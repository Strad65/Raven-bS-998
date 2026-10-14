package keystrokesmod.module.impl.combat;

import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;

import keystrokesmod.event.ClientRotationEvent;
import keystrokesmod.event.PostMotionEvent;
import keystrokesmod.event.PreUpdateEvent;
import keystrokesmod.event.SendPacketEvent;
import keystrokesmod.helper.RotationHelper;
import keystrokesmod.module.Module;
import keystrokesmod.module.ModuleManager;
import keystrokesmod.module.setting.impl.ButtonSetting;
import keystrokesmod.module.setting.impl.DescriptionSetting;
import keystrokesmod.module.setting.impl.SliderSetting;
import keystrokesmod.utility.PacketUtils;
import keystrokesmod.utility.Utils;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.Packet;
import net.minecraft.network.handshake.client.C00Handshake;
import net.minecraft.network.login.client.C00PacketLoginStart;
import net.minecraft.util.MovingObjectPosition.MovingObjectType;
import net.minecraft.util.Vec3;
import net.minecraftforge.client.event.MouseEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.opengl.GL11;

/**
 * Port of RavenXD's Displace module (xyz.daikirai.module.combat.DisplaceModule).
 * <p>
 * Redirects your knockback: the hit is performed while silently rotating the
 * sent yaw sideways, so the victim's knockback is displaced to the chosen side
 * instead of straight behind you.
 * <p>
 * Differences caused by the host client's APIs (completed where missing):
 * <ul>
 * <li>Source {@code getRotations(yaw, pitch)} hook -> {@link ClientRotationEvent}
 * (silent rotation pipeline of this client).</li>
 * <li>Source {@code PacketQueue} blink -> self-contained blink on
 * {@link SendPacketEvent} (hold 2 ticks, then flush, like the source).</li>
 * <li>Source {@code WorldUtils.dropSide} -> inlined as {@link #dropSide} (Void
 * mode ground-side detection).</li>
 * <li>Source {@code SettingsModule.hittableHurtTime} (default 3.0) -> constant
 * {@link #HITTABLE_HURT_TIME}.</li>
 * <li>KillAura/AimAssist/AutoClicker integrations use the APIs added to those
 * modules ({@code killAuraOverride}, {@code AimAssist.target/snap},
 * {@code AutoClicker.isClicking()}).</li>
 * </ul>
 */
public class Displace extends Module {
   public static Displace instance;
   private static SliderSetting modeSetting, angle, cooldown;
   private static ButtonSetting hideRotations, onlyKnockbackItems, overrideAttacks, playersOnly, renderArrow, manual, autoClicker, killAura;

   public enum Mode {
      Left, Right, Random, Void
   }

   private static final String[] modes = new String[]{"Left", "Right", "Random", "Void"};
   /** Source client's global "Hittable hurt time" setting default. */
   private static final double HITTABLE_HURT_TIME = 3.0;

   public static Mode mode = Mode.Left;
   public static boolean rotate;
   private static boolean fromAimAssist;
   public static boolean queued;
   public static long lastAt;
   public static boolean holding;
   private static int holdTicks;
   private static int blinkTicks;
   private static boolean blinkActive;
   private static final ConcurrentLinkedQueue<Packet<?>> blinkQueue = new ConcurrentLinkedQueue<>();
   public static int lastSide = -1;
   private static int side = -1;
   private static EntityLivingBase lastTarget;
   private static boolean arrow;
   private static long arrowAt;
   private static double arrowX, arrowY, arrowZ, dirX, dirZ;
   private static final Random RANDOM = new Random();

   public Displace() {
      super("Displace", Module.category.combat, 0);
      instance = this;
      this.registerSetting(new DescriptionSetting("Redirects target knockback."));
      this.registerSetting(modeSetting = new SliderSetting("Mode", 0, this.modes));
      this.registerSetting(angle = new SliderSetting("Angle", 100.0, 10.0, 180.0, 5.0));
      this.registerSetting(cooldown = new SliderSetting("Cooldown", "s", 0.5, 0.2, 8.0, 0.1));
      this.registerSetting(hideRotations = new ButtonSetting("Hide rotations", false));
      this.registerSetting(onlyKnockbackItems = new ButtonSetting("Only knockback items", true));
      this.registerSetting(overrideAttacks = new ButtonSetting("Override attacks", true));
      this.registerSetting(playersOnly = new ButtonSetting("Players only", false));
      this.registerSetting(renderArrow = new ButtonSetting("Render arrow", true));
      this.registerSetting(new DescriptionSetting("Activate with"));
      this.registerSetting(manual = new ButtonSetting("Manual", true));
      this.registerSetting(autoClicker = new ButtonSetting("AutoClicker", true));
      this.registerSetting(killAura = new ButtonSetting("KillAura", true));
   }

   @Override
   public String getInfo() {
      return mode.name();
   }

   @Override
   public void onEnable() {
      mode = Mode.values()[(int) modeSetting.getInput()];
   }

   @Override
   public void guiUpdate() {
      mode = Mode.values()[(int) modeSetting.getInput()];
   }

   /**
    * Silent rotation hook (port of the source {@code getRotations} override).
    * Runs at LOWEST priority so KillAura (LOW) has already applied its silent
    * rotations and we can override them with the displaced yaw.
    */
   @SubscribeEvent(priority = EventPriority.LOWEST)
   public void onClientRotation(ClientRotationEvent e) {
      if (!rotate || !Utils.nullCheck()) {
         return;
      }

      rotate = false;
      if (fromAimAssist) {
         fromAimAssist = false;
         AimAssist.snap = true;
      }

      startBlink();
      this.clickAttack();
      e.yaw = mc.thePlayer.rotationYaw + offset();
      e.pitch = mc.thePlayer.rotationPitch;
   }

   @SubscribeEvent(priority = EventPriority.HIGHEST)
   public void onPreUpdate(PreUpdateEvent e) {
      if (blinkTicks > 0 && --blinkTicks == 0) {
         releaseBlink();
      }

      if (!Utils.nullCheck()) {
         return;
      }

      // Queued KillAura displace. HIGHEST priority so it runs after
      // RotationHelper (which dispatched KillAura's silent rotations for this
      // tick) but before KillAura's own attack decision, so KillAura can
      // suppress its duplicate attack via killAuraOverride().
      if (killAura.isToggled() && KillAura.target != null && canDisplace(KillAura.target, true, true)) {
         trigger(2);
         this.executeQueued();
      }

      if (holdTicks > 0) {
         holding = --holdTicks > 0;
      }
   }

   @SubscribeEvent
   public void onPostMotion(PostMotionEvent e) {
      rotate = false;
      queued = false;
   }

   @SubscribeEvent
   public void onMouse(MouseEvent e) {
      if (e.button != 0 || !e.buttonstate || !Utils.nullCheck()) {
         return;
      }

      if (!autoClicker.isToggled() && isAutoClickerClicking()) {
         return;
      }

      if (!manual.isToggled()) {
         return;
      }

      Entity entity = null;
      boolean aim = false;
      AimAssist aimAssist = (AimAssist) Module.getModule(AimAssist.class);
      if (AimAssist.target != null && aimAssist != null && aimAssist.isEnabled() && aimAssist.isSilentAim()) {
         entity = AimAssist.target;
         aim = true;
      } else if (mc.objectMouseOver != null && mc.objectMouseOver.typeOfHit == MovingObjectType.ENTITY) {
         entity = mc.objectMouseOver.entityHit;
      }

      if (entity == null) {
         return;
      }

      if (canDisplace(entity, true, true)) {
         e.setCanceled(true);
         trigger(aim ? 1 : 0);
      } else if (overrideAttacks.isToggled() && canDisplace(entity, false, false)) {
         e.setCanceled(true);
      }
   }

   @SubscribeEvent(priority = EventPriority.HIGH)
   public void onSendPacket(SendPacketEvent e) {
      if (!blinkActive || e.isCanceled() || mc.isSingleplayer() || !Utils.nullCheck()) {
         return;
      }

      Packet<?> packet = e.getPacket();
      String name = packet.getClass().getSimpleName();
      if (name.startsWith("S") || packet instanceof C00Handshake || packet instanceof C00PacketLoginStart) {
         return;
      }

      blinkQueue.add(packet);
      e.setCanceled(true);
   }

   @SubscribeEvent
   public void onJoin(EntityJoinWorldEvent e) {
      if (e.entity == mc.thePlayer) {
         this.onDisable();
      }
   }

   @Override
   public void onDisable() {
      if (blinkTicks > 0) {
         blinkTicks = 0;
      }

      if (blinkActive) {
         releaseBlink();
      }

      holding = false;
      arrow = false;
      rotate = false;
      fromAimAssist = false;
      queued = false;
      holdTicks = 0;
      lastSide = -1;
      side = -1;
   }

   public static void rearm() {
      holdTicks = 3;
   }

   public static void clear() {
      lastAt = 0L;
   }

   public static void trigger(int source) {
      if (source == 0) {
         rotate = true;
      } else if (source == 1) {
         fromAimAssist = true;
         rotate = true;
      } else if (source == 2) {
         queued = true;
      }

      lastAt = System.currentTimeMillis();
   }

   public static void startBlink() {
      if (hideRotations.isToggled()) {
         blinkTicks = 2;
         blinkActive = true;
      }
   }

   private static void releaseBlink() {
      if (!blinkQueue.isEmpty()) {
         synchronized (blinkQueue) {
            for (Packet<?> packet : blinkQueue) {
               if (keystrokesmod.Raven.packetsHandler != null) {
                  keystrokesmod.Raven.packetsHandler.handlePacket(packet);
               }

               PacketUtils.sendPacketNoEvent(packet);
            }

            blinkQueue.clear();
         }
      }

      blinkActive = false;
   }

   private void clickAttack() {
      KeyBinding.setKeyBindState(mc.gameSettings.keyBindAttack.getKeyCode(), true);
      KeyBinding.onTick(mc.gameSettings.keyBindAttack.getKeyCode());
      KeyBinding.setKeyBindState(mc.gameSettings.keyBindAttack.getKeyCode(), false);
   }

   /**
    * Consumes a queued KillAura displace: offsets the server yaw KillAura just
    * applied this tick and performs the attack with it (port of the source
    * KillAura {@code takeRotations} displaced attack).
    */
   private void executeQueued() {
      if (!queued) {
         return;
      }

      queued = false;
      if (KillAura.target == null) {
         clear();
         return;
      }

      Float serverYaw = RotationHelper.get().getServerYaw();
      if (serverYaw != null && !serverYaw.isNaN()) {
         RotationHelper.get().setYaw(serverYaw + offset());
      } else {
         RotationHelper.get().setYaw(mc.thePlayer.rotationYaw + offset());
      }

      startBlink();
      mc.playerController.attackEntity(mc.thePlayer, KillAura.target);
      lastAt = System.currentTimeMillis();
   }

   private static boolean isAutoClickerClicking() {
      Module module = ModuleManager.autoClicker;
      return module != null && module.isEnabled() && module instanceof AutoClicker && ((AutoClicker) module).isClicking();
   }

   /**
    * KillAura attack policy (port of the source KillAura Displace checks).
    * Return values:
    * <ul>
    * <li>0 - attack normally.</li>
    * <li>1 - suppress only the attack (swing may proceed); the displaced attack
    * is handled by this module.</li>
    * <li>2 - suppress attack and swing.</li>
    * </ul>
    */
   public static int killAuraOverride(EntityLivingBase entity) {
      Displace displace = instance;
      if (displace == null || !displace.isEnabled() || entity == null || !killAura.isToggled()) {
         return 0;
      }

      if (queued) {
         return 1;
      }

      if (lastAt > 0L && Math.abs(System.currentTimeMillis() - lastAt) < 60L) {
         rearm();
         return 2;
      }

      if (holding) {
         return 2;
      }

      if (overrideAttacks.isToggled() && canDisplace(entity, false, false)) {
         return 2;
      }

      return 0;
   }

   public static boolean canDisplace(Entity entity, boolean checkHurtTime, boolean checkCooldown) {
      if (!(entity instanceof EntityLivingBase)) {
         return false;
      }

      if (checkHurtTime && (double) ((EntityLivingBase) entity).hurtTime > HITTABLE_HURT_TIME) {
         return false;
      }

      if (playersOnly.isToggled() && !(entity instanceof EntityPlayer)) {
         return false;
      }

      int knockback = EnchantmentHelper.getKnockbackModifier(mc.thePlayer);
      if (!mc.thePlayer.isSprinting() && knockback == 0) {
         return false;
      }

      if (onlyKnockbackItems.isToggled() && knockback == 0) {
         return false;
      }

      if (checkCooldown && (double) Math.abs(System.currentTimeMillis() - lastAt) < cooldown.getInput() * 1000.0 - 20.0) {
         return false;
      }

      if (mode == Mode.Void && (side = (lastSide = dropSide(entity, lastSide))) == -1) {
         return false;
      }

      lastTarget = (EntityLivingBase) entity;
      return true;
   }

   public static float offset() {
      float f = (float) angle.getInput();
      if (side != -1) {
         if (side == 0) {
            f = -f;
         }

         side = -1;
      } else if (mode == Mode.Left || mode == Mode.Random && RANDOM.nextBoolean()) {
         f = -f;
      }

      f += (float) Utils.randomizeDouble(-5.0, 5.0);
      if (renderArrow.isToggled() && lastTarget != null) {
         setArrow(lastTarget, f);
      }

      return f;
   }

   /**
    * Port of the source WorldUtils.dropSide: detects on which side of the
    * player (0 = left, 1 = right) the ground drops away around the target, so
    * Void mode can knock the target towards the drop. Returns -1 when neither
    * side is a valid drop.
    */
   private static int dropSide(Entity entity, int previous) {
      double dx = entity.posX - mc.thePlayer.posX;
      double dz = entity.posZ - mc.thePlayer.posZ;
      double len = Math.sqrt(dx * dx + dz * dz);
      if (len < 0.001) {
         return -1;
      }

      double px = -(dz /= len);
      double pz = dx /= len;
      double y = entity.posY;
      int minusSide = 0;
      int plusSide = 0;
      int behind = 0;
      for (int n4 : new int[]{1, 2, 3, 4, 6, 8}) {
         int weight = n4 < 6 ? 2 : 1;
         double bx = entity.posX + dx * (double) n4;
         double bz = entity.posZ + dz * (double) n4;
         for (int i = -1; i <= 1; ++i) {
            double x = bx + px * (double) i;
            double z = bz + pz * (double) i;
            if (mc.theWorld.rayTraceBlocks(new Vec3(x, y, z), new Vec3(x, y - 6.0, z), true) != null) {
               continue;
            }

            behind += weight;
         }

         double mx = entity.posX - px * (double) n4;
         double mz = entity.posZ - pz * (double) n4;
         for (int i = -1; i <= 1; ++i) {
            double x = mx + dx * (double) i;
            double z = mz + dz * (double) i;
            if (mc.theWorld.rayTraceBlocks(new Vec3(x, y, z), new Vec3(x, y - 6.0, z), true) != null) {
               continue;
            }

            minusSide += weight;
         }

         double qx = entity.posX + px * (double) n4;
         double qz = entity.posZ + pz * (double) n4;
         for (int i = -1; i <= 1; ++i) {
            double x = qx + dx * (double) i;
            double z = qz + dz * (double) i;
            if (mc.theWorld.rayTraceBlocks(new Vec3(x, y, z), new Vec3(x, y - 6.0, z), true) != null) {
               continue;
            }

            plusSide += weight;
         }
      }

      if (minusSide == 0 && plusSide == 0) {
         return -1;
      }

      if (behind >= minusSide && behind >= plusSide) {
         return -1;
      }

      if (minusSide == plusSide && previous != -1) {
         return previous;
      }

      return minusSide > plusSide ? 0 : 1;
   }

   @SubscribeEvent
   public void onRenderWorld(RenderWorldLastEvent e) {
      if (!arrow || !renderArrow.isToggled()) {
         return;
      }

      if (!Utils.nullCheck()) {
         return;
      }

      long l = System.currentTimeMillis() - arrowAt;
      if (l > 600L) {
         arrow = false;
         return;
      }

      double d2;
      double d;
      double alpha = 1.0;
      d2 = (double) l / 100.0;
      d2 = d2 < 0.0 ? 0.0 : Math.min(d2, 1.0);
      d = d2 * d2 * (3.0 - 2.0 * d2);
      alpha *= 0.08 + 0.92 * d;
      if (l > 500L) {
         d2 = (double) (l - 500L) / 100.0;
         d2 = d2 < 0.0 ? 0.0 : Math.min(d2, 1.0);
         d = d2 * d2 * (3.0 - 2.0 * d2);
         alpha *= 1.0 - 0.92 * d;
      }

      d2 = (double) l / 450.0;
      d2 = d2 < 0.0 ? 0.0 : Math.min(d2, 1.0);
      d = 1.0 - d2;
      d2 = 1.0 - d * d * d;
      d = Math.sin((double) l * 0.015) * 0.02 * (0.4 + 0.6 * alpha);
      double dist = 0.8 * d2;
      double vx = mc.getRenderManager().viewerPosX;
      double vy = mc.getRenderManager().viewerPosY;
      double vz = mc.getRenderManager().viewerPosZ;
      double x = arrowX - vx + dirX * dist;
      double y = arrowY - vy + d;
      double z = arrowZ - vz + dirZ * dist;
      double yaw = Math.toDegrees(Math.atan2(-dirZ, dirX));
      int fill = (int) (225.0 * alpha) << 24 | 0xFFFFFF;
      int outline = (int) (255.0 * alpha) << 24 | 0xF6F6F6;
      int shadow = (int) (140.0 * alpha) << 24 | 0x5081A;
      GlStateManager.pushMatrix();
      GlStateManager.disableTexture2D();
      GlStateManager.enableBlend();
      GlStateManager.disableCull();
      GlStateManager.depthMask(false);
      GL11.glDisable(2929);
      GlStateManager.translate(x, y, z);
      GlStateManager.rotate((float) yaw, 0.0f, 1.0f, 0.0f);
      GL11.glPushMatrix();
      GL11.glTranslated(0.08 * 0.48, -0.08 * 0.24, -0.02);
      color(shadow);
      GL11.glBegin(7);
      GL11.glVertex3d(-0.2784 * 1.01, -0.0816 * 1.01, 0.0);
      GL11.glVertex3d(0.0 * 1.01, -0.0288 * 1.01, 0.0);
      GL11.glVertex3d(0.0 * 1.01, 0.0288 * 1.01, 0.0);
      GL11.glVertex3d(-0.2784 * 1.01, 0.0816 * 1.01, 0.0);
      GL11.glEnd();
      GL11.glBegin(4);
      GL11.glVertex3d(0.0, -0.144 * 1.01, 0.0);
      GL11.glVertex3d(0.288 * 1.01, 0.0, 0.0);
      GL11.glVertex3d(0.0, 0.144 * 1.01, 0.0);
      GL11.glEnd();
      GL11.glPopMatrix();
      color(fill);
      GL11.glBegin(7);
      GL11.glVertex3d(-0.2784, -0.0816, 0.0);
      GL11.glVertex3d(0.0, -0.0288, 0.0);
      GL11.glVertex3d(0.0, 0.0288, 0.0);
      GL11.glVertex3d(-0.2784, 0.0816, 0.0);
      GL11.glEnd();
      GL11.glBegin(4);
      GL11.glVertex3d(0.0, -0.144, 0.0);
      GL11.glVertex3d(0.288, 0.0, 0.0);
      GL11.glVertex3d(0.0, 0.144, 0.0);
      GL11.glEnd();
      GL11.glEnable(2848);
      GL11.glLineWidth(2.0f);
      color(outline);
      GL11.glBegin(2);
      GL11.glVertex3d(-0.2784, -0.0816, 0.0);
      GL11.glVertex3d(0.0, -0.0288, 0.0);
      GL11.glVertex3d(0.0, 0.0288, 0.0);
      GL11.glVertex3d(-0.2784, 0.0816, 0.0);
      GL11.glEnd();
      GL11.glBegin(2);
      GL11.glVertex3d(0.0, -0.144, 0.0);
      GL11.glVertex3d(0.288, 0.0, 0.0);
      GL11.glVertex3d(0.0, 0.144, 0.0);
      GL11.glEnd();
      GL11.glDisable(2848);
      GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
      GL11.glEnable(2929);
      GlStateManager.depthMask(true);
      GlStateManager.enableCull();
      GlStateManager.disableBlend();
      GlStateManager.enableTexture2D();
      GlStateManager.popMatrix();
   }

   private static void color(int c) {
      GlStateManager.color(
         (float) (c >> 16 & 0xFF) / 255.0f,
         (float) (c >> 8 & 0xFF) / 255.0f,
         (float) (c & 0xFF) / 255.0f,
         (float) (c >> 24 & 0xFF) / 255.0f
      );
   }

   private static void setArrow(EntityLivingBase entity, float f) {
      double d = Math.toRadians(mc.thePlayer.rotationYaw + f);
      dirX = -Math.sin(d);
      dirZ = Math.cos(d);
      arrowX = entity.posX + dirX * 0.8;
      arrowY = entity.posY + 1.2;
      arrowZ = entity.posZ + dirZ * 0.8;
      arrowAt = System.currentTimeMillis();
      arrow = true;
   }
}
