package io.openems.edge.controller.heatpump.shi;

/**
 * How aggressively free battery energy is used to drive the heat pump beyond
 * the current PV surplus. This only governs the setpoint elevation (boost) and
 * the run extension; the passive support that pays for a heat pump running on
 * its own (from energy above the night reserve) is unaffected and active in
 * both modes.
 */
public enum BatterySupportMode {
	/**
	 * The battery is invited into the heat pump's target power: the boost soft
	 * limit and the run-extension coverage are PV surplus PLUS the deliverable
	 * battery power, so free battery energy above the night reserve is actively
	 * converted into extra heat now.
	 */
	OFFENSIVE,
	/**
	 * The battery is not invited into the heat pump's target power: the boost soft
	 * limit and the run-extension coverage use the PV surplus ALONE, so the heat
	 * pump follows the sun. The battery only cushions short surplus dips reactively
	 * (passive support) and never pushes the heat pump above the surplus.
	 */
	CLOUD_BUFFER;
}
