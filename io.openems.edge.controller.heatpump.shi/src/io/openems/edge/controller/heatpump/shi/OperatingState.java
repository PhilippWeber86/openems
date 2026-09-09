package io.openems.edge.controller.heatpump.shi;

/**
 * The one operating state this Controller has decided on for the current cycle.
 * Exactly one of them holds at a time, and it alone determines the command set
 * written to the heat pump - so which command wins no longer depends on the order
 * in which methods happen to be called.
 */
public enum OperatingState {
	/**
	 * No external setpoint influence. The soft power limit may still be written to
	 * hold a run the heat pump started on its own down to the power PV and battery
	 * actually pay for.
	 */
	NORMAL,
	/**
	 * Elevated setpoints on PV surplus: the heat pump is driven to store the
	 * surplus as heat. Committed for the compressor minimum runtime.
	 */
	BOOST,
	/**
	 * A natural hot-water run is extended to the elevated hot-water setpoint. The
	 * compressor is already running, so the storage is topped up without an extra
	 * start. Heating stays released - only hot water is raised.
	 */
	RUN_EXTENSION;
}
