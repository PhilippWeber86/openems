package io.openems.edge.controller.heatpump.shi;

import io.openems.edge.heat.shi.HeatShiHeatPump;

/**
 * The complete set of register values one cycle wants on the heat pump, derived
 * from the {@link OperatingState}.
 *
 * <p>
 * Previously each operating mode wrote its own subset of overlapping registers,
 * so understanding which command ultimately held required knowing the order the
 * methods were called in - the hot-water registers in particular were written by
 * the run extension and released by the normal-mode path, guarded by a flag.
 * Building one complete command per state removes that coupling: the state
 * decides everything, and a single writer transfers it.
 *
 * <p>
 * A null mode or setpoint means "leave that register alone". This is not
 * cosmetic: the SHI rejects heating or hot-water commands while the corresponding
 * operating mode is switched off at the heat pump (status 0, e.g. heating in
 * summer), so those registers must not be written at all in that case.
 *
 * @param heatingMode      HR10000 value, or null to leave it untouched
 * @param heatingSetpoint  HR10001 value in 0.1 degC, or null
 * @param hotWaterMode     HR10005 value, or null to leave it untouched
 * @param hotWaterSetpoint HR10006 value in 0.1 degC, or null
 * @param lpcMode          HR10040 value; always written
 * @param pcLimit          HR10041 value in W; always written
 */
record HeatPumpCommand(//
		Integer heatingMode, //
		Integer heatingSetpoint, //
		Integer hotWaterMode, //
		Integer hotWaterSetpoint, //
		int lpcMode, //
		int pcLimit) {

	/** Upper bound of SHI register HR10041 (300 x 0.1 kW). */
	public static final int MAX_PC_LIMIT = 30_000; // [W]

	/**
	 * BOOST: raise both setpoints and soft-limit the heat pump to the power that is
	 * actually available, so it stores the surplus as heat.
	 *
	 * @param heatingStatus    IR heating status; 0 means heating is off at the device
	 * @param hotWaterStatus   IR hot-water status; 0 means hot water is off
	 * @param heatingSetpoint  the elevated heating setpoint in 0.1 degC
	 * @param hotWaterSetpoint the elevated hot-water setpoint in 0.1 degC
	 * @param availablePower   the power available for the heat pump in W
	 * @return the {@link HeatPumpCommand}
	 */
	public static HeatPumpCommand boost(int heatingStatus, int hotWaterStatus, int heatingSetpoint,
			int hotWaterSetpoint, int availablePower) {
		return new HeatPumpCommand(//
				heatingStatus > 0 ? HeatShiHeatPump.MODE_SETPOINT : null, //
				heatingStatus > 0 ? heatingSetpoint : null, //
				hotWaterStatus > 0 ? HeatShiHeatPump.MODE_SETPOINT : null, //
				hotWaterStatus > 0 ? hotWaterSetpoint : null, //
				HeatShiHeatPump.LPC_MODE_SOFT, //
				clampLimit(availablePower));
	}

	/**
	 * RUN_EXTENSION: raise the hot-water setpoint only. Heating is released - the
	 * heat pump started this run on its own and elevating the heating setpoint
	 * would overheat the house.
	 *
	 * <p>
	 * The soft limit here is a recommendation, not the guarantee against grid draw:
	 * that guarantee is the immediate release as soon as coverage is lost.
	 *
	 * @param heatingStatus    IR heating status; 0 means heating is off at the device
	 * @param hotWaterSetpoint the elevated hot-water setpoint in 0.1 degC
	 * @param coverage         the covered power in W
	 * @return the {@link HeatPumpCommand}
	 */
	public static HeatPumpCommand runExtension(int heatingStatus, int hotWaterSetpoint, int coverage) {
		return new HeatPumpCommand(//
				heatingStatus > 0 ? HeatShiHeatPump.MODE_NONE : null, //
				null, //
				HeatShiHeatPump.MODE_SETPOINT, //
				hotWaterSetpoint, //
				HeatShiHeatPump.LPC_MODE_SOFT, //
				clampLimit(coverage));
	}

	/**
	 * NORMAL: release both setpoints. A run the heat pump started on its own must
	 * not be elevated.
	 *
	 * <p>
	 * The soft power limit is still written while such a run is active, so the heat
	 * pump can modulate down onto the power PV and the battery actually pay for
	 * instead of taking the difference from the grid. The limit is a recommendation:
	 * the heat pump discards it once its temperature deviates too far from its
	 * setpoint, so a genuine heat demand is protected by the device itself. The case
	 * that must be avoided is the opposite one - close to the setpoint the heat pump
	 * does obey, and a limit below its minimum sensible power would push it into a
	 * compressor stop or short cycling. Hence the limit is written exclusively while
	 * the coverage carries at least the minimum power, and released entirely below
	 * that, where a "use almost nothing" recommendation would be dishonest anyway.
	 *
	 * @param heatingStatus  IR heating status; 0 means heating is off at the device
	 * @param hotWaterStatus IR hot-water status; 0 means hot water is off
	 * @param limitSoftPower whether a self-started run is active AND the coverage
	 *                       carries at least the minimum sensible power
	 * @param coveredPower   the covered power in W
	 * @return the {@link HeatPumpCommand}
	 */
	public static HeatPumpCommand normal(int heatingStatus, int hotWaterStatus, boolean limitSoftPower,
			int coveredPower) {
		return new HeatPumpCommand(//
				heatingStatus > 0 ? HeatShiHeatPump.MODE_NONE : null, //
				null, //
				hotWaterStatus > 0 ? HeatShiHeatPump.MODE_NONE : null, //
				null, //
				limitSoftPower ? HeatShiHeatPump.LPC_MODE_SOFT : HeatShiHeatPump.LPC_MODE_NONE, //
				limitSoftPower ? clampLimit(coveredPower) : 0);
	}

	private static int clampLimit(int power) {
		return Math.max(0, Math.min(MAX_PC_LIMIT, power));
	}
}
