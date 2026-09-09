package io.openems.edge.controller.heatpump.shi;

/**
 * The power balance of a single cycle: every derived power figure the Controller
 * bases its decisions and its ESS commands on, calculated exactly once from the
 * cycle's measurements.
 *
 * <p>
 * All figures are derived from three measurements (grid, ESS, heat pump) plus the
 * wiring topology, and the derivation is subtle enough that recalculating parts of
 * it per call site has repeatedly produced inconsistent results. Two distinctions
 * matter and are therefore explicit in the field names:
 *
 * <ul>
 * <li>{@link #batteryPower()} is SIGNED (negative while charging), while
 * {@link #batteryDischarge()} is clamped at 0. The surplus must use the clamped
 * one - a battery charging from PV has already taken its share, so it must not
 * appear as surplus available for the heat pump - whereas the household deficit
 * must use the signed one, because a charging battery raises the grid import
 * without the household needing anything.</li>
 * <li>{@link #pvShare()} is the DC-PV share flowing through the inverter of a
 * HybridEss, i.e. the part of {@code EssActivePower} that is not battery. It is 0
 * on an AC-coupled system, where both {@code _sum} channels carry the same value.
 * Every figure meant to be BATTERY power has it removed; every figure that bounds
 * the ESS ACTIVE power has it added back.</li>
 * </ul>
 *
 * @param heatPumpPower    current heat-pump consumption in W (>= 0)
 * @param batteryPower     battery-only power in W, signed (negative = charging)
 * @param batteryDischarge battery-only discharge in W, clamped at 0
 * @param essAndGridPower  {@code EssActivePower + GridActivePower} in W: the flow
 *                         of the whole segment, which the forced export has to
 *                         overcome before anything reaches a grid-side heat pump
 * @param surplusPower     PV surplus available for the heat pump in W (>= 0),
 *                         after household and battery charging took their share
 *                         and without counting any battery discharge
 * @param householdDeficit household load in W (>= 0) that PV does NOT cover, so
 *                         it is served from grid or battery. Invariant to how it
 *                         is currently split between the two: it is what the
 *                         household needs, not what the battery happens to
 *                         deliver already
 * @param pvShare          DC-PV share of the ESS inverter power in W (>= 0)
 * @param maxSupportPower  upper bound of the battery power available to support
 *                         the heat pump in W (>= 0); 0 when no battery energy is
 *                         released
 */
record PowerBalance(//
		int heatPumpPower, //
		int batteryPower, //
		int batteryDischarge, //
		int essAndGridPower, //
		int surplusPower, //
		int householdDeficit, //
		int pvShare, //
		int maxSupportPower) {

	/**
	 * Calculates the balance from the cycle's measurements. The heat-pump position
	 * decides whether the heat pump is part of the grid measurement:
	 * {@code BEHIND_GRID_METER} has to add it back to the surplus (otherwise the
	 * heat pump eats its own surplus signal) and remove it from the household load,
	 * while {@code GRID_SIDE_OF_GRID_METER} sees neither - there the heat pump is
	 * invisible at the grid meter and fed by whatever the segment exports.
	 *
	 * @param position         the {@link HeatPumpPosition}
	 * @param gridActivePower  grid power in W (import positive)
	 * @param essActivePower   ESS active power in W; on a HybridEss the whole
	 *                         inverter, PV included
	 * @param batteryPower     battery-only power in W, signed (negative = charging)
	 * @param heatPumpPower    heat-pump consumption in W
	 * @return the {@link PowerBalance}, with {@code maxSupportPower} still 0
	 */
	public static PowerBalance of(HeatPumpPosition position, int gridActivePower, int essActivePower, int batteryPower,
			int heatPumpPower) {
		var heatPump = Math.max(0, heatPumpPower);
		var batteryDischarge = Math.max(0, batteryPower);
		var surplusPower = switch (position) {
		case BEHIND_GRID_METER -> Math.max(0, -gridActivePower - batteryDischarge + heatPump);
		case GRID_SIDE_OF_GRID_METER -> Math.max(0, -gridActivePower - batteryDischarge);
		};
		var householdDeficit = switch (position) {
		case BEHIND_GRID_METER -> Math.max(0, gridActivePower + batteryPower - heatPump);
		case GRID_SIDE_OF_GRID_METER -> Math.max(0, gridActivePower + batteryPower);
		};
		return new PowerBalance(heatPump, batteryPower, batteryDischarge, essActivePower + gridActivePower,
				surplusPower, householdDeficit, Math.max(0, essActivePower - batteryPower), 0);
	}

	/**
	 * Returns a copy carrying the given battery support budget.
	 *
	 * @param maxSupportPower the upper bound of the battery support power in W
	 * @return the {@link PowerBalance}
	 */
	public PowerBalance withMaxSupportPower(int maxSupportPower) {
		return new PowerBalance(this.heatPumpPower, this.batteryPower, this.batteryDischarge, this.essAndGridPower,
				this.surplusPower, this.householdDeficit, this.pvShare, Math.max(0, maxSupportPower));
	}

	/**
	 * Battery support power the ESS can currently deliver FOR THE HEAT PUMP: the
	 * ESS maximum minus the PV share it already carries and minus the share the
	 * household needs, optionally capped.
	 *
	 * <p>
	 * Subtracting the household share is essential: a battery that can discharge
	 * 3 kW while the household needs 2 kW has only 1 kW left for the heat pump, and
	 * the coverage checks must see that 1 kW - not the full 3 kW - or a battery
	 * already busy serving the household makes the heat pump look fully covered and
	 * the gap is then left to the grid.
	 *
	 * @param essMaxPower the solved ESS maximum Active-Power in W; on a HybridEss an
	 *                    AC bound that carries the PV
	 * @param cap         optional upper limit in W; 0 = no limit
	 * @return the deliverable support power in W (>= 0)
	 */
	public int deliverableSupportPower(int essMaxPower, int cap) {
		var deliverable = Math.max(0, essMaxPower - this.pvShare - this.householdDeficit);
		return cap > 0 ? Math.min(cap, deliverable) : deliverable;
	}
}
