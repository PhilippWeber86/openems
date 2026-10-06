package io.openems.edge.controller.heatpump.shi;

import static io.openems.edge.controller.heatpump.shi.HeatPumpPosition.BEHIND_GRID_METER;
import static io.openems.edge.controller.heatpump.shi.HeatPumpPosition.GRID_SIDE_OF_GRID_METER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Properties of the per-cycle power balance. These complement the operating
 * scenarios in {@link ControllerShiHeatPumpImplTest}: instead of pinning single
 * points they state the invariants the balance has to satisfy everywhere, which is
 * where the past defects lived - each of them was a case where one call site
 * derived a figure slightly differently from another.
 */
public class PowerBalanceTest {

	/**
	 * The household load is what the household NEEDS, so it must not depend on
	 * whether the grid or the battery happens to serve it right now. Every row is
	 * the same 2000 W load, split differently between grid import and battery
	 * discharge; the balance has to report 2000 W in all of them.
	 *
	 * <p>
	 * Bounding the figure by the measured discharge broke exactly this invariant: it
	 * reported 0 W while the grid still covered everything, which pinned the battery
	 * at 0 W behind the meter and promised the heat pump a battery budget that the
	 * household was about to claim.
	 *
	 * @param gridActivePower the grid share of the load in W
	 * @param batteryPower    the battery share of the load in W
	 */
	@ParameterizedTest
	@CsvSource({ "2000, 0", "1500, 500", "1000, 1000", "500, 1500", "0, 2000" })
	public void householdDeficitIsIndependentOfItsSplit(int gridActivePower, int batteryPower) {
		var acCoupled = PowerBalance.of(GRID_SIDE_OF_GRID_METER, gridActivePower, batteryPower, batteryPower, 0);
		assertEquals(2000, acCoupled.householdDeficit());

		// Behind the meter a running heat pump is part of the grid measurement, so it
		// is added on top of the load and has to be removed again.
		var behindMeter = PowerBalance.of(BEHIND_GRID_METER, gridActivePower + 1500, batteryPower, batteryPower, 1500);
		assertEquals(2000, behindMeter.householdDeficit());
	}

	/**
	 * On a HybridEss the PV sits on the DC side, so EssActivePower is the whole
	 * inverter while EssDischargePower stays the battery alone. More PV through that
	 * inverter must therefore raise the PV share and nothing else - booking it as
	 * battery discharge is what made a PV-covered household shrink the battery
	 * budget and let the AC limit throttle usable PV.
	 *
	 * @param pvPower the DC-PV power flowing through the inverter in W
	 */
	@ParameterizedTest
	@ValueSource(ints = { 0, 500, 2600, 5000, 12_000 })
	public void extraPvIsNeverBatteryDischarge(int pvPower) {
		// Battery idle, household 1000 W, the rest of the PV is exported.
		var balance = PowerBalance.of(GRID_SIDE_OF_GRID_METER, 1000 - pvPower, pvPower, 0, 0);

		assertEquals(0, balance.batteryDischarge());
		assertEquals(0, balance.batteryPower());
		assertEquals(pvPower, balance.pvShare());
		// The household is covered by PV as soon as the PV exceeds it; nothing may be
		// reserved from the battery beyond the part the PV does not reach.
		assertEquals(Math.max(0, 1000 - pvPower), balance.householdDeficit());
	}

	/**
	 * The surplus is production minus household, so it must not depend on what the
	 * battery is doing. Every row is the same 5000 W of PV and 1000 W of household,
	 * with the battery charging, idle or discharging; the surplus has to be 4000 W in
	 * all of them, and the household deficit 0.
	 *
	 * <p>
	 * Counting only the discharge broke exactly this invariant, and the Controller
	 * moves the battery itself: a charging battery shrank the surplus, the forced
	 * export pushed it out of charging, the surplus was back, the Controller let go
	 * and Balancing charged again - on 03.10. the two alternated every 15 s and the
	 * slow inverter discharged the battery into the grid in between.
	 *
	 * @param batteryPower the battery power in W (negative = charging)
	 */
	@ParameterizedTest
	@ValueSource(ints = { -3000, -1779, -500, 0, 500, 1779, 3000 })
	public void theSurplusIsIndependentOfTheBatteryState(int batteryPower) {
		// AC-coupled: the PV is outside the ESS, the ESS reports the battery alone.
		var acCoupled = PowerBalance.of(GRID_SIDE_OF_GRID_METER, 1000 - 5000 - batteryPower, batteryPower,
				batteryPower, 0);
		assertEquals(4000, acCoupled.surplusPower());
		assertEquals(0, acCoupled.householdDeficit());

		// HybridEss: the PV flows through the inverter, so EssActivePower carries it.
		var essActivePower = 5000 + batteryPower;
		var hybrid = PowerBalance.of(GRID_SIDE_OF_GRID_METER, 1000 - essActivePower, essActivePower, batteryPower, 0);
		assertEquals(4000, hybrid.surplusPower());
		assertEquals(0, hybrid.householdDeficit());

		// Behind the meter a running 1500 W heat pump is part of the grid reading.
		var behindMeter = PowerBalance.of(BEHIND_GRID_METER, 1000 + 1500 - essActivePower, essActivePower,
				batteryPower, 1500);
		assertEquals(4000, behindMeter.surplusPower());
		assertEquals(0, behindMeter.householdDeficit());
	}

	/**
	 * A battery serving the household is not surplus: with no PV the surplus stays
	 * 0, however much the battery discharges, and the household need is reported in
	 * full.
	 *
	 * @param batteryPower the battery discharge power in W
	 */
	@ParameterizedTest
	@ValueSource(ints = { 0, 1000, 2000 })
	public void aDischargingBatteryIsNeverSurplus(int batteryPower) {
		// 2000 W household, no PV, split between battery and grid.
		var balance = PowerBalance.of(GRID_SIDE_OF_GRID_METER, 2000 - batteryPower, batteryPower, batteryPower, 0);

		assertEquals(0, balance.surplusPower());
		assertEquals(2000, balance.householdDeficit());
	}

	/**
	 * The support budget may never grow when the ESS can do less. Monotonicity is
	 * the property that keeps the coverage checks honest: a weaker ESS has to make
	 * the heat pump look LESS covered, never more.
	 *
	 * @param position the {@link HeatPumpPosition}
	 */
	@ParameterizedTest
	@EnumSource(HeatPumpPosition.class)
	public void lessEssPowerNeverYieldsMoreSupport(HeatPumpPosition position) {
		// A 2000 W household on the grid, battery idle, 2600 W of PV on the DC side.
		var balance = PowerBalance.of(position, 2000, 2600, 0, 0);

		var previous = 0;
		for (var essMaxPower = 0; essMaxPower <= 12_000; essMaxPower += 250) {
			var support = balance.deliverableSupportPower(essMaxPower, 0);
			assertTrue(support >= previous,
					"support must not shrink as the ESS maximum grows: " + essMaxPower + " W -> " + support + " W");
			assertTrue(support <= essMaxPower, "support must never exceed the ESS maximum");
			previous = support;
		}
		// The reserved parts are the PV share the inverter already carries and the
		// household load, so the budget only opens above their sum.
		assertEquals(0, balance.deliverableSupportPower(4600, 0));
		assertEquals(400, balance.deliverableSupportPower(5000, 0));
	}

	/**
	 * The configured cap may only ever reduce the budget.
	 *
	 * @param cap the configured maximum battery support power in W
	 */
	@ParameterizedTest
	@ValueSource(ints = { 0, 1, 500, 3000, 20_000 })
	public void theCapOnlyReducesTheBudget(int cap) {
		var balance = PowerBalance.of(GRID_SIDE_OF_GRID_METER, 2000, 0, 0, 0);
		var uncapped = balance.deliverableSupportPower(10_000, 0);

		var capped = balance.deliverableSupportPower(10_000, cap);

		assertTrue(capped <= uncapped, "the cap must not raise the budget");
		if (cap > 0) {
			assertTrue(capped <= cap, "the budget must respect the cap");
		} else {
			assertEquals(uncapped, capped, "0 means no cap");
		}
	}

	/**
	 * Without released battery energy nothing may be promised, in any topology: the
	 * Controller grants a budget of 0 and every figure derived from it stays 0.
	 */
	@Test
	public void noReleasedEnergyMeansNoSupport() {
		for (var position : HeatPumpPosition.values()) {
			// A heat pump running at 2500 W with no surplus - the situation in which a
			// budget would be spent immediately if one existed.
			var balance = PowerBalance.of(position, 2500, 0, 0, 2500).withMaxSupportPower(0);

			assertEquals(0, balance.maxSupportPower());
			assertEquals(0, Math.min(balance.maxSupportPower(), balance.heatPumpPower()));
			assertEquals(0, Math.min(balance.maxSupportPower(),
					Math.max(0, balance.heatPumpPower() - balance.surplusPower())));
		}
	}

	/**
	 * A negative budget is not a credit: the balance clamps it, so a caller cannot
	 * hand the ESS commands a negative support power.
	 */
	@Test
	public void aNegativeBudgetIsClampedToZero() {
		var balance = PowerBalance.of(GRID_SIDE_OF_GRID_METER, 0, 0, 0, 0).withMaxSupportPower(-1000);

		assertEquals(0, balance.maxSupportPower());
	}

	/**
	 * Behind the meter the heat pump's own consumption must be added back to the
	 * surplus, otherwise a running heat pump eats its own surplus signal and the
	 * boost drops itself. Grid-side it is invisible at the meter and must not be
	 * added.
	 */
	@Test
	public void theHeatPumpDoesNotEatItsOwnSurplusBehindTheMeter() {
		// 4000 W of PV, 1500 W heat pump, no other load: 2500 W are exported.
		var behindMeter = PowerBalance.of(BEHIND_GRID_METER, -2500, 0, 0, 1500);
		assertEquals(4000, behindMeter.surplusPower());

		// Same export, but the heat pump sits upstream of the meter and is fed by it.
		var gridSide = PowerBalance.of(GRID_SIDE_OF_GRID_METER, -2500, 0, 0, 1500);
		assertEquals(2500, gridSide.surplusPower());
	}
}
