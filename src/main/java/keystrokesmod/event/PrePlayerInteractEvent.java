package keystrokesmod.event;

import net.minecraftforge.fml.common.eventhandler.Event;

/**
 * Posted once per client tick just before player interaction (item use) is
 * processed, so modules can refresh their spoofed-item state in time.
 */
public class PrePlayerInteractEvent extends Event {
}
