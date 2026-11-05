package keystrokesmod.module.impl.player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import keystrokesmod.Raven;
import keystrokesmod.event.PostPlayerInputEvent;
import keystrokesmod.event.PreMotionEvent;
import keystrokesmod.event.PreUpdateEvent;
import keystrokesmod.event.ReceivePacketEvent;
import keystrokesmod.event.SendPacketEvent;
import keystrokesmod.helper.RotationHelper;
import keystrokesmod.mixin.impl.accessor.IAccessorPlayerControllerMP;
import keystrokesmod.module.Module;
import keystrokesmod.module.ModuleManager;
import keystrokesmod.module.impl.client.Settings;
import keystrokesmod.module.impl.movement.LongJump;
import keystrokesmod.module.setting.impl.ButtonSetting;
import keystrokesmod.module.setting.impl.SliderSetting;
import keystrokesmod.utility.BlockUtils;
import keystrokesmod.utility.RenderUtils;
import keystrokesmod.utility.RotationUtils;
import keystrokesmod.utility.ScaffoldBlockCount;
import keystrokesmod.utility.Theme;
import keystrokesmod.utility.Timer;
import keystrokesmod.utility.Utils;
import net.minecraft.block.Block;
import net.minecraft.block.BlockLiquid;
import net.minecraft.block.BlockSlab;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraft.network.play.client.C0APacketAnimation;
import net.minecraft.network.play.client.C0DPacketCloseWindow;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraft.util.Vec3i;
import net.minecraftforge.client.event.MouseEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.event.ForgeEventFactory;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

/**
 * Scaffold ported from OpenB4's {@code xyz.daikirai.module.player.ScaffoldModule}.
 *
 * <p>The tick driven hooks of the source client (onPreUpdate / onPreMotion / onPostPlayerInput /
 * getRotations) are wired to Raven's Forge event bus: state updates and rotation publication run
 * from {@link PreUpdateEvent}, the motion tweaks from {@link PreMotionEvent} and the input
 * overrides from {@link PostPlayerInputEvent}. Server rotations are handed to
 * {@link RotationHelper}, which is the mechanism Raven's mixins already use.
 *
 * <p>Runtime state is static, mirroring the source module; the public {@code isEnabled} mirror
 * is what the rest of Raven reads as "scaffold is currently bridging".
 */
public class Scaffold extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    public enum Rotation {
        None, Strict, Smooth, Simple, Lazy, Offset;

        public float side;
        public float sideJitter;
        public float groundSide;

        public void onDisable() {
            this.groundSide = 0.0f;
            this.sideJitter = 0.0f;
            this.side = 0.0f;
        }
    }

    public enum FastScaffold {
        Disabled(false, false, false),
        Vanilla(true, false, false),
        Constant_A(true, false, false),
        Constant_B(false, false, false),
        Float_A(true, false, false),
        Float_B(true, false, false),
        Jump_A(false, true, false),
        Jump_B(false, true, false),
        Jump_C(true, true, false),
        Jump_D(true, true, false),
        Keep_Y_A(false, false, true),
        Keep_Y_B(false, false, true),
        Side(true, false, false);

        public final boolean sprint;
        public final boolean jump;
        public final boolean keepY;
        public int state;
        public int ticks;
        public double height;

        FastScaffold(boolean sprint, boolean jump, boolean keepY) {
            this.sprint = sprint;
            this.jump = jump;
            this.keepY = keepY;
        }

        public boolean canSprint() {
            if (!this.canFastScaffold()) {
                if (mc.thePlayer.ticksExisted == startTick && jumpState == 2) {
                    return false;
                }
                return Settings.movementFix.isToggled();
            }
            if (legitMotion.isToggled() && (this.jump || this.keepY)) {
                return legitTicks > 0;
            }
            if (!this.sprint) {
                return false;
            }
            return this != Side || this.ticks != -1;
        }

        public void onEnable() {
            enableTick = mc.thePlayer.ticksExisted;
            this.state = 0;
            if (this == Float_A) {
                if (mc.thePlayer.onGround) {
                    this.ticks = 1;
                    delay = 1;
                    if (mc.thePlayer.motionY == -0.0784000015258789) {
                        jump();
                    }
                    keptY = (int) Math.floor(mc.thePlayer.posY) - 1;
                }
            } else if (this == Jump_B || this == Jump_C) {
                this.height = 2.2;
            } else if (this == Jump_D) {
                this.height = 2.5;
            } else if (this == Side) {
                this.ticks = 4;
            }
        }

        public boolean canFastScaffold() {
            if (!active || this == Disabled) {
                return false;
            }
            if (ModuleManager.tower != null && ModuleManager.tower.towering || jumpKeyDown()) {
                return false;
            }
            if (this != Float_A) {
                if (!isMoving() || mc.thePlayer.isSneaking()) {
                    return false;
                }
                if (this.sprint && !this.jump && !direction().forward) {
                    return false;
                }
            }
            if (this == Side) {
                return !isDiagonal();
            }
            return true;
        }

        public void onDisable(boolean disabling) {
            if (this.jump || this.keepY) {
                keptY = -1;
            }
            if (mc.thePlayer != null) {
                if (this == Float_A) {
                    if (disablingModule) {
                        if (mc.thePlayer.onGround && isAirBelow()) {
                            jump();
                        }
                        if (rotation != Rotation.Offset) {
                            multiplyMotion(0.58, false);
                        }
                    }
                } else if (this == Float_B && disablingModule) {
                    multiplyMotion(0.85, false);
                }
            }
            this.ticks = 0;
        }
    }

    public enum MultiPlace {
        Disabled, _1_extra, _2_extra
    }

    public enum FakeRotation {
        None, Smooth, Forward, Spin
    }

    public enum Precision {
        Low(40.0f),
        Medium(20.0f),
        High(5.0f),
        Very_high(1.5f);

        public final float offsetYaw;

        Precision(float offsetYaw) {
            this.offsetYaw = offsetYaw;
        }

        public float getOffsetYaw() {
            return this.offsetYaw;
        }

        public float getOffsetPitch(float pitch) {
            if (pitch > 75.0f) {
                return 1.0f;
            }
            if (pitch > 60.0f) {
                return 2.0f;
            }
            return 5.0f;
        }
    }

    private enum MoveDirection {
        NONE(false, false, false),
        FORWARD(true, false, false),
        FORWARD_LEFT(true, true, false),
        FORWARD_RIGHT(true, true, false),
        BACK(false, false, true),
        BACK_LEFT(false, true, true),
        BACK_RIGHT(false, true, true),
        LEFT(false, false, false),
        RIGHT(false, false, false);

        public final boolean forward;
        public final boolean diagonal;
        public final boolean backward;

        MoveDirection(boolean forward, boolean diagonal, boolean backward) {
            this.forward = forward;
            this.diagonal = diagonal;
            this.backward = backward;
        }
    }

    private static final String[] ROTATION_MODES = {"None", "Strict", "Smooth", "Simple", "Lazy", "Offset"};
    private static final String[] FAKE_ROTATION_MODES = {"None", "Smooth", "Forward", "Spin"};
    private static final String[] FAST_SCAFFOLD_MODES = {
            "Disabled", "Vanilla", "Constant A", "Constant B", "Float A", "Float B",
            "Jump A", "Jump B", "Jump C", "Jump D", "Keep-Y A", "Keep-Y B", "Side"
    };
    private static final String[] MULTI_PLACE_MODES = {"Disabled", "1 extra", "2 extra"};
    private static final String[] PRECISION_MODES = {"Low", "Medium", "High", "Very high"};

    public static SliderSetting motion, rotationSetting, fakeRotationSetting, fastScaffoldSetting, fastScaffoldRmbSetting,
            fastScaffoldMotion, multiPlaceSetting, precisionSetting, sneakEvery, speedBoost, highlightTheme;
    public static ButtonSetting autoSwap, cancelKnockback, highlightBlocks, jumpFacingForward, legitMotion, safeWalk,
            showBlockCount, silentSwing, slowOnEnable, slowSpeedPotion, tower;

    public static Rotation rotation = Rotation.Strict;
    public static FakeRotation fakeRotation = FakeRotation.None;
    public static FastScaffold fastScaffold = FastScaffold.Disabled;
    private static FastScaffold lastFastScaffold = FastScaffold.Disabled;
    public static MultiPlace multiPlace = MultiPlace.Disabled;
    public static Precision precision = Precision.High;

    public static boolean active;
    public static boolean disablingModule;
    private static boolean fastEnabled;
    private static boolean rightHeld;
    public static int keptY = -1;
    private static int searchMode;
    public static int legitTicks;
    public static int delay;
    public static int enableTick;
    public static int jumpState;
    public static int startTick;
    private static int jumpTick;
    private static int airBelowTicks;
    private static int placedCount;
    private static boolean sneakQueued;
    private static boolean smoothSnap;
    private static BlockPos lastPlaced;
    private static float aimYaw;
    private static float aimPitch = 100.0f;
    private static float smoothLimit;
    private static float legitYaw;
    private static float restPitch;
    private static double yawOffset;
    private static long placedAt;
    public static long startedAt;
    public static float startPitch;
    private static long stoppedAt;
    private static long smoothAt;
    private static final HashMap<BlockPos, Long> placed = new HashMap<BlockPos, Long>();
    private static final EnumFacing[] SIDES = {EnumFacing.EAST, EnumFacing.SOUTH, EnumFacing.NORTH, EnumFacing.WEST};
    private static final BlockPos.MutableBlockPos SCRATCH = new BlockPos.MutableBlockPos();

    private static int groundTicks;
    private static int themeIndex;
    private static float playerYawAtSend;
    private static long lastSetback;
    private static boolean sentCloseWindow;
    private static int rotateForwardTicks;
    private static float towerAimYaw;
    private static boolean hasTowerAimYaw;
    private static float fakeSmoothYaw;
    private static ScaffoldBlockCount scaffoldBlockCount;

    // ------------------------------------------------------------------
    // Cross-module compatibility state (read by Raven's other modules).
    // ------------------------------------------------------------------

    /** Mirrors {@link #active}; the rest of the client uses this field instead of isEnabled(). */
    public static boolean isEnabled;
    public static boolean moduleEnabled;
    public static boolean fastScaffoldKeepY;
    public static boolean hasSwapped;
    public static boolean hasPlaced;
    public static boolean placedVP;
    public static boolean lowhop;
    public static boolean blink;
    public static int offsetDelay;
    public static final AtomicInteger lastSlot = new AtomicInteger(-1);
    public static final Map<BlockPos, Timer> highlight = new HashMap<BlockPos, Timer>();
    public static boolean canBlockFade;
    public static int jumpDelayVal;
    public static int airTickVal;

    public Scaffold() {
        super("Scaffold", Module.category.player);
        registerSetting(motion = new SliderSetting("Motion", "x", 1.0, 0.5, 1.2, 0.01));
        registerSetting(rotationSetting = new SliderSetting("Rotation", 1, ROTATION_MODES));
        registerSetting(fakeRotationSetting = new SliderSetting("Rotation (fake)", 0, FAKE_ROTATION_MODES));
        registerSetting(fastScaffoldSetting = new SliderSetting("Fast scaffold", 0, FAST_SCAFFOLD_MODES));
        registerSetting(fastScaffoldRmbSetting = new SliderSetting("Fast scaffold RMB", 0, FAST_SCAFFOLD_MODES));
        registerSetting(fastScaffoldMotion = new SliderSetting("Fast scaffold motion", "x", 1.0, 0.5, 1.2, 0.01));
        registerSetting(multiPlaceSetting = new SliderSetting("Multi-place", 0, MULTI_PLACE_MODES));
        registerSetting(precisionSetting = new SliderSetting("Precision", 2, PRECISION_MODES));
        registerSetting(sneakEvery = new SliderSetting("Sneak every", " blocks", "§cDisabled", 0.0, 0.0, 20.0, 1.0));
        registerSetting(speedBoost = new SliderSetting("Speed boost", 1.3, 0.0, 2.0, 0.05));
        registerSetting(highlightTheme = new SliderSetting("Highlight theme", 0, Theme.themes));
        registerSetting(autoSwap = new ButtonSetting("Auto swap", false));
        registerSetting(cancelKnockback = new ButtonSetting("Cancel knockback", false));
        registerSetting(highlightBlocks = new ButtonSetting("Highlight blocks", true));
        registerSetting(jumpFacingForward = new ButtonSetting("Jump facing forward", true));
        registerSetting(legitMotion = new ButtonSetting("Legit motion", true));
        registerSetting(safeWalk = new ButtonSetting("SafeWalk", false));
        registerSetting(showBlockCount = new ButtonSetting("Show block count", true));
        registerSetting(silentSwing = new ButtonSetting("Silent swing", false));
        registerSetting(slowOnEnable = new ButtonSetting("Slow on enable", false));
        registerSetting(slowSpeedPotion = new ButtonSetting("Slow speed potion", false));
        registerSetting(tower = new ButtonSetting("Tower", false));
        parseSettings();
    }

    private static void parseSettings() {
        rotation = Rotation.values()[modeIndex(rotationSetting, Rotation.values().length)];
        fakeRotation = FakeRotation.values()[modeIndex(fakeRotationSetting, FakeRotation.values().length)];
        multiPlace = MultiPlace.values()[modeIndex(multiPlaceSetting, MultiPlace.values().length)];
        precision = Precision.values()[modeIndex(precisionSetting, Precision.values().length)];
        themeIndex = modeIndex(highlightTheme, Theme.values().length);
    }

    private static int modeIndex(SliderSetting setting, int length) {
        int index = (int) Math.round(setting.getInput());
        return Math.max(0, Math.min(length - 1, index));
    }

    @Override
    public void onEnable() {
        moduleEnabled = true;
        disablingModule = false;
        resetRuntime();
        scaffoldBlockCount = new ScaffoldBlockCount(mc);
        FMLCommonHandler.instance().bus().register(scaffoldBlockCount);
        if (Utils.nullCheck()) {
            restPitch = randomFloat(84.5f, 87.5f);
            yawOffset = randomOffset();
            // Raven's Safewalk has no scaffold mode, so the source client's
            // SafeWalkModule.scaffoldActive branch is always inactive here.
            if (!mc.thePlayer.onGround) {
                delay = 3;
            } else if (potionLevel(Potion.jump) <= 3 && nearEdge(0.2) && countBlocks() > 0) {
                jumpState = 2;
                jumpTick = mc.thePlayer.ticksExisted;
                delay = 4;
            }
        }
    }

    @Override
    public void onDisable() {
        disablingModule = true;
        stop();
        legitTicks = 0;
        delay = 0;
        airBelowTicks = 0;
        placedCount = 0;
        placedAt = 0L;
        if (Utils.nullCheck()) {
            double d = speed();
            if (d > 0.34) {
                multiplyMotion((0.29 + 0.02 * Math.random()) / d, false);
            }
        }
        disablingModule = false;
        moduleEnabled = false;
        isEnabled = false;
        if (scaffoldBlockCount != null) {
            scaffoldBlockCount.beginFade();
            scaffoldBlockCount = null;
        }
    }

    private static void resetRuntime() {
        active = false;
        isEnabled = false;
        keptY = -1;
        delay = 0;
        legitTicks = 0;
        enableTick = 0;
        startTick = 0;
        jumpState = 0;
        jumpTick = 0;
        airBelowTicks = 0;
        placedCount = 0;
        sneakQueued = false;
        smoothSnap = false;
        fastEnabled = false;
        rightHeld = false;
        lastFastScaffold = FastScaffold.Disabled;
        lastPlaced = null;
        aimYaw = 0.0f;
        aimPitch = 100.0f;
        smoothLimit = 0.0f;
        legitYaw = 0.0f;
        restPitch = 0.0f;
        yawOffset = 0.0;
        placedAt = 0L;
        startedAt = 0L;
        startPitch = 0.0f;
        stoppedAt = 0L;
        smoothAt = 0L;
        groundTicks = 0;
        playerYawAtSend = 0.0f;
        rotateForwardTicks = 0;
        hasTowerAimYaw = false;
        towerAimYaw = 0.0f;
        fakeSmoothYaw = 0.0f;
        hasSwapped = false;
        hasPlaced = false;
        placedVP = false;
        lowhop = false;
        blink = false;
        offsetDelay = 0;
        fastScaffoldKeepY = false;
        lastSlot.set(-1);
        placed.clear();
        highlight.clear();
        canBlockFade = false;
        sentCloseWindow = false;
        rotation.onDisable();
        for (FastScaffold mode : FastScaffold.values()) {
            mode.state = 0;
            mode.ticks = 0;
            mode.height = 0.0;
        }
        parseSettings();
    }

    @Override
    public void guiUpdate() {
        parseSettings();
    }

    @Override
    public String getInfo() {
        FastScaffold mode = FastScaffold.values()[modeIndex(rightHeld ? fastScaffoldRmbSetting : fastScaffoldSetting,
                FastScaffold.values().length)];
        if (mode == FastScaffold.Disabled) {
            return rotationSetting.getSelectedOption();
        }
        if (mode.jump) {
            return "Jump";
        }
        if (mode.keepY) {
            return "Keep-Y";
        }
        if (mode == FastScaffold.Constant_A || mode == FastScaffold.Constant_B) {
            return "Constant";
        }
        if (mode == FastScaffold.Float_A || mode == FastScaffold.Float_B) {
            return "Float";
        }
        return mode.name().replace("___", "-").replace("__", ".").replace("_", " ").trim();
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onPreUpdate(PreUpdateEvent event) {
        if (!Utils.nullCheck() || !super.isEnabled()) {
            return;
        }
        groundTicks = !mc.thePlayer.onGround ? 0 : ++groundTicks;
        parseSettings();
        update();
        if (rotateForwardTicks > 0) {
            --rotateForwardTicks;
        }

        float lastYaw = RotationUtils.serverRotations[0];
        float lastPitch = RotationUtils.serverRotations[1];
        float[] rotations = rotations(lastYaw, lastPitch);
        if (rotations != null) {
            if (ModuleManager.tower != null && ModuleManager.tower.isVerticalTowering()) {
                if (aimPitch != 100.0f) {
                    towerAimYaw = aimYaw;
                    hasTowerAimYaw = true;
                }
                if (hasTowerAimYaw) {
                    rotations[0] = towerAimYaw;
                }
                if (ModuleManager.tower.yaw != 0.0f) {
                    rotations[0] = ModuleManager.tower.yaw;
                }
                if (ModuleManager.tower.pitch != 0.0f) {
                    rotations[1] = ModuleManager.tower.pitch;
                }
            } else {
                hasTowerAimYaw = false;
            }
            RotationHelper.get().setRotations(rotations[0], rotations[1]);
        }

        long now = System.currentTimeMillis();
        if (!placed.isEmpty()) {
            Iterator<Map.Entry<BlockPos, Long>> iterator = placed.entrySet().iterator();
            while (iterator.hasNext()) {
                if (now - Math.abs(iterator.next().getValue()) >= 1000L) {
                    iterator.remove();
                }
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onPreMotion(PreMotionEvent event) {
        if (!Utils.nullCheck() || !super.isEnabled()) {
            return;
        }
        playerYawAtSend = mc.thePlayer.rotationYaw;
        preMotion(event);
        applyFakeRotation(event.getPitch());
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onPostPlayerInput(PostPlayerInputEvent event) {
        if (!Utils.nullCheck() || !super.isEnabled()) {
            return;
        }
        postPlayerInput();
    }

    /**
     * The module owns the mouse while it is bridging. Only presses are swallowed: letting the
     * matching release through keeps the vanilla key states in sync.
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onMouse(MouseEvent event) {
        if (event.button > -1 && event.buttonstate && active) {
            event.setCanceled(true);
        }
    }

    /**
     * A press that happened before the module became active still held the vanilla use key, so
     * release it at the end of the tick while a block is held.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onPostUpdateUseKey(keystrokesmod.event.PostUpdateEvent event) {
        if (active && Utils.nullCheck() && isScaffoldBlock(mc.thePlayer.getHeldItem())) {
            KeyBinding.setKeyBindState(mc.gameSettings.keyBindUseItem.getKeyCode(), false);
            KeyBinding.setKeyBindState(mc.gameSettings.keyBindAttack.getKeyCode(), false);
        }
    }

    /**
     * Vanilla's right click must not place a block next to the scaffold one. The module places
     * through {@code playerController.onPlayerRightClick}, which does not fire this event.
     */
    @SubscribeEvent
    public void onPlayerInteract(PlayerInteractEvent event) {
        PlayerInteractEvent.Action action = event.action;
        if (active && (action == PlayerInteractEvent.Action.RIGHT_CLICK_BLOCK || action == PlayerInteractEvent.Action.RIGHT_CLICK_AIR)
                && isScaffoldBlock(mc.thePlayer.getHeldItem())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onSendPacket(SendPacketEvent event) {
        if (event.isCanceled()) {
            return;
        }
        if (event.getPacket() instanceof C03PacketPlayer) {
            sentCloseWindow = false;
        } else if (event.getPacket() instanceof C0DPacketCloseWindow) {
            sentCloseWindow = true;
        }
    }

    @SubscribeEvent
    public void onReceivePacket(ReceivePacketEvent event) {
        if (event.getPacket() instanceof S08PacketPlayerPosLook) {
            lastSetback = System.currentTimeMillis();
        }
    }

    @SubscribeEvent
    public void onRenderWorld(RenderWorldLastEvent event) {
        if (!Utils.nullCheck() || placed.isEmpty() || !highlightBlocks.isToggled()) {
            return;
        }
        long now = System.currentTimeMillis();
        int baseColor = Theme.getGradient(themeIndex, 0.0);
        Iterator<Map.Entry<BlockPos, Long>> iterator = placed.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<BlockPos, Long> entry = iterator.next();
            float fade = (float) (now - Math.abs(entry.getValue())) / 500.0f;
            if (fade >= 1.0f) {
                iterator.remove();
                continue;
            }
            int alpha = 255 - (int) Math.min(fade * 255.0f, 255.0f);
            RenderUtils.renderBlock(entry.getKey(), Utils.mergeAlpha(baseColor, alpha), true, false);
        }
    }

    private static void update() {
        if (sentCloseWindow || screenOpen() || isAdventure()) {
            stop();
            return;
        }
        if (autoSwap.isToggled()) {
            autoSwapTick();
        }
        ItemStack itemStack = mc.thePlayer.getHeldItem();
        if (!isScaffoldBlock(itemStack)) {
            if (!autoSwap.isToggled() || countBlocks() == 0) {
                stop();
            }
            return;
        }
        long now = System.currentTimeMillis();
        if (!active) {
            startTick = mc.thePlayer.ticksExisted;
            startedAt = now;
            startPitch = mc.thePlayer.rotationPitch;
            if (restPitch == 0.0f) {
                restPitch = randomFloat(84.5f, 87.5f);
            }
            if (yawOffset == 0.0) {
                yawOffset = randomOffset();
            }
            KeyBinding.setKeyBindState(mc.gameSettings.keyBindAttack.getKeyCode(), false);
            KeyBinding.setKeyBindState(mc.gameSettings.keyBindUseItem.getKeyCode(), false);
            if (slowOnEnable.isToggled()) {
                multiplyMotion(0.2, true);
            }
        }
        active = true;
        isEnabled = true;
        moduleEnabled = true;
        hasSwapped = holdingBlocks();
        fastScaffoldTick();
        fastScaffoldKeepY = fastScaffold.canFastScaffold() && (fastScaffold.jump || fastScaffold.keepY);

        if (LongJump.function) {
            delay = 0;
        } else if (delay > 0) {
            --delay;
            return;
        }
        if (!noUseOrDig()) {
            return;
        }
        BlockPos below = below(1.0);
        airBelowTicks = isReplaceable(below) ? ++airBelowTicks : 0;
        BlockPos pos = place((BlockPos) null, itemStack);
        if (pos != null) {
            ++placedCount;
            hasPlaced = true;
            if (sneakEvery.getInput() > 0.0 && placedCount % (int) sneakEvery.getInput() == 0) {
                sneakQueued = true;
            }
            placed.put(pos, placedAt);
            if (itemStack.stackSize > 0) {
                afterPlace(pos, itemStack);
            }
        }
    }

    private static BlockPos place(BlockPos last, ItemStack itemStack) {
        BlockPos target = search(last);
        if (target == null) {
            return null;
        }
        EnumFacing facing = faceFor(target, null);
        if (facing == null) {
            return null;
        }
        BlockPos against = target.offset(facing.getOpposite());
        if (airBelowTicks > 0) {
            float yaw = aimYaw;
            float pitch = aimPitch;
            MovingObjectPosition hit = aim(against, facing, -170.0f, RotationUtils.serverRotations[0]);
            if (hit == null) {
                return last == null ? null : place((BlockPos) null, itemStack);
            }
            switch (rotation) {
                case Simple: {
                    float movementAngle = movementAngle();
                    if (!(Math.abs(movementAngle - aimYaw) < 100.0f)) {
                        break;
                    }
                    float difference = aimYaw - movementAngle;
                    difference = (difference + 540.0f) % 360.0f - 180.0f;
                    float offset = 0.0f;
                    if (Math.abs(difference) >= 10.0f) {
                        offset = Math.max(-10.0f, Math.min(difference, 10.0f));
                    }
                    aimYaw = movementAngle + offset;
                    if (!mc.thePlayer.onGround) {
                        break;
                    }
                    aimPitch = 82.5f;
                    break;
                }
                case Lazy: {
                    if (pitch == 100.0f || rayTraceBlock(against, null, mc.playerController.getBlockReachDistance(), yaw, pitch) == null) {
                        break;
                    }
                    aimYaw = yaw;
                    aimPitch = pitch;
                    break;
                }
                default:
                    break;
            }
            Vec3 hitVec = hit.hitVec;
            if (mc.playerController.onPlayerRightClick(mc.thePlayer, mc.theWorld, itemStack, against, facing, hitVec)) {
                swing(silentSwing.isToggled(), true);
                if (searchMode == 1 || searchMode == 2 && fastScaffold.keepY) {
                    fastScaffold.ticks = mc.thePlayer.ticksExisted;
                }
                lastPlaced = target;
                placedAt = System.currentTimeMillis();
                placed.put(target, placedAt);
                airBelowTicks = 0;
                return target;
            }
        } else if (isMoving() && Math.random() > 0.8) {
            syncCurrentPlayItem();
            mc.thePlayer.sendQueue.addToSendQueue(new C08PacketPlayerBlockPlacement(itemStack));
        }
        return null;
    }

    private static BlockPos search(BlockPos last) {
        ArrayList<Vec3> list = new ArrayList<Vec3>();
        searchMode = 0;
        if (keptY != -1 && fastScaffold.canFastScaffold()) {
            int decimals = posYDecimals();
            if (fastScaffold == FastScaffold.Jump_A) {
                if (decimals == 1661 || decimals == 2492 && mc.thePlayer.motionY > 0.0) {
                    searchMode = 1;
                } else {
                    searchMode = 2;
                }
            } else if (fastScaffold == FastScaffold.Jump_B || fastScaffold == FastScaffold.Jump_C || fastScaffold == FastScaffold.Jump_D) {
                if (mc.thePlayer.ticksExisted - fastScaffold.ticks > 4 && mc.thePlayer.posY - (double) keptY < fastScaffold.height
                        && mc.thePlayer.motionY < -0.1) {
                    searchMode = 1;
                    if (fastScaffold.ticks == 0) {
                        multiplyMotion(0.4, false);
                    }
                } else {
                    searchMode = 2;
                }
            } else if (fastScaffold.keepY) {
                searchMode = 2;
            } else if (fastScaffold == FastScaffold.Float_A) {
                if (mc.thePlayer.onGround && mc.thePlayer.ticksExisted - enableTick > 0) {
                    keptY = -1;
                } else {
                    searchMode = 2;
                }
            }
        }
        return candidates(list, searchMode, keptY) ? firstPlaceable(list, last) : null;
    }

    private static boolean candidates(List<Vec3> list, int mode, int y) {
        for (int i = -5; i <= 5; ++i) {
            for (int j = -5; j <= 5; ++j) {
                for (int k = -3; k <= -1; ++k) {
                    int blockY = MathHelper.floor_double(mc.thePlayer.posY) + k;
                    if (mode > 0 && blockY != y) {
                        continue;
                    }
                    double x = mc.thePlayer.posX + (double) i;
                    double z = mc.thePlayer.posZ + (double) j;
                    SCRATCH.set(MathHelper.floor_double(x), blockY, MathHelper.floor_double(z));
                    if (isReplaceable(SCRATCH)) {
                        continue;
                    }
                    for (int side = -1; side <= 1; side += 2) {
                        switch (mode) {
                            case 0:
                                list.add(new Vec3(x + (double) side, (double) blockY, z));
                                list.add(new Vec3(x, (double) blockY, z + (double) side));
                                // fall through
                            case 1:
                                list.add(new Vec3(x, (double) (blockY + side), z));
                                continue;
                            case 2:
                                list.add(new Vec3(x + (double) side, (double) blockY, z));
                                list.add(new Vec3(x, (double) blockY, z + (double) side));
                                break;
                            default:
                                break;
                        }
                    }
                }
            }
        }
        if (list.isEmpty()) {
            return false;
        }
        HashSet<Long> seen = new HashSet<Long>(list.size() * 2);
        ArrayList<Vec3> unique = new ArrayList<Vec3>(list.size());
        for (Vec3 vec3 : list) {
            long key = Double.doubleToLongBits(vec3.xCoord) ^ Double.doubleToLongBits(vec3.yCoord) * 31L ^ Double.doubleToLongBits(vec3.zCoord) * 31L * 31L;
            if (!seen.add(key)) {
                continue;
            }
            unique.add(vec3);
        }
        list.clear();
        list.addAll(unique);
        final double x = mc.thePlayer.posX;
        double y2 = mc.thePlayer.posY - 1.0;
        final double z = mc.thePlayer.posZ;
        if (!mc.thePlayer.onGround && !jumpKeyDown() && !fastScaffold.canFastScaffold()) {
            y2 -= 1.0;
        }
        final double fy = y2;
        Comparator<Vec3> comparator = Comparator.comparingDouble(point -> distanceSq(x, fy, z, point));
        if (mc.thePlayer.ticksExisted - startTick <= 3) {
            double yaw = mc.thePlayer.rotationYaw * ((float) Math.PI / 180);
            final double dirX = -Math.sin(yaw);
            final double dirZ = Math.cos(yaw);
            comparator = comparator.thenComparingDouble(point -> behind(x, z, dirX, dirZ, point));
        }
        list.sort(comparator);
        return true;
    }

    private static BlockPos firstPlaceable(List<Vec3> list, BlockPos last) {
        for (Vec3 vec3 : list) {
            SCRATCH.set(MathHelper.floor_double(vec3.xCoord), MathHelper.floor_double(vec3.yCoord), MathHelper.floor_double(vec3.zCoord));
            if (!isReplaceable(SCRATCH) || faceFor(SCRATCH, last) == null) {
                continue;
            }
            return new BlockPos((Vec3i) SCRATCH);
        }
        return null;
    }

    private static float[] rotations(float lastYaw, float lastPitch) {
        if (!active || rotation == Rotation.None) {
            return null;
        }
        long sincePlaced = sincePlace();
        float yaw;
        float pitch;
        float offset;
        if (rotation == Rotation.Offset) {
            float inputAngle = (float) inputAngleSigned();
            float relativeYaw = mc.thePlayer.rotationYaw + inputAngle;
            float normalized = (relativeYaw % 360.0f + 360.0f) % 360.0f;
            float quadrant = normalized % 90.0f;
            if (!(quadrant >= 80.0f) && !(quadrant < 10.0f)) {
                offset = quadrant >= 10.0f && quadrant < 45.0f ? 45.0f : -45.0f;
            } else {
                offset = -45.0f;
            }
            if (!mc.thePlayer.onGround && rotation.groundSide != 0.0f) {
                offset = rotation.groundSide;
            } else {
                rotation.groundSide = offset;
            }
            if (offset != rotation.side) {
                rotation.side = offset;
                rotation.sideJitter = (float) (Math.signum(offset) * Utils.randomizeDouble(0.02, 0.08));
            }
            aimYaw = movementAngle() + (offset + rotation.sideJitter);
        }
        boolean jumpRotation = false;
        if (legitTicks <= 0) {
            if (fastScaffold == FastScaffold.Float_A && fastScaffold.ticks == 1 && mc.thePlayer.ticksExisted == enableTick) {
                jumpRotation = true;
            }
        } else {
            --legitTicks;
            jumpRotation = true;
        }
        if (rotateForwardTicks > 0 && legitTicks <= 0) {
            jumpRotation = true;
        }
        if (jumpRotation) {
            yaw = -180.0f;
            pitch = aimPitch == 100.0f ? mc.thePlayer.rotationPitch : aimPitch;
            if (legitMotion.isToggled()) {
                if (legitTicks == 4) {
                    if ((double) (mc.thePlayer.rotationYaw - playerYawAtSend) < -0.7) {
                        yaw = -yaw;
                    }
                    legitYaw = yaw;
                    --delay;
                    jumpRotation = false;
                } else if (legitTicks == 3) {
                    if (legitYaw > 0.0f) {
                        yaw = -yaw;
                    }
                    if (rotation == Rotation.Smooth) {
                        legitTicks = Math.min(legitTicks, 1);
                        if (delay > legitTicks) {
                            delay = legitTicks;
                        }
                        smoothSnap = true;
                    }
                    pitch = randomFloat(20.0f, 25.0f);
                } else if (legitTicks == 2) {
                    legitYaw += randomFloat(105.0f, 120.0f) * -Math.signum(legitYaw);
                    yaw = legitYaw;
                    pitch = randomFloat(40.0f, 45.0f);
                } else if (legitTicks == 1) {
                    legitYaw += randomFloat(33.0f, 35.0f) * -Math.signum(legitYaw);
                    yaw = legitYaw;
                    if (aimPitch == 100.0f || rotation == Rotation.Simple) {
                        pitch = randomFloat(50.0f, 55.0f);
                    }
                } else if (legitTicks == 0) {
                    legitYaw += randomFloat(29.0f, 33.0f) * -Math.signum(legitYaw);
                    yaw = legitYaw;
                    if (aimPitch == 100.0f || rotation == Rotation.Simple) {
                        pitch = randomFloat(65.0f, 70.0f);
                    }
                }
            }
            if (rotation == Rotation.Offset && rotation.side > 0.0f) {
                yaw = -yaw;
            }
            if (jumpRotation) {
                return fixSensitivity(movementAngle() + yaw + randomOffset(), pitch, lastYaw, lastPitch);
            }
        }
        float desiredYaw;
        float desiredPitch;
        if (rotation == Rotation.Smooth && lastPlaced != null && groundTicks <= 3) {
            desiredYaw = aimYaw;
            desiredPitch = aimPitch;
            float limit = smoothLimit;
            if (smoothSnap) {
                smoothSnap = false;
                limit = 81.0f;
                limit += randomFloat(-2.0f, 2.0f);
            }
            float difference = MathHelper.wrapAngleTo180_float(desiredYaw - lastYaw);
            if (Math.abs(difference) > limit) {
                desiredYaw = lastYaw + (!(difference > 0.0f) ? -limit : limit);
            }
            return fixSensitivity(desiredYaw, desiredPitch, lastYaw, lastPitch);
        }
        if (aimPitch != 100.0f && sincePlaced < holdTime()) {
            yaw = aimYaw;
            pitch = aimPitch;
            if (sincePlaced > 5L && isMoving() && Math.random() > 0.3) {
                yaw = (float) ((double) yaw + Utils.randomizeDouble(0.05, 0.5) * (double) (!(Math.random() < 0.5) ? 1 : -1));
                pitch += randomOffset();
            }
            float[] fixed = fixSensitivity(yaw, pitch, lastYaw, lastPitch);
            yaw = fixed[0];
            pitch = fixed[1];
        } else {
            if (aimPitch != 100.0f) {
                aimPitch = 100.0f;
            }
            int ticks = mc.thePlayer.ticksExisted - startTick;
            desiredYaw = movementAngle();
            desiredPitch = rotation != Rotation.Simple ? restPitch : 82.5f;
            desiredYaw += (float) yawOffset;
            desiredPitch += randomOffset();
            if (ticks <= 2 && !mc.thePlayer.onGround && mc.thePlayer.ticksExisted - jumpTick > 3) {
                BlockPos target = search(null);
                if (target != null) {
                    EnumFacing facing = faceFor(target, null);
                    if (facing != null && facing != EnumFacing.UP) {
                        BlockPos against = target.offset(facing.getOpposite());
                        float[] found = searchBlockRotation(against, facing, -170.0f, precision, lastYaw);
                        if (found != null) {
                            desiredYaw = found[0];
                            desiredPitch = found[1];
                        }
                    }
                }
            } else if (rotation == Rotation.Offset) {
                desiredYaw = aimYaw;
            }
            yaw = desiredYaw;
            pitch = desiredPitch;
            if (ticks <= 2) {
                float fraction = ticks != 0 ? (ticks != 2 ? 0.8f : 0.55f) : 0.3f;
                fraction = (float) ((double) fraction + Utils.randomizeDouble(-0.02, 0.02));
                yaw = lastYaw + fraction * (desiredYaw - lastYaw);
                pitch = lastPitch + fraction * (desiredPitch - lastPitch);
                if (Math.abs(yaw - desiredYaw) < 5.0f) {
                    delay = 0;
                }
            }
            float[] fixed = fixSensitivity(yaw, pitch, lastYaw, lastPitch);
            yaw = fixed[0];
            if (yaw != lastYaw) {
                pitch = fixed[1];
                yawOffset = randomOffset();
            } else {
                pitch = lastPitch;
            }
        }
        return new float[]{yaw, pitch};
    }

    private static void preMotion(PreMotionEvent event) {
        boolean floatWasSet = FastScaffold.Float_A.state == 1 || FastScaffold.Float_B.state == 1;
        FastScaffold.Float_A.state = 0;
        FastScaffold.Float_B.state = 0;
        if (!fastScaffold.canFastScaffold()) {
            return;
        }
        if (fastScaffold == FastScaffold.Float_A) {
            if (event.getPosY() % 1.0 == 0.0 && mc.thePlayer.motionY == -0.0784000015258789) {
                if (Utils.isHypixel()) {
                    event.setPosY(event.getPosY() + 1.0E-13);
                }
                fastScaffold.state = 1;
                if (isMoving() && !mc.thePlayer.isSneaking() && !sinceSetback(3000L)) {
                    double d = fastScaffold.sprint
                            ? 0.2 + perSpeedLevel(isDiagonal() ? 0.035 : 0.043)
                            : 0.23 + perSpeedLevel(isDiagonal() ? 0.042 : 0.05);
                    if (speedLevel() == 0 && isDiagonal()) {
                        d *= 0.87;
                    }
                    strafe(d);
                    if (!fastScaffold.sprint) {
                        mc.thePlayer.setSprinting(true);
                        mc.thePlayer.spawnRunningParticles();
                    }
                }
            }
        } else if (fastScaffold == FastScaffold.Float_B) {
            if (event.getPosY() % 1.0 == 0.0 && mc.thePlayer.motionY == -0.0784000015258789 && isMoving()
                    && !mc.thePlayer.isSneaking() && !sinceSetback(3000L)) {
                double d;
                if (!floatWasSet && (isDiagonal() || !isAirBelow())) {
                    if (Utils.isHypixel()) {
                        event.setPosY(event.getPosY() + 0.001 + Double.parseDouble("0.0000" + idOffset()));
                        event.setOnGround(false);
                    }
                    fastScaffold.state = 1;
                    d = 0.153;
                } else {
                    d = 0.245 + perSpeedLevel(0.025);
                }
                strafe(d);
            }
        } else if ((fastScaffold.jump || fastScaffold.keepY) && mc.thePlayer.onGround
                && (jumpFacingForward.isToggled() || floatWasSet)) {
            if (fastScaffold.state == 1) {
                fastScaffold.state = 2;
                double d = fastScaffold.sprint && direction().forward ? 0.25 : (fastScaffold.keepY ? 0.54 : 0.4);
                jumpWith(d);
            }
            delay = legitTicks;
        }
    }

    private static void postPlayerInput() {
        if (sneakQueued && sincePlace() > 90L) {
            sneakQueued = false;
            setSneak(true);
        }
        if (jumpState == 1) {
            if (jumpKeyDown()) {
                setJump(false);
                jumpState = 2;
            } else {
                jumpState = 0;
            }
        } else if (jumpState == 2) {
            setJump(true);
            jumpState = 0;
        }
        if (fastScaffold == FastScaffold.Float_A || fastScaffold == FastScaffold.Float_B) {
            if (!fastScaffold.canFastScaffold() && fastScaffold.state == 1) {
                fastScaffold.state = 0;
            }
        } else if ((fastScaffold.jump || fastScaffold.keepY) && legitMotion.isToggled()
                && fastScaffold.canFastScaffold() && mc.thePlayer.onGround && fastScaffold.state == 0) {
            setJump(true);
        }
    }

    private static void fastScaffoldTick() {
        rightHeld = Mouse.isButtonDown(1) && mc.currentScreen == null;
        fastScaffold = FastScaffold.values()[modeIndex(rightHeld ? fastScaffoldRmbSetting : fastScaffoldSetting, FastScaffold.values().length)];
        boolean moving = isMoving();
        boolean groundMoving = mc.thePlayer.onGround && moving;
        boolean can = fastScaffold.canFastScaffold();
        boolean sprintForward = fastScaffold.sprint && direction().forward;
        boolean applied = false;
        if (fastScaffold == lastFastScaffold) {
            if (!can) {
                if (fastEnabled) {
                    fastEnabled = false;
                    fastScaffold.onDisable(false);
                }
            } else if (!fastEnabled) {
                fastEnabled = true;
                fastScaffold.onEnable();
            }
        } else {
            if (fastEnabled && lastFastScaffold != null) {
                lastFastScaffold.onDisable(false);
            }
            if (can) {
                fastScaffold.onEnable();
            }
            fastEnabled = can;
            lastFastScaffold = fastScaffold;
        }
        double d;
        if (!can) {
            if (ModuleManager.tower != null && !ModuleManager.tower.towering && legitTicks > 0 && mc.thePlayer.onGround) {
                delay = 0;
                legitTicks = 0;
            }
        } else {
            boolean newY;
            double speed;
            switch (fastScaffold) {
                case Constant_A:
                    if (groundMoving) {
                        d = 0.153 + perSpeedLevel(0.034);
                        if (speedLevel() == 0 && isDiagonal()) {
                            d *= 0.87;
                        }
                        strafe(d);
                    }
                    break;
                case Constant_B:
                    if (groundMoving) {
                        strafe(0.181);
                    }
                    break;
                case Jump_A:
                case Jump_B:
                case Jump_C:
                case Jump_D:
                    if (!groundMoving) {
                        if (moving && jumpFacingForward.isToggled() && mc.thePlayer.motionY < 0.0) {
                            d = Math.floor(mc.thePlayer.posY);
                            if (d - 2.0 == (double) keptY && mc.thePlayer.posY + mc.thePlayer.motionY < d) {
                                legitTicks = !legitMotion.isToggled() ? 1 : 5;
                            }
                        }
                        break;
                    }
                    if (fastScaffold.state != 2) {
                        newY = false;
                        if (mc.thePlayer.ticksExisted - fastScaffold.ticks > 10) {
                            keptY = (int) Math.floor(mc.thePlayer.posY) - 1;
                            newY = true;
                        }
                        if ((jumpFacingForward.isToggled() || FastScaffold.Float_A.state == 1 || FastScaffold.Float_B.state == 1) && newY) {
                            if (jumpFacingForward.isToggled()) {
                                legitTicks = !legitMotion.isToggled() ? 1 : 5;
                            }
                            fastScaffold.state = 1;
                        }
                        if (fastScaffold.state == 0) {
                            if (fastScaffold != FastScaffold.Jump_A) {
                                speed = !sprintForward ? (!newY ? 0.545 : 0.4) : (!newY ? 0.3 : 0.25);
                            } else {
                                speed = !isDiagonal() ? 0.53 : 0.37;
                            }
                            jumpWith(speed);
                        }
                    } else {
                        fastScaffold.state = 0;
                    }
                    multiplyMotion(0.95, false);
                    break;
                case Keep_Y_A:
                case Keep_Y_B:
                    if (!groundMoving) {
                        if (moving && jumpFacingForward.isToggled() && mc.thePlayer.motionY < 0.0) {
                            d = Math.floor(mc.thePlayer.posY);
                            if (d - 1.0 == (double) keptY && mc.thePlayer.posY + mc.thePlayer.motionY < d) {
                                legitTicks = !legitMotion.isToggled() ? 1 : 5;
                            }
                        }
                    } else {
                        if (fastScaffold.state != 2) {
                            newY = false;
                            if (mc.thePlayer.ticksExisted - fastScaffold.ticks > 10) {
                                keptY = (int) Math.floor(mc.thePlayer.posY) - 1;
                                newY = true;
                            }
                            if ((jumpFacingForward.isToggled() || FastScaffold.Float_A.state == 1 || FastScaffold.Float_B.state == 1) && newY) {
                                if (jumpFacingForward.isToggled() && legitTicks == 0) {
                                    legitTicks = !legitMotion.isToggled() ? 1 : 5;
                                }
                                fastScaffold.state = 1;
                                multiplyMotion(speedLevel() <= 0 ? 0.8 : 0.4, false);
                            }
                            if (fastScaffold.state == 0) {
                                speed = !sprintForward ? (!newY ? 0.545 : 0.54) : (!newY ? 0.3 : 0.25);
                                jumpWith(speed);
                            }
                        } else {
                            fastScaffold.state = 0;
                        }
                        multiplyMotion(0.95, false);
                    }
                    break;
                default:
                    break;
            }
            if (!mc.thePlayer.onGround && (fastScaffold == FastScaffold.Jump_D || fastScaffold == FastScaffold.Keep_Y_B)
                    && motionYDecimals() == 30) {
                mc.thePlayer.motionY -= 0.14;
            }
            if (groundMoving) {
                d = fastScaffoldMotion.getInput();
                if (!legitMotion.isToggled() && direction().diagonal) {
                    d -= 0.02;
                }
                multiplyMotion(d, true);
                applied = true;
            }
        }
        if (!applied && groundMoving) {
            d = motion.getInput();
            if (!legitMotion.isToggled()) {
                if (slowSpeedPotion.isToggled()) {
                    d -= perSpeedLevel(0.17);
                }
                if (direction().diagonal) {
                    d -= 0.02;
                }
                if (mc.thePlayer.ticksExisted - startTick <= 2) {
                    d -= 0.05;
                }
            }
            multiplyMotion(d, true);
        }
    }

    private static long holdTime() {
        if (mc.thePlayer.isSprinting()) {
            return 710L;
        }
        if (!isMoving()) {
            return 460L;
        }
        return 110L;
    }

    private static void stop() {
        if (active) {
            stoppedAt = System.currentTimeMillis();
            rotation.onDisable();
            if (Utils.nullCheck() && !screenOpen()) {
                int slot = lastSlot.get();
                if (slot != -1 && autoSwap.isToggled()) {
                    mc.thePlayer.inventory.currentItem = slot;
                }
            }
            lastSlot.set(-1);
            if (fastEnabled) {
                fastScaffold.onDisable(true);
                fastEnabled = false;
            }
            if (ModuleManager.tower != null) {
                ModuleManager.tower.towering = false;
            }
            sneakQueued = false;
            smoothSnap = false;
            rightHeld = false;
            active = false;
            isEnabled = false;
            jumpState = 1;
            if (Utils.nullCheck() && mc.thePlayer.onGround && rotation != Rotation.Offset) {
                multiplyMotion(0.75, false);
            }
        }
        keptY = -1;
        lastPlaced = null;
        aimPitch = 100.0f;
        hasSwapped = false;
        fastScaffoldKeepY = false;
        fakeSmoothYaw = 0.0f;
    }

    private static EnumFacing faceFor(BlockPos pos, BlockPos only) {
        BlockPos down = pos.down();
        if (canPlaceAgainst(down) && (only == null || down.equals(only))) {
            return EnumFacing.UP;
        }
        double dx = mc.thePlayer.posX - ((double) pos.getX() + 0.5);
        double dz = mc.thePlayer.posZ - ((double) pos.getZ() + 0.5);
        EnumFacing facing = Math.abs(dx) > Math.abs(dz) ? (dx > 0.0 ? EnumFacing.EAST : EnumFacing.WEST)
                : (dz > 0.0 ? EnumFacing.SOUTH : EnumFacing.NORTH);
        BlockPos toward = pos.offset(facing);
        if (canPlaceAgainst(toward) && (only == null || toward.equals(only))) {
            return facing.getOpposite();
        }
        for (EnumFacing side : SIDES) {
            if (side == facing) {
                continue;
            }
            BlockPos other = pos.offset(side);
            if (!canPlaceAgainst(other)) {
                continue;
            }
            if (only != null && !other.equals(only)) {
                continue;
            }
            return side.getOpposite();
        }
        return null;
    }

    private static boolean canPlaceAgainst(BlockPos pos) {
        if (!placed.isEmpty() && !placed.containsKey(pos) && sincePlace() < 250L) {
            boolean near = false;
            for (BlockPos placedPos : placed.keySet()) {
                if (mc.thePlayer.getDistanceSq(placedPos) < 12.0) {
                    near = true;
                    break;
                }
            }
            if (near) {
                return false;
            }
        }
        return !isReplaceable(pos) && !BlockUtils.isInteractable(BlockUtils.getBlock(pos));
    }

    private static void afterPlace(BlockPos pos, ItemStack itemStack) {
        if (fastScaffold == FastScaffold.Side && fastScaffold.canFastScaffold()
                && (fastScaffold.ticks == -1 || ++fastScaffold.ticks == 2 || fastScaffold.ticks >= 5)) {
            if (fastScaffold.ticks >= 5) {
                fastScaffold.ticks = 0;
            }
            double centerX = (double) pos.getX() + 0.5;
            double centerZ = (double) pos.getZ() + 0.5;
            EnumFacing facing = null;
            switch (mc.thePlayer.getHorizontalFacing()) {
                case NORTH:
                case SOUTH:
                    facing = mc.thePlayer.posX > centerX ? EnumFacing.EAST : EnumFacing.WEST;
                    break;
                case WEST:
                case EAST:
                    facing = mc.thePlayer.posZ > centerZ ? EnumFacing.SOUTH : EnumFacing.NORTH;
                    break;
                default:
                    break;
            }
            Vec3 hitVec = hitVec(pos, facing);
            MovingObjectPosition hit = new MovingObjectPosition(MovingObjectPosition.MovingObjectType.BLOCK, hitVec, facing, pos);
            if (isReplaceable(placedPos(hit))) {
                place(hit, itemStack);
                swing(silentSwing.isToggled(), true);
                placedAt = System.currentTimeMillis();
                if (fastScaffold.ticks == -1) {
                    fastScaffold.ticks = 0;
                }
                return;
            }
            fastScaffold.ticks = -1;
        }
        if (multiPlace != MultiPlace.Disabled && itemStack.stackSize > 0
                && ((ModuleManager.tower != null && ModuleManager.tower.towering) || fastScaffold.canFastScaffold())
                && placeExtra(itemStack) && multiPlace == MultiPlace._2_extra && itemStack.stackSize > 0) {
            placeExtra(itemStack);
        }
    }

    private static boolean placeExtra(ItemStack itemStack) {
        BlockPos target = search(lastPlaced);
        if (target == null) {
            return false;
        }
        EnumFacing facing = faceFor(target, lastPlaced);
        if (facing == null) {
            return false;
        }
        BlockPos against = target.offset(facing.getOpposite());
        float[] found = searchBlockRotation(against, facing, -170.0f, precision, RotationUtils.serverRotations[0]);
        if (found == null) {
            return false;
        }
        MovingObjectPosition hit = rayTraceBlock(against, null, mc.playerController.getBlockReachDistance(), found[0], found[1]);
        if (hit == null) {
            return false;
        }
        place(hit, itemStack);
        swing(silentSwing.isToggled(), true);
        lastPlaced = target;
        placedAt = System.currentTimeMillis();
        placed.put(target, placedAt);
        return true;
    }

    private static MovingObjectPosition aim(BlockPos pos, EnumFacing facing, float offset, float lastYaw) {
        float[] found = searchBlockRotation(pos, facing, offset, precision, lastYaw);
        if (found == null) {
            return null;
        }
        aimYaw = found[0];
        aimPitch = found[1];
        if (rotation == Rotation.Smooth && lastPlaced != null && groundTicks <= 3) {
            smoothLimit = 35.0f + (float) (1.0 * Math.random());
            smoothAt = System.currentTimeMillis();
            float difference = MathHelper.wrapAngleTo180_float(aimYaw - lastYaw);
            if (Math.abs(difference) > smoothLimit) {
                return null;
            }
        }
        return rayTraceBlock(pos, null, mc.playerController.getBlockReachDistance(), aimYaw, aimPitch);
    }

    private static long sincePlace() {
        return Math.abs(System.currentTimeMillis() - placedAt);
    }

    public static boolean towerAllowed() {
        long since = sincePlace();
        return tower.isToggled() && active && (since < 400L || since > 700L);
    }

    private static void applyFakeRotation(float pitch) {
        if (!active || fakeRotation == FakeRotation.None) {
            return;
        }
        float yaw;
        switch (fakeRotation) {
            case Smooth:
                if (fakeSmoothYaw == 0.0f) {
                    fakeSmoothYaw = mc.thePlayer.rotationYaw;
                }
                float target = movementAngle();
                float difference = MathHelper.wrapAngleTo180_float(target - fakeSmoothYaw);
                fakeSmoothYaw += difference * 0.4f;
                yaw = fakeSmoothYaw;
                break;
            case Forward:
                yaw = mc.thePlayer.rotationYawHead;
                break;
            case Spin: {
                long elapsed = System.currentTimeMillis() - startedAt;
                yaw = mc.thePlayer.rotationYaw + (float) (elapsed % 750L) / 750.0f * 360.0f;
                break;
            }
            default:
                return;
        }
        RotationUtils.setFakeRotations(yaw, pitch);
    }

    // ------------------------------------------------------------------
    // Compatibility accessors used by other Raven modules.
    // ------------------------------------------------------------------

    public boolean sprint() {
        return active && fastScaffold != null && fastScaffold.canSprint();
    }

    public static boolean holdingBlocks() {
        if (mc.thePlayer == null) {
            return false;
        }
        return isScaffoldBlock(mc.thePlayer.getHeldItem()) || autoSwap.isToggled() && countBlocks() > 0;
    }

    public boolean stopFastPlace() {
        return isEnabled();
    }

    public boolean stopRotation() {
        return isEnabled && rotation != Rotation.None;
    }

    public boolean canSafewalk() {
        if (!active) {
            return false;
        }
        if (safeWalk.isToggled()) {
            if (fastScaffold != null && (fastScaffold.jump || fastScaffold.keepY)) {
                return !fastScaffold.canFastScaffold();
            }
            return true;
        }
        return fastScaffold != null && (fastScaffold == FastScaffold.Float_A || fastScaffold == FastScaffold.Float_B)
                && fastScaffold.canFastScaffold();
    }

    public int totalBlocks() {
        return countBlocks();
    }

    public int getJumpLevel() {
        return potionLevel(Potion.jump);
    }

    public float getMotionYaw() {
        return (float) Math.toDegrees(Math.atan2(mc.thePlayer.motionZ, mc.thePlayer.motionX)) - 90.0f;
    }

    public float hardcodedYaw() {
        float simpleYaw = 0.0f;
        boolean w = Keyboard.isKeyDown(mc.gameSettings.keyBindForward.getKeyCode());
        boolean s = Keyboard.isKeyDown(mc.gameSettings.keyBindBack.getKeyCode());
        boolean a = Keyboard.isKeyDown(mc.gameSettings.keyBindLeft.getKeyCode());
        boolean d = Keyboard.isKeyDown(mc.gameSettings.keyBindRight.getKeyCode());
        boolean duplicate = a & d;
        if (w) {
            simpleYaw -= 180.0f;
            if (!duplicate) {
                if (a) {
                    simpleYaw += 45.0f;
                }
                if (d) {
                    simpleYaw -= 45.0f;
                }
            }
        } else if (!s) {
            simpleYaw -= 180.0f;
            if (!duplicate) {
                if (a) {
                    simpleYaw += 90.0f;
                }
                if (d) {
                    simpleYaw -= 90.0f;
                }
            }
        } else if (!duplicate) {
            if (a) {
                simpleYaw -= 45.0f;
            }
            if (d) {
                simpleYaw += 45.0f;
            }
        }
        return simpleYaw;
    }

    public void rotateForward(boolean delayForward) {
        if (jumpFacingForward.isToggled() && rotation != Rotation.None) {
            rotateForwardTicks = delayForward ? 5 : 2;
        }
    }

    // ------------------------------------------------------------------
    // Auto swap (ported from OpenB4's AutoSwapModule, backed by Raven's
    // AutoSwap settings module).
    // ------------------------------------------------------------------

    private static void autoSwapTick() {
        if (ModuleManager.autoSwap == null || screenOpen()) {
            return;
        }
        ItemStack held = mc.thePlayer.getHeldItem();
        boolean heldBlock = isScaffoldBlock(held);
        if (!ModuleManager.autoSwap.swapToGreaterStack.isToggled() && heldBlock
                && (double) held.stackSize > ModuleManager.autoSwap.swapAt.getInput()) {
            return;
        }
        int current = mc.thePlayer.inventory.currentItem;
        int bestSlot = -1;
        int bestSize = -1;
        for (int i = 8; i >= 0; --i) {
            if (i == current) {
                continue;
            }
            ItemStack stack = mc.thePlayer.inventory.getStackInSlot(i);
            if (!isScaffoldBlock(stack) || bestSize >= stack.stackSize) {
                continue;
            }
            if (heldBlock) {
                if (held.stackSize >= stack.stackSize) {
                    continue;
                }
                if (ModuleManager.autoSwap.sameType.isToggled() && held.getItem() != stack.getItem()) {
                    continue;
                }
            }
            bestSize = stack.stackSize;
            bestSlot = i;
        }
        if (bestSlot != -1 && current != bestSlot) {
            if (lastSlot.get() == -1) {
                lastSlot.set(current);
            }
            Utils.switchSlot(bestSlot, false);
        }
    }

    private static int countBlocks() {
        int total = 0;
        if (mc.thePlayer == null) {
            return 0;
        }
        for (int i = 0; i < 9; ++i) {
            ItemStack stack = mc.thePlayer.inventory.mainInventory[i];
            if (isScaffoldBlock(stack)) {
                total += stack.stackSize;
            }
        }
        return total;
    }

    // ------------------------------------------------------------------
    // OpenB4 utility ports (kept local so the module stays self-contained).
    // ------------------------------------------------------------------

    private static boolean isScaffoldBlock(ItemStack stack) {
        if (stack == null || stack.getItem() == null || !(stack.getItem() instanceof ItemBlock)) {
            return false;
        }
        Block block = ((ItemBlock) stack.getItem()).getBlock();
        return isPlaceableBlock(block) && block != Blocks.pumpkin;
    }

    private static boolean isPlaceableBlock(Block block) {
        return block == Blocks.glass || block.isFullBlock() && block != Blocks.gravel && block != Blocks.sand
                && block != Blocks.soul_sand && block != Blocks.tnt && block != Blocks.crafting_table
                && block != Blocks.furnace && block != Blocks.dispenser && block != Blocks.dropper
                && block != Blocks.noteblock && block != Blocks.command_block;
    }

    private static boolean isReplaceable(BlockPos pos) {
        Block block = BlockUtils.getBlock(pos);
        return block == Blocks.air || block == Blocks.fire || block instanceof BlockLiquid || block.getMaterial().isReplaceable();
    }

    private static BlockPos below(double distance) {
        return new BlockPos(mc.thePlayer.posX, mc.thePlayer.posY - distance, mc.thePlayer.posZ);
    }

    private static BlockPos placedPos(MovingObjectPosition hit) {
        return hit.getBlockPos().offset(hit.sideHit);
    }

    private static Vec3 hitVec(BlockPos pos, EnumFacing facing) {
        double low = 0.1;
        double high = 0.9;
        double yLow = 0.05;
        double yHigh = 0.95;
        double top = 1.0;
        double x = (double) pos.getX() + Utils.randomizeDouble(low, high);
        double y = (double) pos.getY() + Utils.randomizeDouble(yLow, yHigh);
        double z = (double) pos.getZ() + Utils.randomizeDouble(low, high);
        IBlockState state = BlockUtils.getBlockState(pos);
        Block block = state.getBlock();
        if (!block.isFullBlock() && block instanceof BlockSlab) {
            if (state.getValue(BlockSlab.HALF) == BlockSlab.EnumBlockHalf.TOP) {
                yLow = 0.505;
            } else if (facing == EnumFacing.UP) {
                top = 0.5;
            } else {
                yHigh = 0.495;
            }
            y = (double) pos.getY() + Utils.randomizeDouble(yLow, yHigh);
        }
        switch (facing) {
            case EAST:
                x = pos.getX() + 1;
                break;
            case WEST:
                x = pos.getX();
                break;
            case NORTH:
                z = pos.getZ();
                break;
            case SOUTH:
                z = pos.getZ() + 1;
                break;
            case UP:
                y = (double) pos.getY() + top;
                break;
            case DOWN:
                y = pos.getY();
                break;
            default:
                break;
        }
        return new Vec3(x, y, z);
    }

    private static boolean isMoving() {
        return mc.thePlayer.movementInput.moveForward != 0.0f || mc.thePlayer.movementInput.moveStrafe != 0.0f;
    }

    private static boolean jumpKeyDown() {
        return mc.gameSettings.keyBindJump.isKeyDown();
    }

    private static void setJump(boolean jump) {
        mc.thePlayer.movementInput.jump = jump;
    }

    private static void setSneak(boolean sneak) {
        boolean was = mc.thePlayer.movementInput.sneak;
        mc.thePlayer.movementInput.sneak = sneak;
        if (!was && sneak) {
            mc.thePlayer.movementInput.moveForward *= 0.3f;
            mc.thePlayer.movementInput.moveStrafe *= 0.3f;
        }
    }

    private static float forwardInput() {
        float forward = 0.0f;
        if (mc.gameSettings.keyBindForward.isKeyDown()) {
            forward += 1.0f;
        }
        if (mc.gameSettings.keyBindBack.isKeyDown()) {
            forward -= 1.0f;
        }
        return forward;
    }

    private static float strafeInput() {
        float strafe = 0.0f;
        if (mc.gameSettings.keyBindLeft.isKeyDown()) {
            strafe += 1.0f;
        }
        if (mc.gameSettings.keyBindRight.isKeyDown()) {
            strafe -= 1.0f;
        }
        return strafe;
    }

    private static MoveDirection direction() {
        return direction(forwardInput(), strafeInput());
    }

    private static MoveDirection direction(double forward, double strafe) {
        if (forward == 0.0) {
            if (strafe == 0.0) {
                return MoveDirection.NONE;
            }
            return strafe > 0.0 ? MoveDirection.LEFT : MoveDirection.RIGHT;
        }
        if (forward > 0.0) {
            if (strafe == 0.0) {
                return MoveDirection.FORWARD;
            }
            return strafe > 0.0 ? MoveDirection.FORWARD_LEFT : MoveDirection.FORWARD_RIGHT;
        }
        if (strafe == 0.0) {
            return MoveDirection.BACK;
        }
        return strafe > 0.0 ? MoveDirection.BACK_LEFT : MoveDirection.BACK_RIGHT;
    }

    private static boolean isDiagonal() {
        float yaw = Math.abs(mc.thePlayer.rotationYaw % 90.0f);
        return yaw > 10.0f && yaw < 80.0f || direction().diagonal;
    }

    private static float movementAngle() {
        return mc.thePlayer.rotationYaw + (float) inputAngle();
    }

    private static double inputAngle() {
        double angle = Math.toDegrees(Math.atan2(strafeInput(), -forwardInput()));
        return (angle + 360.0) % 360.0;
    }

    private static double inputAngleSigned() {
        double angle = Math.toDegrees(Math.atan2(-strafeInput(), forwardInput()));
        return angle == 0.0 ? 0.0 : angle;
    }

    private static void jump() {
        mc.thePlayer.jump();
    }

    private static void jump(double speed) {
        strafe(speed);
        jump();
    }

    private static void multiplyMotion(double multiplier) {
        mc.thePlayer.motionX *= multiplier;
        mc.thePlayer.motionZ *= multiplier;
    }

    private static void multiplyMotion(double multiplier, boolean always) {
        if (!always && legitMotion.isToggled()) {
            return;
        }
        multiplyMotion(multiplier);
    }

    private static int potionLevel(Potion potion) {
        PotionEffect effect = mc.thePlayer.getActivePotionEffect(potion);
        return effect == null ? 0 : effect.getAmplifier() + 1;
    }

    private static int speedLevel() {
        return potionLevel(Potion.moveSpeed);
    }

    private static double perSpeedLevel(double perLevel) {
        return (double) speedLevel() * perLevel;
    }

    private static int idOffset() {
        int id = Math.abs(mc.thePlayer.getEntityId());
        return id > 999 ? id + 213 : id * 99;
    }

    private static int decimals(double value) {
        return (int) Math.round(value % 1.0 * 10000.0);
    }

    private static int posYDecimals() {
        return decimals(mc.thePlayer.posY);
    }

    private static int motionYDecimals() {
        return (int) Math.round(mc.thePlayer.motionY % 10.0 * 10000.0);
    }

    private static boolean isAirBelow() {
        BlockPos pos = new BlockPos(mc.thePlayer.posX,
                mc.thePlayer.posY - (double) (mc.thePlayer.posY % 1.0 == 0.0 ? 1 : 0), mc.thePlayer.posZ);
        return mc.theWorld.isAirBlock(pos);
    }

    private static boolean nearEdge(double margin) {
        int x = MathHelper.floor_double(mc.thePlayer.posX);
        int y = MathHelper.floor_double(mc.thePlayer.posY - 0.01);
        int z = MathHelper.floor_double(mc.thePlayer.posZ);
        BlockPos pos = new BlockPos(x, y, z);
        if (mc.theWorld.isAirBlock(pos)) {
            return true;
        }
        double fx = mc.thePlayer.posX - (double) x;
        double fz = mc.thePlayer.posZ - (double) z;
        return fx < margin && mc.theWorld.isAirBlock(new BlockPos(x - 1, y, z))
                || fx > 1.0 - margin && mc.theWorld.isAirBlock(new BlockPos(x + 1, y, z))
                || fz < margin && mc.theWorld.isAirBlock(new BlockPos(x, y, z - 1))
                || fz > 1.0 - margin && mc.theWorld.isAirBlock(new BlockPos(x, y, z + 1));
    }

    private static void strafe(double speed) {
        setSpeed(speed, movementYaw());
    }

    private static void setSpeed(double speed) {
        setSpeed(speed, mc.thePlayer.rotationYaw);
    }

    private static void setSpeed(double speed, float yaw) {
        if (speed == 0.0) {
            mc.thePlayer.motionZ = 0.0;
            mc.thePlayer.motionX = 0.0;
            return;
        }
        mc.thePlayer.motionX = -Math.sin(yaw * (float) Math.PI / 180.0f) * speed;
        mc.thePlayer.motionZ = Math.cos(yaw * (float) Math.PI / 180.0f) * speed;
    }

    private static float movementYaw() {
        RotationHelper helper = RotationHelper.get();
        float yaw;
        if (Settings.movementFix != null && Settings.movementFix.isToggled() && helper.getServerYaw() != null) {
            yaw = helper.getServerYaw();
        } else {
            yaw = mc.thePlayer.rotationYaw;
        }
        float forward = mc.thePlayer.movementInput.moveForward;
        float strafe = mc.thePlayer.movementInput.moveStrafe;
        float multiplier = 1.0f;
        if (forward < 0.0f) {
            yaw += 180.0f;
            multiplier = -0.5f;
        } else if (forward > 0.0f) {
            multiplier = 0.5f;
        }
        if (strafe > 0.0f) {
            yaw -= 90.0f * multiplier;
        } else if (strafe < 0.0f) {
            yaw += 90.0f * multiplier;
        }
        return yaw;
    }

    private static double speed() {
        return Math.sqrt(mc.thePlayer.motionX * mc.thePlayer.motionX + mc.thePlayer.motionZ * mc.thePlayer.motionZ);
    }

    private static void swing(boolean packetOnly, boolean resetEquip) {
        if (packetOnly) {
            mc.thePlayer.sendQueue.addToSendQueue(new C0APacketAnimation());
        } else {
            mc.thePlayer.swingItem();
            if (resetEquip) {
                mc.getItemRenderer().resetEquippedProgress();
            }
        }
    }

    private static void place(MovingObjectPosition hit, ItemStack itemStack) {
        float fx = (float) (hit.hitVec.xCoord - (double) hit.getBlockPos().getX());
        float fy = (float) (hit.hitVec.yCoord - (double) hit.getBlockPos().getY());
        float fz = (float) (hit.hitVec.zCoord - (double) hit.getBlockPos().getZ());
        mc.thePlayer.sendQueue.addToSendQueue(new C08PacketPlayerBlockPlacement(hit.getBlockPos(), hit.sideHit.getIndex(), itemStack, fx, fy, fz));
        if (!mc.thePlayer.capabilities.isCreativeMode) {
            itemStack.onItemUse(mc.thePlayer, mc.theWorld, hit.getBlockPos(), hit.sideHit, fx, fy, fz);
            if (itemStack.stackSize <= 0) {
                ForgeEventFactory.onPlayerDestroyItem(mc.thePlayer, itemStack);
            }
        }
    }

    private static void syncCurrentPlayItem() {
        ((IAccessorPlayerControllerMP) mc.playerController).syncCurrentPlayItem();
    }

    private static MovingObjectPosition rayTraceBlock(BlockPos pos, EnumFacing facing, double range, float yaw, float pitch) {
        MovingObjectPosition hit = RotationUtils.rayCastBlock((float) range, yaw, pitch);
        if (hit == null || hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK) {
            return null;
        }
        if (!BlockUtils.isSamePos(hit.getBlockPos(), pos) || facing != null && hit.sideHit != facing) {
            return null;
        }
        return hit;
    }

    private static float[] searchBlockRotation(BlockPos pos, EnumFacing facing, float offset, Precision precision, float airYaw) {
        float best = 360.0f;
        float bestYaw = 0.0f;
        float bestPitch = 0.0f;
        float base = mc.thePlayer.onGround ? movementAngle() : airYaw;
        float from = base + offset;
        float to = base + 170.0f;
        float reach = mc.playerController.getBlockReachDistance();
        for (float yaw = from; yaw <= to; yaw += precision.getOffsetYaw()) {
            float jitteredYaw = (float) ((double) yaw + (Math.random() - 0.5));
            for (float pitch = 90.0f; pitch > 30.0f; pitch -= precision.getOffsetPitch(pitch)) {
                float jitteredPitch = MathHelper.clamp_float((float) ((double) pitch + (Math.random() - 0.5)), -90.0f, 90.0f);
                MovingObjectPosition hit = RotationUtils.rayCastBlock(reach, jitteredYaw, jitteredPitch);
                if (hit == null || hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK
                        || facing != null && hit.sideHit != facing
                        || !BlockUtils.isSamePos(hit.getBlockPos(), pos)
                        || !awayFromEdge((float) (hit.hitVec.yCoord - (double) hit.getBlockPos().getY()))
                        || !awayFromEdge((float) (hit.hitVec.xCoord - (double) hit.getBlockPos().getX()))
                        || !awayFromEdge((float) (hit.hitVec.zCoord - (double) hit.getBlockPos().getZ()))) {
                    continue;
                }
                float difference = Math.abs(base - jitteredYaw);
                if (difference < best) {
                    best = difference;
                    bestYaw = jitteredYaw;
                    bestPitch = jitteredPitch;
                }
            }
        }
        return best == 360.0f ? null : new float[]{bestYaw, bestPitch};
    }

    private static boolean awayFromEdge(float value) {
        return value == 0.0f || value == 1.0f || (double) value > 0.06 && (double) value < 0.94;
    }

    private static float[] fixSensitivity(float yaw, float pitch, float lastYaw, float lastPitch) {
        float deltaYaw = yaw - lastYaw;
        float deltaPitch = pitch - lastPitch;
        float sensitivity = mc.gameSettings.mouseSensitivity * 0.6f + 0.2f;
        double step = (double) (sensitivity * sensitivity * sensitivity) * 1.2;
        float fixedYaw = (float) ((double) Math.round((double) deltaYaw / step) * step);
        float fixedPitch = (float) ((double) Math.round((double) deltaPitch / step) * step);
        return new float[]{lastYaw + fixedYaw, MathHelper.clamp_float(lastPitch + fixedPitch, -90.0f, 90.0f)};
    }

    private static float randomOffset() {
        return (float) (Math.random() - 0.5);
    }

    private static float randomFloat(float min, float max) {
        return (float) ((double) min + (double) (max - min) * Math.random());
    }

    private static double distanceSq(double x, double y, double z, Vec3 point) {
        double dx = x - point.xCoord;
        double dy = y - point.yCoord;
        double dz = z - point.zCoord;
        return dx * dx + dy * dy + dz * dz;
    }

    private static double behind(double x, double z, double dirX, double dirZ, Vec3 point) {
        double dx = point.xCoord - x;
        double dz = point.zCoord - z;
        return -(dx * dirX + dz * dirZ);
    }

    private static double speedBonus() {
        int level = speedLevel();
        switch (level) {
            case 0:
                return 0.0;
            case 1:
                return 0.05;
            case 2:
                return speedBoost.getInput() / 10.0;
            default:
                return 0.06 * (double) level;
        }
    }

    private static double boosted(double speed) {
        double boosted = speed + speedBonus() + 1.0E-4 * Math.random();
        if (sinceSetback(500L)) {
            boosted *= speedLevel() > 0 ? 0.85 : 0.95;
        }
        return boosted;
    }

    private static void jumpWith(double speed) {
        if (legitMotion.isToggled()) {
            return;
        }
        jump(boosted(speed));
    }

    private static boolean sinceSetback(long millis) {
        return System.currentTimeMillis() - lastSetback < millis;
    }

    private static boolean noUseOrDig() {
        if (Raven.packetsHandler == null) {
            return true;
        }
        return !Raven.packetsHandler.C02.sentCurrentTick.get() && !Raven.packetsHandler.C07.sentCurrentTick.get();
    }

    private static boolean screenOpen() {
        return mc.currentScreen != null;
    }

    private static boolean isAdventure() {
        return mc.thePlayer != null && !mc.thePlayer.capabilities.allowEdit;
    }
}
