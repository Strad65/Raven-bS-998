package keystrokesmod.module.impl.movement;

import keystrokesmod.event.PostPlayerInputEvent;
import keystrokesmod.event.PreEntityVelocityEvent;
import keystrokesmod.event.PreUpdateEvent;
import keystrokesmod.event.ReceivePacketEvent;
import keystrokesmod.event.SendPacketEvent;
import keystrokesmod.module.Module;
import keystrokesmod.module.setting.impl.SliderSetting;
import keystrokesmod.utility.PacketUtils;
import keystrokesmod.utility.Utils;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.network.play.client.C03PacketPlayer.C05PacketPlayerLook;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraft.network.play.server.S12PacketEntityVelocity;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * Port of OpenB4's {@code xyz.daikirai.module.movement.StasisModule}.
 *
 * <p>Freezes the player midair by zeroing local motion and swallowing the outgoing
 * position packets, replacing them with a look-only packet while on Hypixel. Every
 * {@code Pulse} ticks the freeze is released for a single tick (so the server receives
 * the stored motion) and then re-applied. A server teleport (S08) releases the freeze
 * for 3 ticks, a knockback (S12 on the player) for 4 ticks.
 *
 * <p>Mapping notes versus the source module:
 * <ul>
 *    <li>{@code onPreUpdate}/{@code onPostPlayerInput} -&gt; {@link PreUpdateEvent} /
 *        {@link PostPlayerInputEvent}.</li>
 *    <li>{@code onSendPacketAlways}/{@code onVelocityAlways}/{@code onS08} -&gt;
 *        {@link SendPacketEvent} / {@link PreEntityVelocityEvent} /
 *        {@link ReceivePacketEvent}, which only fire while the module is enabled.</li>
 *    <li>{@code Hypixel.onHypixel()} -&gt; {@link Utils#isHypixel()}.</li>
 *    <li>{@code PacketUtils.send} -&gt; {@link PacketUtils#sendPacketNoEvent} so the
 *        replacement look packet does not re-enter this handler.</li>
 * </ul>
 */
public class Stasis extends Module {
   public static Stasis instance;
   public static SliderSetting pulse;
   public static boolean frozen;
   private static double savedX;
   private static double savedY;
   private static double savedZ;
   private static int frozenAt;
   private static int delay;

   public Stasis() {
      super("Stasis", Module.category.movement, 0);
      instance = this;
      this.registerSetting(pulse = new SliderSetting("Pulse", " ticks", "§cDisabled", 65.0, 2.0, 85.0, 1.0));
   }

   @Override
   public void onEnable() {
      delay = 2;
      // Snapshot the motion now so disabling before the first freeze never zeroes it.
      if (!frozen && Utils.nullCheck()) {
         savedX = mc.thePlayer.motionX;
         savedY = mc.thePlayer.motionY;
         savedZ = mc.thePlayer.motionZ;
      }
   }

   @Override
   public void onDisable() {
      freeze(false);
      delay = 0;
   }

   @SubscribeEvent
   public void onPreUpdate(PreUpdateEvent e) {
      if (!Utils.nullCheck()) {
         return;
      }

      if (delay > 0 && --delay == 0) {
         freeze(true);
      }

      if (frozen) {
         mc.thePlayer.motionX = 0.0;
         mc.thePlayer.motionY = 0.0;
         mc.thePlayer.motionZ = 0.0;
         if (!(pulse.getInput() <= pulse.getMin())
            && mc.thePlayer.ticksExisted - frozenAt >= (int)this.pulse.getInput()) {
            freeze(false);
            delay = 1;
         }
      }
   }

   @SubscribeEvent
   public void onPostPlayerInput(PostPlayerInputEvent e) {
      if (!Utils.nullCheck()) {
         return;
      }

      if (frozen || delay == 1) {
         mc.thePlayer.movementInput.moveForward = 0.0F;
         mc.thePlayer.movementInput.moveStrafe = 0.0F;
      }
   }

   @SubscribeEvent
   public void onSendPacket(SendPacketEvent e) {
      if (!frozen || !Utils.nullCheck() || !(e.getPacket() instanceof C03PacketPlayer)) {
         return;
      }

      C03PacketPlayer packet = (C03PacketPlayer)e.getPacket();
      if (Utils.isHypixel() && !(packet instanceof C05PacketPlayerLook)) {
         PacketUtils.sendPacketNoEvent(
            new C05PacketPlayerLook(mc.thePlayer.rotationYaw, mc.thePlayer.rotationPitch, mc.thePlayer.onGround)
         );
         e.setCanceled(true);
      }
   }

   @SubscribeEvent
   public void onReceivePacket(ReceivePacketEvent e) {
      if (e.getPacket() instanceof S08PacketPlayerPosLook) {
         onS08();
      }
   }

   @SubscribeEvent
   public void onPreEntityVelocity(PreEntityVelocityEvent e) {
      S12PacketEntityVelocity packet = e.packet;
      if (mc.thePlayer == null || packet.getEntityID() != mc.thePlayer.getEntityId() || !frozen) {
         return;
      }

      freeze(false);
      delay = 4;
   }

   /** Called when the server teleports the player (OpenB4 calls this from its S08 hook). */
   public static void onS08() {
      freeze(false);
      delay = 3;
   }

   public static void freeze(boolean state) {
      if (!Utils.nullCheck()) {
         frozen = false;
         return;
      }

      if (state) {
         savedX = mc.thePlayer.motionX;
         savedY = mc.thePlayer.motionY;
         savedZ = mc.thePlayer.motionZ;
         frozenAt = mc.thePlayer.ticksExisted;
      } else {
         mc.thePlayer.motionX = savedX;
         mc.thePlayer.motionY = savedY;
         mc.thePlayer.motionZ = savedZ;
      }

      frozen = state;
   }
}
