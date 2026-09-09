package io.openems.edge.controller.heatpump.shi;

import static io.openems.edge.controller.heatpump.shi.BatteryReserveCalculator.REQUIRED_FORECAST_QUARTERS;
import static io.openems.edge.controller.heatpump.shi.NightReserveMode.MAX_DEFICIT;
import static io.openems.edge.controller.heatpump.shi.NightReserveMode.SOC_TRAJECTORY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.function.IntUnaryOperator;

import org.junit.jupiter.api.Test;

import io.openems.edge.controller.heatpump.shi.BatteryReserveCalculator.Reason;

/**
 * Direct tests of the night-reserve policy. Unlike the scenario tests in
 * {@link ControllerShiHeatPumpImplTest} these need no component harness at all -
 * the calculator takes plain maps and returns a result - which is the point of
 * having it separate from the Controller's channel and predictor plumbing.
 */
public class BatteryReserveCalculatorTest {

	private static final Instant NOW = Instant.parse("2026-09-09T06:00:00Z");
	private static final int CAPACITY = 10_000;
	private static final int MIN_SOC = 15;
	/** A quarter is fully ahead of us, for clean arithmetic. */
	private static final float FULL_QUARTER = 0.25F;

	private static BatteryReserveCalculator calculator(NightReserveMode mode, int buffer) {
		// Lossless and with ample charge headroom: these tests are about the reserve
		// policy, not about the charge model, which has its own tests.
		return new BatteryReserveCalculator(mode, MIN_SOC, buffer, 5000, 1F, 1F);
	}

	private static Map<Instant, Integer> series(IntUnaryOperator valuePerQuarter) {
		var map = new HashMap<Instant, Integer>();
		for (var i = 0; i < REQUIRED_FORECAST_QUARTERS; i++) {
			var value = valuePerQuarter.applyAsInt(i);
			if (value >= 0) {
				map.put(NOW.plus(i * 15L, ChronoUnit.MINUTES), value);
			}
		}
		return map;
	}

	private static Map<Instant, Integer> flat(int value) {
		return series(i -> value);
	}

	private static BatteryReserveCalculator.Result calculate(BatteryReserveCalculator calculator, int soc,
			Map<Instant, Integer> productions, Map<Instant, Integer> unmanagedConsumptions,
			Map<Instant, Integer> totalConsumptions) {
		return calculator.calculate(calculator.usableEnergy(soc, CAPACITY), CAPACITY, NOW, FULL_QUARTER, productions,
				unmanagedConsumptions, totalConsumptions, Map.of());
	}

	@Test
	public void anEmptyBatteryIsNotAForecastProblem() {
		var calculator = calculator(MAX_DEFICIT, 100);

		// At or below Min-SoC there is nothing to release ...
		assertEquals(0, calculator.usableEnergy(MIN_SOC, CAPACITY));
		assertEquals(0, calculator.usableEnergy(5, CAPACITY));
		// ... and without a battery there is nothing either.
		assertEquals(0, calculator.usableEnergy(80, 0));

		var result = calculate(calculator, 10, flat(0), flat(500), flat(500));

		assertEquals(Reason.NO_USABLE_ENERGY, result.reason());
		assertEquals(0, result.spareEnergy());
		assertNull(result.reserveEnergy(), "no reserve can be stated");
		// The Controller drives its forecast warning off this flag, so an empty battery
		// must not light it up.
		assertFalse(result.reason().isForecastProblem());
	}

	@Test
	public void aGapInBothConsumptionChannelsReleasesNothing() {
		// Quarter 10 is missing in the Unmanaged AND in the plain forecast, so the
		// night cannot be promised gap-free.
		var withGap = series(i -> i == 10 ? -1 : 500);

		var result = calculate(calculator(MAX_DEFICIT, 100), 65, flat(0), withGap, withGap);

		assertEquals(Reason.FORECAST_INCOMPLETE, result.reason());
		assertEquals(0, result.spareEnergy());
		assertNull(result.reserveEnergy());
		assertTrue(result.reason().isForecastProblem());
	}

	@Test
	public void aForecastShorterThanTheHorizonReleasesNothing() {
		// Half a day of forecast is not enough to promise the night: releasing on it
		// would be exactly the optimistic mistake the horizon check prevents.
		var tooShort = series(i -> i < 48 ? 500 : -1);

		var result = calculate(calculator(MAX_DEFICIT, 100), 65, flat(0), tooShort, tooShort);

		assertEquals(Reason.FORECAST_INCOMPLETE, result.reason());
		assertEquals(0, result.spareEnergy());
	}

	@Test
	public void aMissingUnmanagedQuarterFallsBackToTheTotalForecast() {
		// The Unmanaged channel has no value for quarter 10; the plain channel does.
		// It includes managed consumers and is therefore never too small - the
		// substitution is conservative.
		var unmanaged = series(i -> i == 10 ? -1 : 0);
		var total = series(i -> i == 10 ? 4000 : 0);

		var result = calculate(calculator(MAX_DEFICIT, 120), 65, flat(0), unmanaged, total);

		assertEquals(Reason.OK, result.reason());
		// 4000 W over a quarter = 1000 Wh, x 120 % buffer = 1200 Wh.
		assertEquals(Integer.valueOf(1200), result.reserveEnergy());
		assertEquals(5000 - 1200, result.spareEnergy());
	}

	/**
	 * Morning-recovery forecast: 12 h of PV well above the load, then a 6 h evening
	 * block and a quiet late night. The overnight deficit far exceeds a low morning
	 * SoC, so MAX_DEFICIT reserves everything - while the daytime PV refills the
	 * battery long before the evening, which is exactly what SOC_TRAJECTORY is for.
	 */
	@Test
	public void theTrajectoryCreditsTheDaytimeRechargeAndMaxDeficitDoesNot() {
		var productions = series(i -> i < 48 ? 2000 : 0);
		var consumptions = series(i -> i < 48 ? 500 : i < 72 ? 1200 : 200);

		// 25 % SoC -> 1000 Wh usable above the 15 % Min-SoC.
		var maxDeficit = calculate(calculator(MAX_DEFICIT, 100), 25, productions, consumptions, consumptions);
		final var trajectory = calculate(calculator(SOC_TRAJECTORY, 100), 25, productions, consumptions,
				consumptions);

		assertEquals(Reason.OK, maxDeficit.reason());
		// 24 quarters x 300 Wh + 24 quarters x 50 Wh of deficit.
		assertEquals(Integer.valueOf(8400), maxDeficit.reserveEnergy());
		assertEquals(0, maxDeficit.spareEnergy(), "the morning stays blocked");

		assertEquals(Reason.OK, trajectory.reason());
		assertEquals(Integer.valueOf(0), trajectory.reserveEnergy());
		assertEquals(1000, trajectory.spareEnergy(), "the forecast recharge frees the morning");
	}

	@Test
	public void theTrajectoryBufferBecomesACushionTheForecastMustLeave() {
		var productions = series(i -> i < 48 ? 2000 : 0);
		var consumptions = series(i -> i < 48 ? 500 : i < 72 ? 1200 : 200);

		// Same razor-thin forecast, but now a 120 % buffer. It ends near Min-SoC, so it
		// does not leave the cushion on top and nothing is freed - the safety margin
		// that must exist when the release relies on a future recharge.
		var result = calculate(calculator(SOC_TRAJECTORY, 120), 25, productions, consumptions, consumptions);

		assertEquals(Reason.OK, result.reason());
		assertEquals(0, result.spareEnergy());
	}

	@Test
	public void aSunnyForecastNeedsNoReserve() {
		var result = calculate(calculator(MAX_DEFICIT, 120), 65, flat(5000), flat(500), flat(500));

		assertEquals(Reason.OK, result.reason());
		assertEquals(Integer.valueOf(0), result.reserveEnergy());
		assertEquals(5000, result.spareEnergy(), "all usable energy is free");
	}

	@Test
	public void theHeatPumpForecastIsRemovedFromTheHouseholdLoad() {
		var calculator = calculator(MAX_DEFICIT, 100);
		// A single quarter of load, 4000 W of it, 3000 W of which is the heat pump:
		// only the 1000 W household remainder belongs into the reserve.
		var consumptions = series(i -> i == 10 ? 4000 : 0);
		var heatPumps = series(i -> i == 10 ? 3000 : 0);

		var withHeatPump = calculator.calculate(calculator.usableEnergy(65, CAPACITY), CAPACITY, NOW, FULL_QUARTER,
				flat(0), consumptions, consumptions, heatPumps);
		var withoutHeatPump = calculate(calculator, 65, flat(0), consumptions, consumptions);

		assertEquals(Integer.valueOf(250), withHeatPump.reserveEnergy());
		assertEquals(Integer.valueOf(1000), withoutHeatPump.reserveEnergy());
	}
}
