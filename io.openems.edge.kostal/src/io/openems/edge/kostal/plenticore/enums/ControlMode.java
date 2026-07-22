package io.openems.edge.kostal.plenticore.enums;

public enum ControlMode {

	/**
	 * Uses the internal 'AUTO' mode of the inverter. Allows no remote control of
	 * Set-Points. Requires a Smart Meter at the grid junction point.
	 */
	INTERNAL,
	/**
	 * Full control of the inverter by OpenEMS. Slower than internal 'AUTO' mode,
	 * but does not require a Smart Meter at the grid junction point.
	 */
	REMOTE,
	/**
	 * Uses the internal 'AUTO' self-consumption mode of the inverter while OpenEMS
	 * has no active Set-Point to apply, and takes over with remote Set-Points only
	 * when an intervention is required. Handing control back to the internal mode
	 * relies on the inverter's control timeout (the battery-management-mode
	 * register is read-only): OpenEMS stops writing the Set-Point and the inverter
	 * returns to internal operation after the configured watchdog time. Requires a
	 * Smart Meter at the grid junction point.
	 */
	SMART;
}
