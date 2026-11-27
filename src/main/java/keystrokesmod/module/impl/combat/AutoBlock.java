package keystrokesmod.module.impl.combat;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import keystrokesmod.event.PostMotionEvent;
import keystrokesmod.event.PreEntityVelocityEvent;
import keystrokesmod.event.SendPacketEvent;
import keystrokesmod.module.Module;
import keystrokesmod.module.ModuleManager;
import keystrokesmod.module.impl.world.AntiBot;
import keystrokesmod.module.setting.impl.ButtonSetting;
import keystrokesmod.module.setting.impl.SliderSetting;
import keystrokesmod.utility.BlockUtils;
import keystrokesmod.utility.PacketUtils;
import keystrokesmod.utility.ReflectionUtils;
import keystrokesmod.utility.RotationUtils;
import keystrokesmod.utility.Utils;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.monster.EntityGolem;
import net.minecraft.entity.monster.EntityMob;
import net.minecraft.entity.monster.EntitySlime;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraft.network.play.server.S12PacketEntityVelocity;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraftforge.client.event.MouseEvent;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Mouse;

/**
 * Port of OpenB4's {@code xyz.daikirai.module.combat.AutoBlockModule}.
 *
 * <p>Holds the use-item key for a short duration while an enemy is in range so the
 * server receives a block packet right before/after each hit, optionally faking the
 * client-side block animation (force block animation) and optionally lagging the
 * outgoing packet stream (lag chance / lag duration) in the same way OpenB4 does.
 *
 * <p>Mapping notes versus the source module:
 * <ul>
 *    <li>{@code PacketQueue} -&gt; a local held-packet list guarded by {@link SendPacketEvent}.</li>
 *    <li>{@code isFakeBlocking()} -&gt; {@link ReflectionUtils#setItemInUse(boolean)} which is the
 *        same client-side block animation switch used by KillAura/NoSlow in this client.</li>
 *    <li>{@code onSendPacket}/{@code onKnockback}/{@code onPostMotion} -&gt; the equivalent Forge
 *        events of this client ({@link SendPacketEvent}, {@link PreEntityVelocityEvent},
 *        {@link PostMotionEvent}).</li>
 *    <li>{@code PlayerUtils/BotCheck/Hypixel} helpers -&gt; {@link Utils}, {@link AntiBot} and
 *        {@link RotationUtils}.</li>
 * </ul>
 */
public class AutoBlock extends Module {
   private static final Random RANDOM = new Random();

   private SliderSetting maxHurtTime;
   private SliderSetting maxHoldDuration;
   private SliderSetting randomHoldDuration;
   private SliderSetting cooldown;
   private SliderSetting range;
   private ButtonSetting disableBlockOutOfRange;
   private ButtonSetting disableWhileChasing;
   private ButtonSetting forceBlockAnimation;
   private ButtonSetting ignoreMobs;
   private ButtonSetting ignoreTeammates;
   private ButtonSetting onlyWhileLeftClicking;
   private ButtonSetting onlyWhileRightClicking;
   private ButtonSetting onlyWhileTargeting;
   private SliderSetting lagChance;
   private SliderSetting lagDuration;
   private ButtonSetting flushOnAttack;
   private ButtonSetting flushOnInteractItem;

   /** Outgoing packets held while {@link #lagging} is active (replacement for OpenB4's PacketQueue). */
   private final List<Packet<?>> heldPackets = new ArrayList<>();

   /** True while the client is only pretending to block (block animation without a block packet). */
   private boolean fakeBlocking;
   /** True when {@link #fakeBlocking} has already been pushed to the player's item-in-use state. */
   private boolean fakeBlockingApplied;

   private int holdTicks;
   private int cooldownTicks;
   private boolean hadSword;

   private boolean lagging;
   private long lagUntil;
   private boolean startLag;
   private boolean flush;
   private int lagAccumulator;

   private double rangeValue;
   private double rangeSq;

   public AutoBlock() {
      super("AutoBlock", Module.category.combat, 0);

      this.registerSetting(this.maxHurtTime = new SliderSetting("Max hurt time", "ms", 100.0, 0.0, 500.0, 50.0));
      this.registerSetting(this.maxHoldDuration = new SliderSetting("Max hold duration", "ms", 150.0, 50.0, 1000.0, 50.0));
      this.registerSetting(this.randomHoldDuration = new SliderSetting("Random hold duration", "ms", 0.0, 0.0, 1000.0, 50.0));
      this.registerSetting(this.cooldown = new SliderSetting("Cooldown", "ms", 250.0, 0.0, 1000.0, 50.0));
      this.registerSetting(this.range = new SliderSetting("Range", 3.0, 1.0, 6.1, 0.1));
      this.registerSetting(this.disableBlockOutOfRange = new ButtonSetting("Disable block out of range", false));
      this.registerSetting(this.disableWhileChasing = new ButtonSetting("Disable while chasing", false));
      this.registerSetting(this.forceBlockAnimation = new ButtonSetting("Force block animation", false));
      this.registerSetting(this.ignoreMobs = new ButtonSetting("Ignore mobs", false));
      this.registerSetting(this.ignoreTeammates = new ButtonSetting("Ignore teammates", true));
      this.registerSetting(this.onlyWhileLeftClicking = new ButtonSetting("Only while left clicking", false));
      this.registerSetting(this.onlyWhileRightClicking = new ButtonSetting("Only while right clicking", false));
      this.registerSetting(this.onlyWhileTargeting = new ButtonSetting("Only while targeting", false));
      this.registerSetting(this.lagChance = new SliderSetting("Lag chance", "%", 0.0, 0.0, 100.0, 2.0));
      this.registerSetting(this.lagDuration = new SliderSetting("Lag duration", "ms", 200.0, 10.0, 800.0, 10.0));
      this.registerSetting(this.flushOnAttack = new ButtonSetting("Flush on attack", true));
      this.registerSetting(this.flushOnInteractItem = new ButtonSetting("Flush on interact item", true));

      this.updateRange();
   }

   @Override
   public void guiUpdate() {
      this.updateRange();
   }

   @Override
   public void onEnable() {
      this.reset();
   }

   @Override
   public void onDisable() {
      this.reset();
   }

   /** Mirrors OpenB4's {@code Module#isFakeBlocking()} hook. */
   public boolean isFakeBlocking() {
      return this.fakeBlocking;
   }

   private void reset() {
      this.hadSword = false;
      this.flush = false;
      this.startLag = false;
      this.stopLag();
      this.applyFakeBlocking(false);
      if (this.holdTicks > 0) {
         this.holdTicks = 0;
         releaseUseItem();
      }

      this.cooldownTicks = 0;
   }

   @SubscribeEvent
   public void onPostMotion(PostMotionEvent e) {
      if (!Utils.nullCheck()) {
         this.discardHeldPackets();
         return;
      }

      if (this.flush) {
         this.flush = false;
         if (this.lagging) {
            this.stopLag();
         }
      }

      if (this.startLag) {
         this.startLag = false;
         if (!this.lagging) {
            this.lagging = true;
         }

         this.lagUntil = System.currentTimeMillis() + (long)(int)this.lagDuration.getInput();
      }

      boolean right = Mouse.isButtonDown(1);
      boolean sword = Utils.holdingSword();
      boolean manual = right && !this.onlyWhileRightClicking.isToggled();
      boolean keepHeld = right && (!this.onlyWhileRightClicking.isToggled() || !sword);
      boolean block;
      if (!this.forceBlockAnimation.isToggled()) {
         block = this.canBlock() && this.hurtTimeOk() && this.conditions();
         this.applyFakeBlocking(false);
      } else {
         boolean fake = this.canBlock() && this.conditions();
         this.applyFakeBlocking(fake);
         block = fake && this.hurtTimeOk();
      }

      if (this.cooldownTicks > 0) {
         this.cooldownTicks--;
      }

      if (this.holdTicks > 0) {
         this.holdTicks--;
         if (!block || this.holdTicks == 0) {
            this.holdTicks = 0;
            if (!keepHeld) {
               this.release();
            }
         }
      } else if (block && !manual && this.cooldownTicks == 0) {
         int hold = (int)Math.ceil((double)(int)this.maxHoldDuration.getInput() / 50.0);
         int random = 0;
         if (!(this.randomHoldDuration.getInput() <= this.randomHoldDuration.getMin())) {
            random = (int)Math.ceil((double)(int)this.randomHoldDuration.getInput() / 50.0);
            random = Math.min(RANDOM.nextInt(random + 1), RANDOM.nextInt(random + 1));
         }

         this.holdTicks = hold + random;
         pressUseItem();
      }

      if (this.onlyWhileRightClicking.isToggled() && right && !sword && this.hadSword) {
         pressUseItem();
      }

      this.hadSword = sword;
   }

   @SubscribeEvent
   public void onRenderTick(TickEvent.RenderTickEvent e) {
      if (!this.lagging || e.phase == TickEvent.Phase.START) {
         return;
      }

      if (System.currentTimeMillis() >= this.lagUntil) {
         this.stopLag();
      }
   }

   @SubscribeEvent(priority = EventPriority.HIGHEST)
   public void onSendPacket(SendPacketEvent e) {
      if (e.isCanceled() || !Utils.nullCheck() || mc.isSingleplayer()) {
         return;
      }

      Packet<?> packet = e.getPacket();
      if (this.lagging) {
         if (packet instanceof C02PacketUseEntity) {
            if (this.flushOnAttack.isToggled() && ((C02PacketUseEntity)packet).getAction() == C02PacketUseEntity.Action.ATTACK) {
               this.flush = true;
            }
         } else if (packet instanceof C08PacketPlayerBlockPlacement
            && this.flushOnInteractItem.isToggled()
            && !isSword(((C08PacketPlayerBlockPlacement)packet).getStack())) {
            this.flush = true;
         }

         this.heldPackets.add(packet);
         e.setCanceled(true);
      } else if (packet instanceof C08PacketPlayerBlockPlacement) {
         C08PacketPlayerBlockPlacement placement = (C08PacketPlayerBlockPlacement)packet;
         if (placement.getPlacedBlockDirection() == 255 && isSword(placement.getStack()) && this.rollLag()) {
            this.startLag = true;
         }
      }
   }

   @SubscribeEvent
   public void onPreEntityVelocity(PreEntityVelocityEvent e) {
      if (!Utils.nullCheck()) {
         return;
      }

      S12PacketEntityVelocity packet = e.packet;
      if (packet.getEntityID() != mc.thePlayer.getEntityId()) {
         return;
      }

      if (this.holdTicks > 0) {
         this.holdTicks = 0;
         this.release();
      }
   }

   @SubscribeEvent
   public void onWorldJoin(EntityJoinWorldEvent e) {
      if (e.entity == mc.thePlayer) {
         this.discardHeldPackets();
         this.holdTicks = 0;
         this.cooldownTicks = 0;
         this.hadSword = false;
         this.applyFakeBlocking(false);
      }
   }

   @SubscribeEvent
   public void onMouse(MouseEvent e) {
      if (e.button != 1 || !e.buttonstate || !Utils.nullCheck()) {
         return;
      }

      if (this.disableBlockOutOfRange.isToggled()
         && this.canBlock()
         && !BlockUtils.isInteractable(mc.objectMouseOver)
         && !this.lookingAtBot()) {
         e.setCanceled(true);
      }
   }

   private void updateRange() {
      double value = this.range.getInput();
      if (value != this.rangeValue) {
         this.rangeValue = value;
         double expanded = value + 1.2;
         this.rangeSq = expanded * expanded;
      }
   }

   private boolean hurtTimeOk() {
      int ticks = (int)Math.ceil((double)(int)this.maxHurtTime.getInput() / 50.0);
      return mc.thePlayer.hurtTime <= ticks;
   }

   /**
    * OpenB4 stops blocking when KillAura is already packet-blocking its own target, so the
    * same guard is applied here against this client's KillAura autoblock setting.
    */
   private boolean canBlock() {
      if (!Utils.nullCheck() || mc.currentScreen != null || !Utils.holdingSword()) {
         return false;
      }

      return ModuleManager.killAura == null
         || KillAura.target == null
         || ModuleManager.killAura.autoBlockMode.getInput() <= 0.0;
   }

   private boolean conditions() {
      if (this.onlyWhileTargeting.isToggled() && !this.targetMatches(this.ignoreMobs.isToggled() ? 0 : 1)) {
         return false;
      }

      if (this.onlyWhileLeftClicking.isToggled() && !Utils.isClicking()) {
         return false;
      }

      if (this.onlyWhileRightClicking.isToggled() && !Mouse.isButtonDown(1)) {
         return false;
      }

      return this.enemyInRange();
   }

   private boolean enemyInRange() {
      this.updateRange();
      if (this.range.getInput() >= this.range.getMax()) {
         return true;
      }

      for (Entity entity : mc.theWorld.loadedEntityList) {
         if (!(entity instanceof EntityLivingBase)) {
            continue;
         }

         EntityLivingBase living = (EntityLivingBase)entity;
         if (living == mc.thePlayer || living.deathTime != 0) {
            continue;
         }

         if (AntiBot.isBot(living)) {
            continue;
         }

         if (mc.thePlayer.getDistanceSqToEntity(living) > this.rangeSq) {
            continue;
         }

         if (living instanceof EntityPlayer) {
            if (Utils.isFriended((EntityPlayer)living)) {
               continue;
            }
         } else if (this.ignoreMobs.isToggled() || !isMob(living)) {
            continue;
         }

         if (this.disableWhileChasing.isToggled() && awayFromPlayer(living)) {
            continue;
         }

         if (!RotationUtils.isPossibleToHit(living, this.rangeValue, RotationUtils.getRotations(living))) {
            continue;
         }

         if (this.ignoreTeammates.isToggled() && Utils.isTeammate(living)) {
            continue;
         }

         return true;
      }

      return false;
   }

   /** Same target sources OpenB4's {@code PlayerUtils.targetMatches} uses (KillAura + crosshair). */
   private boolean targetMatches(int type) {
      Entity killAuraTarget = ModuleManager.killAura != null ? ModuleManager.killAura.target : null;
      Entity mouseOver = mc.objectMouseOver != null ? mc.objectMouseOver.entityHit : null;
      return matches(killAuraTarget, type) || matches(mouseOver, type);
   }

   private static boolean matches(Entity entity, int type) {
      if (entity == null) {
         return false;
      }

      switch (type) {
         case 0:
            return entity instanceof EntityPlayer;
         case 1:
            return entity instanceof EntityPlayer || isMob(entity);
         default:
            return true;
      }
   }

   /** Same mob set as OpenB4's {@code DamageTagsModule.isMob}. */
   private static boolean isMob(Entity entity) {
      return entity instanceof EntityMob || entity instanceof EntityGolem || entity instanceof EntitySlime;
   }

   private boolean lookingAtBot() {
      return mc.objectMouseOver != null
         && mc.objectMouseOver.typeOfHit == MovingObjectPosition.MovingObjectType.ENTITY
         && AntiBot.isBot(mc.objectMouseOver.entityHit);
   }

   private static boolean awayFromPlayer(EntityLivingBase entity) {
      double distance = mc.thePlayer.getDistanceSqToEntity(entity);
      return distance > 1.0 && !facingPlayer(entity, 90.0F);
   }

   private static boolean facingPlayer(EntityLivingBase entity, float fov) {
      float yawToPlayer = yawBetween(entity.posX, entity.posZ, mc.thePlayer.posX, mc.thePlayer.posZ);
      double delta = MathHelper.wrapAngleTo180_double((double)(yawToPlayer - entity.rotationYaw));
      return Math.abs(delta) <= (double)fov;
   }

   private static float yawBetween(double fromX, double fromZ, double toX, double toZ) {
      double dx = toX - fromX;
      double dz = toZ - fromZ;
      return (float)(Math.atan2(dz, dx) * 180.0 / Math.PI) - 90.0F;
   }

   private boolean rollLag() {
      if (this.lagChance.getInput() <= this.lagChance.getMin() || mc.isSingleplayer()) {
         return false;
      }

      this.lagAccumulator += (int)this.lagChance.getInput();
      if (this.lagAccumulator >= 100) {
         this.lagAccumulator -= 100;
         return true;
      }

      return false;
   }

   private void stopLag() {
      this.flush = false;
      this.lagging = false;
      this.flushHeldPackets();
   }

   private void flushHeldPackets() {
      if (this.heldPackets.isEmpty()) {
         return;
      }

      if (Utils.nullCheck()) {
         List<Packet<?>> packets = new ArrayList<>(this.heldPackets);
         this.heldPackets.clear();

         for (Packet<?> packet : packets) {
            PacketUtils.sendPacketNoEvent(packet);
         }
      } else {
         this.heldPackets.clear();
      }
   }

   private void discardHeldPackets() {
      this.lagging = false;
      this.flush = false;
      this.startLag = false;
      this.heldPackets.clear();
   }

   private void release() {
      releaseUseItem();
      if (!(this.cooldown.getInput() <= this.cooldown.getMin())) {
         this.cooldownTicks = (int)Math.ceil((double)(int)this.cooldown.getInput() / 50.0);
      }
   }

   private void applyFakeBlocking(boolean state) {
      this.fakeBlocking = state;
      if (state && !this.fakeBlockingApplied) {
         ReflectionUtils.setItemInUse(true);
         this.fakeBlockingApplied = true;
      } else if (!state && this.fakeBlockingApplied) {
         ReflectionUtils.setItemInUse(false);
         this.fakeBlockingApplied = false;
      }
   }

   private static boolean isSword(ItemStack stack) {
      return stack != null && stack.getItem() instanceof ItemSword;
   }

   private static void pressUseItem() {
      int key = mc.gameSettings.keyBindUseItem.getKeyCode();
      KeyBinding.setKeyBindState(key, true);
      KeyBinding.onTick(key);
   }

   private static void releaseUseItem() {
      KeyBinding.setKeyBindState(mc.gameSettings.keyBindUseItem.getKeyCode(), false);
   }
}
