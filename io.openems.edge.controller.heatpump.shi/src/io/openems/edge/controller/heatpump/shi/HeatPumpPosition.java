package io.openems.edge.controller.heatpump.shi;

/**
 * Where the heat pump is connected relative to the grid meter used by OpenEMS.
 */
public enum HeatPumpPosition {
	/**
	 * The heat pump is connected behind the grid meter (standard installation,
	 * grid meter at the grid connection point). Its consumption is part of the
	 * grid measurement and of the calculated '_sum' consumption. The battery
	 * would serve it via Balancing by default, so this Controller LIMITS the ESS
	 * discharge to the household demand plus the allowed support power.
	 */
	BEHIND_GRID_METER,
	/**
	 * The heat pump is connected grid-side (upstream) of the grid meter. Its
	 * consumption is invisible at the balancing point, so the battery never
	 * serves it by default; this Controller FORCES battery export towards the
	 * heat pump while support is allowed.
	 */
	GRID_SIDE_OF_GRID_METER;
}
