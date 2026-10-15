package keystrokesmod.module.impl.combat;

import keystrokesmod.event.PostMotionEvent;
import keystrokesmod.event.PreEntityVelocityEvent;
import keystrokesmod.event.PreExplosionPacketEvent;
import keystrokesmod.event.PreUpdateEvent;
import keystrokesmod.module.Module;
import keystrokesmod.module.setting.impl.ButtonSetting;
import keystrokesmod.module.setting.impl.SliderSetting;
import keystrokesmod.utility.Utils;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.network.play.server.S12PacketEntityVelocity;
import net.minecraft.network.play.server.S27PacketExplosion;
import net.minecraft.potion.Potion;
import net.minecraft.util.MathHelper;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * Ported from RavenXD ({@code xyz.daikirai.module.combat.JumpResetModule}).
 *
 * <p>Jumps the moment knockback lands so the sprint-jump boost cancels part of the horizontal
 * knockback. A jump is only sent when the packet knockback vector proves the jump actually helps
 * (at least a 3% reduction of the horizontal knockback), and explosion knockback is ignored.
 */
public class JumpReset extends Module {
   private SliderSetting chance;
   private ButtonSetting minimizeVertical;
   private ButtonSetting onlyWhileTargeting;

   /** A knockback packet was received this tick and still has to be answered. */
   private boolean pending;
   /** Set while the current knockback came from an explosion, which cannot be jump-reset. */
   private boolean explosion;
   /** True while this module holds the jump key down. */
   private boolean setJump;

   private double kbX;
   private double kbZ;
   private int kbY;

   public JumpReset() {
      super("Jump Reset", Module.category.combat);
      this.registerSetting(this.chance = new SliderSetting("Chance", "%", 80.0, 0.0, 100.0, 2.0));
      this.registerSetting(this.minimizeVertical = new ButtonSetting("Minimize vertical", false));
      this.registerSetting(this.onlyWhileTargeting = new ButtonSetting("Only while targeting", false));
      this.closetModule = true;
   }

   @Override
   public String getInfo() {
      return (int) this.chance.getInput() == 100 ? "" : (int) this.chance.getInput() + "%";
   }

   @SubscribeEvent
   public void onPreExplosionPacket(PreExplosionPacketEvent e) {
      if (!Utils.nullCheck()) {
         return;
      }

      S27PacketExplosion packet = e.packet;
      if (mc.thePlayer.getDistanceSq(packet.getX(), packet.getY(), packet.getZ()) > 36.0) {
         return;
      }

      this.explosion = true;
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

      int motionY = packet.getMotionY();
      // Same knockback filter as RavenXD's PacketUtils.isKnockback.
      if (motionY <= 0 || motionY >= 4500) {
         return;
      }

      if (this.explosion) {
         return;
      }

      if (this.minimizeVertical.isToggled() && motionY < 3000) {
         return;
      }

      this.kbX = packet.getMotionX() / 8000.0;
      this.kbY = motionY;
      this.kbZ = packet.getMotionZ() / 8000.0;
      this.pending = true;
   }

   @SubscribeEvent
   public void onPreUpdate(PreUpdateEvent e) {
      if (!this.pending) {
         return;
      }

      this.pending = false;
      if (!mc.thePlayer.onGround || !mc.thePlayer.isSprinting()) {
         return;
      }

      if (mc.thePlayer.isInWater() || mc.thePlayer.isInLava() || mc.thePlayer.isRiding() || mc.thePlayer.isPotionActive(Potion.jump)) {
         return;
      }

      if (this.onlyWhileTargeting.isToggled() && !this.targeting()) {
         return;
      }

      if (this.chance.getInput() != 100.0 && !(Math.random() < this.chance.getInput() / 100.0)) {
         return;
      }

      if (!this.helps()) {
         return;
      }

      KeyBinding.setKeyBindState(mc.gameSettings.keyBindJump.getKeyCode(), this.setJump = true);
      KeyBinding.onTick(mc.gameSettings.keyBindJump.getKeyCode());
   }

   @SubscribeEvent
   public void onPostMotion(PostMotionEvent e) {
      if (this.setJump && !Utils.jumpDown()) {
         KeyBinding.setKeyBindState(mc.gameSettings.keyBindJump.getKeyCode(), this.setJump = false);
      }
   }

   @Override
   public void onUpdate() {
      this.explosion = false;
   }

   @Override
   public void onDisable() {
      this.explosion = false;
      this.pending = false;
      if (this.setJump && !Utils.jumpDown()) {
         KeyBinding.setKeyBindState(mc.gameSettings.keyBindJump.getKeyCode(), this.setJump = false);
      } else {
         this.setJump = false;
      }
   }

   /** Mirrors RavenXD's {@code PlayerUtils.targetMatches(1)} with this client's targeting sources. */
   private boolean targeting() {
      if (KillAura.target != null || AimAssist.target != null) {
         return true;
      }

      return mc.objectMouseOver != null && mc.objectMouseOver.entityHit != null;
   }

   private boolean helps() {
      if (this.kbX == 0.0 && this.kbZ == 0.0) {
         return false;
      }

      double kb = Math.sqrt(this.kbX * this.kbX + this.kbZ * this.kbZ);
      // RavenXD's RotationManager.movementYaw() falls back to the player's own yaw, which is also
      // the yaw EntityLivingBase#jump uses for the sprint-jump boost.
      float yaw = MathHelper.wrapAngleTo180_float(mc.thePlayer.rotationYaw);
      double after = withJump(this.kbX, this.kbZ, yaw);
      double less = (kb - after) / kb;
      return less >= 0.03;
   }

   private static double withJump(double x, double z, float yaw) {
      double r = Math.toRadians(yaw);
      double nx = x + -Math.sin(r) * 0.2;
      double nz = z + Math.cos(r) * 0.2;
      return Math.sqrt(nx * nx + nz * nz);
   }
}
