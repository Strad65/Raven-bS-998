package keystrokesmod.module.impl.combat;

import keystrokesmod.event.PreUpdateEvent;
import keystrokesmod.event.SendPacketEvent;
import keystrokesmod.module.Module;
import keystrokesmod.module.ModuleManager;
import keystrokesmod.module.impl.world.AntiBot;
import keystrokesmod.module.setting.impl.ButtonSetting;
import keystrokesmod.module.setting.impl.SliderSetting;
import keystrokesmod.utility.BlinkHandler;
import keystrokesmod.utility.PacketUtils;
import keystrokesmod.utility.RotationUtils;
import keystrokesmod.utility.Utils;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

public class AutoBlock extends Module {
   private SliderSetting range;
   private SliderSetting blinkDuration;
   private SliderSetting cycleCooldown;
   private ButtonSetting releaseOnAttack;
   private ButtonSetting renderTimer;

   public boolean blink;
   private boolean isBlocking;
   private boolean inCycle;
   private int tickCounter;
   private int blinkTicksDuration;
   private int cooldownTicks;
   private EntityLivingBase nearestEnemy;

   public AutoBlock() {
      super("AutoBlock", Module.category.combat);
      this.registerSetting(range = new SliderSetting("Range", 2.0, 2.0, 4.5, 0.1));
      this.registerSetting(blinkDuration = new SliderSetting("Blink duration", 1.0, 1.0, 10.0, 1.0));
      this.registerSetting(cycleCooldown = new SliderSetting("Cycle cooldown", 0.0, 0.0, 10.0, 1.0));
      this.registerSetting(releaseOnAttack = new ButtonSetting("Release on attack", true));
      this.registerSetting(renderTimer = new ButtonSetting("Render timer", true));
   }

   @Override
   public void onEnable() {
      this.reset();
   }

   @Override
   public void onDisable() {
      this.reset();
      if (this.blink) {
         BlinkHandler.release();
      }
   }

   @SubscribeEvent
   public void onPreUpdate(PreUpdateEvent e) {
      if (!Utils.nullCheck() || !Utils.holdingSword()) {
         if (this.blink) {
            BlinkHandler.release();
            this.blink = false;
         }
         this.reset();
         return;
      }

      // Find nearest enemy in range
      this.nearestEnemy = this.findNearestEnemy();

      if (this.nearestEnemy == null) {
         // No enemy in range - release blink and reset
         if (this.blink) {
            BlinkHandler.release();
            this.blink = false;
         }
         this.reset();
         return;
      }

      // Enemy in range - start/continue cycle
      if (!this.inCycle) {
         // Start new cycle
         this.startCycle();
      } else {
         // Continue current cycle
         this.tickCounter++;

         if (this.tickCounter <= this.blinkTicksDuration) {
            // During blink phase
            if (!this.blink) {
               this.blink = true;
            }
         } else if (this.tickCounter <= this.blinkTicksDuration + this.cooldownTicks) {
            // During cooldown phase - release blink
            if (this.blink) {
               BlinkHandler.release();
               this.blink = false;
            }
         } else {
            // Cycle complete - start new cycle
            this.startCycle();
         }
      }
   }

   @SubscribeEvent(priority = EventPriority.LOWEST)
   public void onSendPacket(SendPacketEvent e) {
      if (!Utils.nullCheck()) {
         return;
      }

      // Release on attack if enabled
      if (this.releaseOnAttack.isToggled() && e.getPacket() instanceof C02PacketUseEntity) {
         C02PacketUseEntity packet = (C02PacketUseEntity) e.getPacket();
         if (packet.getAction() == C02PacketUseEntity.Action.ATTACK && this.blink) {
            BlinkHandler.release();
            this.blink = false;
            this.reset();
         }
      }
   }

   private void startCycle() {
      this.inCycle = true;
      this.tickCounter = 0;
      this.blinkTicksDuration = (int) this.blinkDuration.getInput();
      this.cooldownTicks = (int) this.cycleCooldown.getInput();

      // Send block packet at start of cycle
      if (!this.isBlocking && Utils.holdingSword()) {
         PacketUtils.sendPacketNoEvent(new C08PacketPlayerBlockPlacement(mc.thePlayer.inventory.getCurrentItem()));
         this.isBlocking = true;
      }
   }

   private void reset() {
      this.inCycle = false;
      this.tickCounter = 0;
      this.isBlocking = false;
      this.nearestEnemy = null;
   }

   private EntityLivingBase findNearestEnemy() {
      EntityLivingBase closest = null;
      double closestDistance = this.range.getInput();

      for (Entity entity : mc.theWorld.loadedEntityList) {
         if (entity == mc.thePlayer) {
            continue;
         }

         if (!(entity instanceof EntityLivingBase)) {
            continue;
         }

         EntityLivingBase livingEntity = (EntityLivingBase) entity;

         // Check if valid target
         if (!this.isValidTarget(livingEntity)) {
            continue;
         }

         // Check distance
         double distance = mc.thePlayer.getDistanceToEntity(livingEntity);
         if (distance > this.range.getInput()) {
            continue;
         }

         // Check if can be hit
         if (!RotationUtils.isPossibleToHit(livingEntity, this.range.getInput(),
                                            RotationUtils.getRotations(livingEntity))) {
            continue;
         }

         // Find closest
         if (closest == null || distance < closestDistance) {
            closest = livingEntity;
            closestDistance = distance;
         }
      }

      return closest;
   }

   private boolean isValidTarget(EntityLivingBase entity) {
      if (entity.isDead || entity.getHealth() <= 0) {
         return false;
      }

      if (entity instanceof EntityPlayer) {
         EntityPlayer player = (EntityPlayer) entity;

         // Skip teammates
         if (Utils.isTeammate(player)) {
            return false;
         }

         // Skip friends and check bot
         if (ModuleManager.antiBot != null && ModuleManager.antiBot.isEnabled()) {
            if (AntiBot.isBot(entity)) {
               return false;
            }
         }

         return !Utils.isFriended(player);
      }

      // Allow other living entities (mobs)
      return true;
   }

   @Override
   public String getInfo() {
      return BlinkHandler.blinkTicks + "";
   }
}
