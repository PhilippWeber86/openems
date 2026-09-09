package io.openems.edge.controller.heatpump.shi;

import io.openems.common.types.OptionsEnum;

/**
 * Why the Controller is in its current {@link OperatingState}.
 *
 * <p>
 * "Normal operation" on its own does not explain anything: the interesting cases
 * are the ones where a boost is wanted but held back, and they are
 * indistinguishable from the outside. This names the binding cause, so the widget
 * and the history can show why nothing is happening right now.
 *
 * <p>
 * Deliberately about the OPERATING STATE only. Why the battery is not supporting
 * the heat pump is a separate question - a boost never requires battery energy,
 * PV surplus alone starts it - and it is answered by `FreeBatteryEnergy`,
 * `NightReserveEnergy` and the `NoPredictionAvailable` warning.
 */
public enum DecisionReason implements OptionsEnum {

	UNDEFINED(-1, "Undefined"),
	/** Elevated setpoints are active on PV surplus. */
	BOOST_ACTIVE(0, "Boost active on PV surplus"),
	/** A natural hot-water run is being extended. */
	RUN_EXTENSION_ACTIVE(1, "Natural hot-water run extended"),
	/**
	 * A grid, ESS or heat-pump power reading is missing, so the PV surplus cannot be
	 * verified - fail-safe, the heat pump is released.
	 */
	MEASUREMENT_UNAVAILABLE(2, "Power measurement unavailable"),
	/** The PV surplus alone does not reach the minimum power for a boost. */
	SURPLUS_TOO_LOW(3, "PV surplus below the minimum power"),
	/**
	 * The entry conditions hold, but the confirmation time has not been reached yet -
	 * short surplus spikes must not trigger a committed compressor cycle.
	 */
	BOOST_CONFIRMATION_PENDING(4, "Waiting for the boost confirmation time"),
	/** The forecast does not sustain a full compressor cycle, so entry is vetoed. */
	FORECAST_VETO(5, "Blocked by the forecast veto"),
	/**
	 * The entry conditions are fulfilled, but the compressor cycle limits (minimum
	 * runtime, restart lock) or the configured minimum switching time still block
	 * the change.
	 */
	SWITCHING_HYSTERESIS(6, "Blocked by the switching hysteresis");

	private final int value;
	private final String name;

	private DecisionReason(int value, String name) {
		this.value = value;
		this.name = name;
	}

	@Override
	public int getValue() {
		return this.value;
	}

	@Override
	public String getName() {
		return this.name;
	}

	@Override
	public OptionsEnum getUndefined() {
		return UNDEFINED;
	}
}
