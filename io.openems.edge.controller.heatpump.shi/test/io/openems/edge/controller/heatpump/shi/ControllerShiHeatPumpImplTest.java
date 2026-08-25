package io.openems.edge.controller.heatpump.shi;

import static io.openems.common.test.TestUtils.createDummyClock;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.common.test.DummyConfigurationAdmin;
import io.openems.common.types.ChannelAddress;
import io.openems.common.types.MeterType;
import io.openems.edge.common.sum.DummySum;
import io.openems.edge.common.sum.Sum;
import io.openems.edge.common.test.AbstractComponentTest.TestCase;
import io.openems.edge.common.test.DummyComponentManager;
import io.openems.edge.controller.test.ControllerTest;
import io.openems.edge.ess.api.ManagedSymmetricEss;
import io.openems.edge.ess.test.DummyManagedSymmetricEss;
import io.openems.edge.ess.test.DummyPower;
import io.openems.edge.heat.shi.HeatShiHeatPump;
import io.openems.edge.heat.shi.test.DummyHeatShiHeatPump;
import io.openems.edge.meter.api.ElectricityMeter;
import io.openems.edge.predictor.api.prediction.Prediction;
import io.openems.edge.predictor.api.test.DummyPredictor;
import io.openems.edge.predictor.api.test.DummyPredictorManager;

class ControllerShiHeatPumpImplTest {

	private static final ChannelAddress SUM_PRODUCTION_ACTIVE_POWER = new ChannelAddress("_sum",
			"ProductionActivePower");
	private static final ChannelAddress SUM_CONSUMPTION_ACTIVE_POWER = new ChannelAddress("_sum",
			"ConsumptionActivePower");
	private static final ChannelAddress SUM_UNMANAGED_PRODUCTION_ACTIVE_POWER = new ChannelAddress("_sum",
			"UnmanagedProductionActivePower");
	private static final ChannelAddress SUM_UNMANAGED_CONSUMPTION_ACTIVE_POWER = new ChannelAddress("_sum",
			"UnmanagedConsumptionActivePower");

	/**
	 * Predictor with a flat production/consumption forecast on the plain '_sum'
	 * channels.
	 *
	 * @param cm          the {@link DummyComponentManager}
	 * @param sum         the {@link DummySum}
	 * @param now         the current time
	 * @param production  flat production forecast in W
	 * @param consumption flat consumption forecast in W
	 * @return the {@link DummyPredictorManager}
	 * @throws OpenemsNamedException on error
	 */
	private static DummyPredictorManager flatPredictor(DummyComponentManager cm, DummySum sum, Instant now,
			int production, int consumption) throws OpenemsNamedException {
		var prod = new Integer[96];
		var cons = new Integer[96];
		Arrays.fill(prod, production);
		Arrays.fill(cons, consumption);
		return new DummyPredictorManager(//
				new DummyPredictor("predictor0", cm, Prediction.from(sum, SUM_PRODUCTION_ACTIVE_POWER, now, prod),
						SUM_PRODUCTION_ACTIVE_POWER),
				new DummyPredictor("predictor1", cm, Prediction.from(sum, SUM_CONSUMPTION_ACTIVE_POWER, now, cons),
						SUM_CONSUMPTION_ACTIVE_POWER));
	}

	/**
	 * Sunny forecast (production far above consumption) so the night reserve is 0
	 * and all usable battery energy above Min-SoC is free.
	 *
	 * @param cm  the {@link DummyComponentManager}
	 * @param sum the {@link DummySum}
	 * @param now the current time
	 * @return the {@link DummyPredictorManager}
	 * @throws OpenemsNamedException on error
	 */
	private static DummyPredictorManager sunnyPredictor(DummyComponentManager cm, DummySum sum, Instant now)
			throws OpenemsNamedException {
		return flatPredictor(cm, sum, now, 5000, 500);
	}

	/**
	 * Morning-recovery forecast: 12 h of PV (2000 W) then none, low daytime load,
	 * a 6 h evening block (1200 W) and a quiet late night (200 W). The overnight
	 * deficit exceeds a low morning SoC, so MAX_DEFICIT reserves everything, while
	 * the daytime PV refills the battery, so SOC_TRAJECTORY frees the morning
	 * energy.
	 *
	 * @param cm  the {@link DummyComponentManager}
	 * @param sum the {@link DummySum}
	 * @param now the start of the forecast
	 * @return a {@link DummyPredictorManager} with the described forecast
	 */
	private static DummyPredictorManager morningRecoveryPredictor(DummyComponentManager cm, DummySum sum, Instant now)
			throws OpenemsNamedException {
		var prod = new Integer[96];
		var cons = new Integer[96];
		for (var i = 0; i < 96; i++) {
			prod[i] = i < 48 ? 2000 : 0;
			cons[i] = i < 48 ? 500 : i < 72 ? 1200 : 200;
		}
		return new DummyPredictorManager(//
				new DummyPredictor("predictor0", cm, Prediction.from(sum, SUM_PRODUCTION_ACTIVE_POWER, now, prod),
						SUM_PRODUCTION_ACTIVE_POWER),
				new DummyPredictor("predictor1", cm, Prediction.from(sum, SUM_CONSUMPTION_ACTIVE_POWER, now, cons),
						SUM_CONSUMPTION_ACTIVE_POWER));
	}

	/**
	 * Predictor with separate Unmanaged and total (plain) consumption forecasts, so
	 * the per-quarter fallback in the reserve calc can be exercised. Any array entry
	 * may be null to model a gap.
	 *
	 * @param cm                   the {@link DummyComponentManager}
	 * @param sum                  the {@link DummySum}
	 * @param now                  the current time
	 * @param production           production forecast (plain channel)
	 * @param unmanagedConsumption Unmanaged-consumption forecast (may contain nulls)
	 * @param totalConsumption     plain consumption forecast (may contain nulls)
	 * @return the {@link DummyPredictorManager}
	 * @throws OpenemsNamedException on error
	 */
	private static DummyPredictorManager consumptionSplitPredictor(DummyComponentManager cm, DummySum sum, Instant now,
			Integer[] production, Integer[] unmanagedConsumption, Integer[] totalConsumption)
			throws OpenemsNamedException {
		return new DummyPredictorManager(//
				new DummyPredictor("predictor0", cm, Prediction.from(sum, SUM_PRODUCTION_ACTIVE_POWER, now, production),
						SUM_PRODUCTION_ACTIVE_POWER),
				new DummyPredictor("predictor1", cm,
						Prediction.from(sum, SUM_UNMANAGED_CONSUMPTION_ACTIVE_POWER, now, unmanagedConsumption),
						SUM_UNMANAGED_CONSUMPTION_ACTIVE_POWER),
				new DummyPredictor("predictor2", cm,
						Prediction.from(sum, SUM_CONSUMPTION_ACTIVE_POWER, now, totalConsumption),
						SUM_CONSUMPTION_ACTIVE_POWER));
	}

	@Test
	void testElevatedModeOnGridExportWithHysteresis() throws Exception {
		var clock = createDummyClock();
		var cm = new DummyComponentManager(clock);
		var sum = new DummySum();
		new ControllerTest(new ControllerShiHeatPumpImpl()) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("componentManager", cm) //
				.addReference("sum", sum) //
				.addReference("predictorManager", sunnyPredictor(cm, sum, Instant.now(clock))) //
				.addReference("heatPump", new DummyHeatShiHeatPump("heatPump0")) //
				.addComponent(new DummyManagedSymmetricEss("ess0") //
						.setPower(new DummyPower(10_000))) //
				.activate(MyConfig.create() //
						.setId("ctrl0") //
						.setHeatPumpId("heatPump0") //
						.setEssId("ess0") //
						.setHeatPumpPosition(HeatPumpPosition.GRID_SIDE_OF_GRID_METER) //
						.setBatterySupportMode(BatterySupportMode.CLOUD_BUFFER) //
						.build()) //
				// CLOUD_BUFFER: the boost follows the sun alone. Strong export ->
				// elevated mode; once the export is gone the sun no longer covers the
				// heat pump and the boost is dropped after the hysteresis expires.
				.next(new TestCase("Grid export above minimum: elevated") //
						.input("_sum", Sum.ChannelId.GRID_ACTIVE_POWER, -4000) //
						.input("_sum", Sum.ChannelId.ESS_DISCHARGE_POWER, 0) //
						.input("_sum", Sum.ChannelId.ESS_SOC, 65) //
						.input("_sum", Sum.ChannelId.ESS_CAPACITY, 10_000) //
						.input("heatPump0", ElectricityMeter.ChannelId.ACTIVE_POWER, 500) //
						.output("heatPump0", HeatShiHeatPump.ChannelId.HEATING_MODE, 1) //
						.output("heatPump0", HeatShiHeatPump.ChannelId.HEATING_SETPOINT, 550) //
						.output("heatPump0", HeatShiHeatPump.ChannelId.HOT_WATER_MODE, 1) //
						.output("heatPump0", HeatShiHeatPump.ChannelId.HOT_WATER_SETPOINT, 550) //
						.output("heatPump0", HeatShiHeatPump.ChannelId.LPC_MODE, 1) //
						.output(ControllerShiHeatPump.ChannelId.ELEVATED_MODE_ACTIVE, true)) //
				.next(new TestCase("Export gone: hysteresis keeps elevated mode") //
						.input("_sum", Sum.ChannelId.GRID_ACTIVE_POWER, 0) //
						.input("_sum", Sum.ChannelId.ESS_DISCHARGE_POWER, 1000) //
						.input("_sum", Sum.ChannelId.ESS_SOC, 65) //
						.input("_sum", Sum.ChannelId.ESS_CAPACITY, 10_000) //
						.input("heatPump0", ElectricityMeter.ChannelId.ACTIVE_POWER, 500) //
						.output(ControllerShiHeatPump.ChannelId.ELEVATED_MODE_ACTIVE, true)) //
				.next(new TestCase("Hysteresis expired: back to normal mode") //
						.timeleap(clock, 6, ChronoUnit.MINUTES) //
						.input("_sum", Sum.ChannelId.GRID_ACTIVE_POWER, 0) //
						.input("_sum", Sum.ChannelId.ESS_DISCHARGE_POWER, 1000) //
						.input("_sum", Sum.ChannelId.ESS_SOC, 65) //
						.input("_sum", Sum.ChannelId.ESS_CAPACITY, 10_000) //
						.input("heatPump0", ElectricityMeter.ChannelId.ACTIVE_POWER, 500) //
						.output("heatPump0", HeatShiHeatPump.ChannelId.HEATING_MODE, 0) //
						.output(ControllerShiHeatPump.ChannelId.ELEVATED_MODE_ACTIVE, false)) //
				.deactivate();
	}

	@Test
	void testSwitchOffDelayBridgesShortDipThenDrops() throws Exception {
		var clock = createDummyClock();
		var cm = new DummyComponentManager(clock);
		var sum = new DummySum();
		new ControllerTest(new ControllerShiHeatPumpImpl()) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("componentManager", cm) //
				.addReference("sum", sum) //
				.addReference("predictorManager", sunnyPredictor(cm, sum, Instant.now(clock))) //
				.addReference("heatPump", new DummyHeatShiHeatPump("heatPump0")) //
				.addComponent(new DummyManagedSymmetricEss("ess0") //
						.setPower(new DummyPower(10_000))) //
				.activate(MyConfig.create() //
						.setId("ctrl0") //
						.setHeatPumpId("heatPump0") //
						.setEssId("ess0") //
						.setHeatPumpPosition(HeatPumpPosition.GRID_SIDE_OF_GRID_METER) //
						.setBatterySupportMode(BatterySupportMode.CLOUD_BUFFER) //
						.setMinimumSwitchingTime(1) // negligible commit window
						.setSwitchOffDelay(20) //
						.build()) //
				// Uncovered time is accumulated in steps <= the 10 s max-credited time,
				// so the budget is exercised realistically (not one big jump).
				.next(new TestCase("Enter elevated on strong sun") //
						.input("_sum", Sum.ChannelId.GRID_ACTIVE_POWER, -4000) //
						.input("_sum", Sum.ChannelId.ESS_DISCHARGE_POWER, 0) //
						.input("_sum", Sum.ChannelId.ESS_SOC, 65) //
						.input("_sum", Sum.ChannelId.ESS_CAPACITY, 10_000) //
						.input("heatPump0", ElectricityMeter.ChannelId.ACTIVE_POWER, 500) //
						.output(ControllerShiHeatPump.ChannelId.ELEVATED_MODE_ACTIVE, true)) //
				// 10 s cloud -> budget 10 s (< 20 s) -> bridged.
				.next(new TestCase("10 s cloud: bridged") //
						.timeleap(clock, 10, ChronoUnit.SECONDS) //
						.input("_sum", Sum.ChannelId.GRID_ACTIVE_POWER, 0) //
						.input("_sum", Sum.ChannelId.ESS_DISCHARGE_POWER, 0) //
						.input("_sum", Sum.ChannelId.ESS_SOC, 65) //
						.input("_sum", Sum.ChannelId.ESS_CAPACITY, 10_000) //
						.input("heatPump0", ElectricityMeter.ChannelId.ACTIVE_POWER, 500) //
						.output(ControllerShiHeatPump.ChannelId.ELEVATED_MODE_ACTIVE, true)) //
				// 5 s sun -> budget decays 5 s x 2 = 10 s back to 0 -> timer cleared.
				.next(new TestCase("Sun returns: budget decays away") //
						.timeleap(clock, 5, ChronoUnit.SECONDS) //
						.input("_sum", Sum.ChannelId.GRID_ACTIVE_POWER, -4000) //
						.input("_sum", Sum.ChannelId.ESS_DISCHARGE_POWER, 0) //
						.input("_sum", Sum.ChannelId.ESS_SOC, 65) //
						.input("_sum", Sum.ChannelId.ESS_CAPACITY, 10_000) //
						.input("heatPump0", ElectricityMeter.ChannelId.ACTIVE_POWER, 500) //
						.output(ControllerShiHeatPump.ChannelId.ELEVATED_MODE_ACTIVE, true)) //
				// Sustained cloud: 10 s + 10 s = 20 s -> reaches the delay -> drop.
				.next(new TestCase("10 s cloud again: building") //
						.timeleap(clock, 10, ChronoUnit.SECONDS) //
						.input("_sum", Sum.ChannelId.GRID_ACTIVE_POWER, 0) //
						.input("_sum", Sum.ChannelId.ESS_DISCHARGE_POWER, 0) //
						.input("_sum", Sum.ChannelId.ESS_SOC, 65) //
						.input("_sum", Sum.ChannelId.ESS_CAPACITY, 10_000) //
						.input("heatPump0", ElectricityMeter.ChannelId.ACTIVE_POWER, 500) //
						.output(ControllerShiHeatPump.ChannelId.ELEVATED_MODE_ACTIVE, true)) //
				.next(new TestCase("10 s more cloud: delay reached -> dropped") //
						.timeleap(clock, 10, ChronoUnit.SECONDS) //
						.input("_sum", Sum.ChannelId.GRID_ACTIVE_POWER, 0) //
						.input("_sum", Sum.ChannelId.ESS_DISCHARGE_POWER, 0) //
						.input("_sum", Sum.ChannelId.ESS_SOC, 65) //
						.input("_sum", Sum.ChannelId.ESS_CAPACITY, 10_000) //
						.input("heatPump0", ElectricityMeter.ChannelId.ACTIVE_POWER, 500) //
						.output(ControllerShiHeatPump.ChannelId.ELEVATED_MODE_ACTIVE, false)) //
				.deactivate();
	}

	@Test
	void testOffensiveHoldsOnBatteryThroughSunLoss() throws Exception {
		var clock = createDummyClock();
		var cm = new DummyComponentManager(clock);
		var sum = new DummySum();
		new ControllerTest(new ControllerShiHeatPumpImpl()) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("componentManager", cm) //
				.addReference("sum", sum) //
				.addReference("predictorManager", sunnyPredictor(cm, sum, Instant.now(clock))) //
				.addReference("heatPump", new DummyHeatShiHeatPump("heatPump0")) //
				.addComponent(new DummyManagedSymmetricEss("ess0") //
						.setPower(new DummyPower(10_000))) //
				.activate(MyConfig.create() //
						.setId("ctrl0") //
						.setHeatPumpId("heatPump0") //
						.setEssId("ess0") //
						.setHeatPumpPosition(HeatPumpPosition.GRID_SIDE_OF_GRID_METER) //
						.setBatterySupportMode(BatterySupportMode.OFFENSIVE) //
						.build()) //
				.next(new TestCase("Enter elevated on strong sun") //
						.input("_sum", Sum.ChannelId.GRID_ACTIVE_POWER, -4000) //
						.input("_sum", Sum.ChannelId.ESS_DISCHARGE_POWER, 0) //
						.input("_sum", Sum.ChannelId.ESS_SOC, 65) //
						.input("_sum", Sum.ChannelId.ESS_CAPACITY, 10_000) //
						.input("heatPump0", ElectricityMeter.ChannelId.ACTIVE_POWER, 500) //
						.output(ControllerShiHeatPump.ChannelId.ELEVATED_MODE_ACTIVE, true)) //
				// Sun gone, but OFFENSIVE + free battery energy covers the heat pump ->
				// the boost is held on the battery even after the commit window expired.
				.next(new TestCase("Sun gone, battery covers: held") //
						.timeleap(clock, 6, ChronoUnit.MINUTES) //
						.input("_sum", Sum.ChannelId.GRID_ACTIVE_POWER, 0) //
						.input("_sum", Sum.ChannelId.ESS_DISCHARGE_POWER, 0) //
						.input("_sum", Sum.ChannelId.ESS_SOC, 65) //
						.input("_sum", Sum.ChannelId.ESS_CAPACITY, 10_000) //
						.input("heatPump0", ElectricityMeter.ChannelId.ACTIVE_POWER, 500) //
						.output(ControllerShiHeatPump.ChannelId.ELEVATED_MODE_ACTIVE, true)) //
				// Battery drained to Min-SoC -> no free energy -> nothing covers the heat
				// pump anymore -> boost dropped (switch-off delay 0 in test config).
				.next(new TestCase("Battery at Min-SoC: no coverage, dropped") //
						.input("_sum", Sum.ChannelId.GRID_ACTIVE_POWER, 0) //
						.input("_sum", Sum.ChannelId.ESS_DISCHARGE_POWER, 0) //
						.input("_sum", Sum.ChannelId.ESS_SOC, 15) //
						.input("_sum", Sum.ChannelId.ESS_CAPACITY, 10_000) //
						.input("heatPump0", ElectricityMeter.ChannelId.ACTIVE_POWER, 500) //
						.output(ControllerShiHeatPump.ChannelId.ELEVATED_MODE_ACTIVE, false)) //
				.deactivate();
	}

	@Test
	void testSwitchOffBudgetNotWipedByBriefCoverage() throws Exception {
		var clock = createDummyClock();
		var cm = new DummyComponentManager(clock);
		var sum = new DummySum();
		new ControllerTest(new ControllerShiHeatPumpImpl()) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("componentManager", cm) //
				.addReference("sum", sum) //
				.addReference("predictorManager", sunnyPredictor(cm, sum, Instant.now(clock))) //
				.addReference("heatPump", new DummyHeatShiHeatPump("heatPump0")) //
				.addComponent(new DummyManagedSymmetricEss("ess0") //
						.setPower(new DummyPower(10_000))) //
				.activate(MyConfig.create() //
						.setId("ctrl0") //
						.setHeatPumpId("heatPump0") //
						.setEssId("ess0") //
						.setHeatPumpPosition(HeatPumpPosition.GRID_SIDE_OF_GRID_METER) //
						.setBatterySupportMode(BatterySupportMode.CLOUD_BUFFER) //
						.setMinimumSwitchingTime(1) // negligible commit window
						.setSwitchOffDelay(20) //
						.build()) //
				.next(new TestCase("Enter elevated on strong sun") //
						.input("_sum", Sum.ChannelId.GRID_ACTIVE_POWER, -4000) //
						.input("_sum", Sum.ChannelId.ESS_DISCHARGE_POWER, 0) //
						.input("_sum", Sum.ChannelId.ESS_SOC, 65) //
						.input("_sum", Sum.ChannelId.ESS_CAPACITY, 10_000) //
						.input("heatPump0", ElectricityMeter.ChannelId.ACTIVE_POWER, 500) //
						.output(ControllerShiHeatPump.ChannelId.ELEVATED_MODE_ACTIVE, true)) //
				// 10 s + 8 s uncovered -> budget 18 s (< 20 s) -> still elevated.
				.next(new TestCase("10 s cloud: budget 10 s") //
						.timeleap(clock, 10, ChronoUnit.SECONDS) //
						.input("_sum", Sum.ChannelId.GRID_ACTIVE_POWER, 0) //
						.input("_sum", Sum.ChannelId.ESS_DISCHARGE_POWER, 0) //
						.input("_sum", Sum.ChannelId.ESS_SOC, 65) //
						.input("_sum", Sum.ChannelId.ESS_CAPACITY, 10_000) //
						.input("heatPump0", ElectricityMeter.ChannelId.ACTIVE_POWER, 500) //
						.output(ControllerShiHeatPump.ChannelId.ELEVATED_MODE_ACTIVE, true)) //
				.next(new TestCase("8 s more cloud: budget 18 s") //
						.timeleap(clock, 8, ChronoUnit.SECONDS) //
						.input("_sum", Sum.ChannelId.GRID_ACTIVE_POWER, 0) //
						.input("_sum", Sum.ChannelId.ESS_DISCHARGE_POWER, 0) //
						.input("_sum", Sum.ChannelId.ESS_SOC, 65) //
						.input("_sum", Sum.ChannelId.ESS_CAPACITY, 10_000) //
						.input("heatPump0", ElectricityMeter.ChannelId.ACTIVE_POWER, 500) //
						.output(ControllerShiHeatPump.ChannelId.ELEVATED_MODE_ACTIVE, true)) //
				// 2 s clearly covered -> budget decays only 2 s x 2 = 4 s (to 14 s),
				// it is NOT reset to zero.
				.next(new TestCase("2 s sun blip: small decay, not wiped") //
						.timeleap(clock, 2, ChronoUnit.SECONDS) //
						.input("_sum", Sum.ChannelId.GRID_ACTIVE_POWER, -4000) //
						.input("_sum", Sum.ChannelId.ESS_DISCHARGE_POWER, 0) //
						.input("_sum", Sum.ChannelId.ESS_SOC, 65) //
						.input("_sum", Sum.ChannelId.ESS_CAPACITY, 10_000) //
						.input("heatPump0", ElectricityMeter.ChannelId.ACTIVE_POWER, 500) //
						.output(ControllerShiHeatPump.ChannelId.ELEVATED_MODE_ACTIVE, true)) //
				// 8 s cloud -> 14 + 8 = 22 s >= 20 s -> drop. Had the blip reset the
				// budget, 8 s alone would not have reached the delay.
				.next(new TestCase("8 s more cloud: budget reaches delay -> dropped") //
						.timeleap(clock, 8, ChronoUnit.SECONDS) //
						.input("_sum", Sum.ChannelId.GRID_ACTIVE_POWER, 0) //
						.input("_sum", Sum.ChannelId.ESS_DISCHARGE_POWER, 0) //
						.input("_sum", Sum.ChannelId.ESS_SOC, 65) //
						.input("_sum", Sum.ChannelId.ESS_CAPACITY, 10_000) //
						.input("heatPump0", ElectricityMeter.ChannelId.ACTIVE_POWER, 500) //
						.output(ControllerShiHeatPump.ChannelId.ELEVATED_MODE_ACTIVE, false)) //
				.deactivate();
	}

	@Test
	void testBoostStartsOnSunWithoutFreeEnergy() throws Exception {
		var clock = createDummyClock();
		new ControllerTest(new ControllerShiHeatPumpImpl()) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("componentManager", new DummyComponentManager(clock)) //
				.addReference("sum", new DummySum()) //
				.addReference("heatPump", new DummyHeatShiHeatPump("heatPump0")) //
				.addComponent(new DummyManagedSymmetricEss("ess0") //
						.setPower(new DummyPower(10_000))) //
				.activate(MyConfig.create() //
						.setId("ctrl0") //
						.setHeatPumpId("heatPump0") //
						.setEssId("ess0") //
						.setHeatPumpPosition(HeatPumpPosition.GRID_SIDE_OF_GRID_METER) //
						.build()) //
				// Strong export but no prediction -> free energy 0. Running the heat pump
				// on surplus is the base case, so a strong surplus must still start a
				// boost - the battery is a bonus, not an entry precondition. No free
				// energy just means no battery support (forced export stays 0).
				.next(new TestCase("Strong export, no free battery energy: boost on sun alone") //
						.input("_sum", Sum.ChannelId.GRID_ACTIVE_POWER, -4000) //
						.input("_sum", Sum.ChannelId.ESS_DISCHARGE_POWER, 0) //
						.input("heatPump0", ElectricityMeter.ChannelId.ACTIVE_POWER, 0) //
						.output(ControllerShiHeatPump.ChannelId.ELEVATED_MODE_ACTIVE, true) //
						.output(ControllerShiHeatPump.ChannelId.FREE_BATTERY_ENERGY, 0) //
						.output(ControllerShiHeatPump.ChannelId.ESS_FORCED_EXPORT_POWER, 0)) //
				.deactivate();
	}

	@Test
	void testNoBridgeBelowMinimumSurplus() throws Exception {
		var clock = createDummyClock();
		var cm = new DummyComponentManager(clock);
		var sum = new DummySum();
		new ControllerTest(new ControllerShiHeatPumpImpl()) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("componentManager", cm) //
				.addReference("sum", sum) //
				.addReference("predictorManager", sunnyPredictor(cm, sum, Instant.now(clock))) //
				.addReference("heatPump", new DummyHeatShiHeatPump("heatPump0")) //
				.addComponent(new DummyManagedSymmetricEss("ess0") //
						.setPower(new DummyPower(10_000))) //
				.activate(MyConfig.create() //
						.setId("ctrl0") //
						.setHeatPumpId("heatPump0") //
						.setEssId("ess0") //
						.setHeatPumpPosition(HeatPumpPosition.GRID_SIDE_OF_GRID_METER) //
						.setMinimumSurplusPowerForElevatedMode(2000) //
						.build()) //
				// Export 1500 W below the minimum of 2000 W. Plenty of free battery
				// energy, but the bridge was removed: the battery must not push a weak
				// surplus over the threshold -> stays normal.
				.next(new TestCase("Surplus below minimum, no bridge: stays normal") //
						.input("_sum", Sum.ChannelId.GRID_ACTIVE_POWER, -1500) //
						.input("_sum", Sum.ChannelId.ESS_DISCHARGE_POWER, 0) //
						.input("_sum", Sum.ChannelId.ESS_SOC, 65) //
						.input("_sum", Sum.ChannelId.ESS_CAPACITY, 10_000) //
						.input("heatPump0", ElectricityMeter.ChannelId.ACTIVE_POWER, 500) //
						.output(ControllerShiHeatPump.ChannelId.ELEVATED_MODE_ACTIVE, false)) //
				.deactivate();
	}

	@Test
	void testBoostHeldOnSunAfterEnergyDrained() throws Exception {
		var clock = createDummyClock();
		var cm = new DummyComponentManager(clock);
		var sum = new DummySum();
		new ControllerTest(new ControllerShiHeatPumpImpl()) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("componentManager", cm) //
				.addReference("sum", sum) //
				.addReference("predictorManager", sunnyPredictor(cm, sum, Instant.now(clock))) //
				.addReference("heatPump", new DummyHeatShiHeatPump("heatPump0")) //
				.addComponent(new DummyManagedSymmetricEss("ess0") //
						.setPower(new DummyPower(10_000))) //
				.activate(MyConfig.create() //
						.setId("ctrl0") //
						.setHeatPumpId("heatPump0") //
						.setEssId("ess0") //
						.setHeatPumpPosition(HeatPumpPosition.GRID_SIDE_OF_GRID_METER) //
						.build()) //
				.next(new TestCase("Start: strong sun and free energy -> boost") //
						.input("_sum", Sum.ChannelId.GRID_ACTIVE_POWER, -4000) //
						.input("_sum", Sum.ChannelId.ESS_DISCHARGE_POWER, 0) //
						.input("_sum", Sum.ChannelId.ESS_SOC, 65) //
						.input("_sum", Sum.ChannelId.ESS_CAPACITY, 10_000) //
						.input("heatPump0", ElectricityMeter.ChannelId.ACTIVE_POWER, 0) //
						.output(ControllerShiHeatPump.ChannelId.ELEVATED_MODE_ACTIVE, true)) //
				// Battery drained to Min-SoC -> free energy 0, but sun still strong ->
				// boost held (free energy only required to START)
				.next(new TestCase("Energy drained but sun holds: boost stays") //
						.input("_sum", Sum.ChannelId.GRID_ACTIVE_POWER, -4000) //
						.input("_sum", Sum.ChannelId.ESS_DISCHARGE_POWER, 0) //
						.input("_sum", Sum.ChannelId.ESS_SOC, 15) //
						.input("_sum", Sum.ChannelId.ESS_CAPACITY, 10_000) //
						.input("heatPump0", ElectricityMeter.ChannelId.ACTIVE_POWER, 0) //
						.output(ControllerShiHeatPump.ChannelId.ELEVATED_MODE_ACTIVE, true) //
						.output(ControllerShiHeatPump.ChannelId.FREE_BATTERY_ENERGY, 0)) //
				.deactivate();
	}

	@Test
	void testBoostConfirmationDelaysEntry() throws Exception {
		var clock = createDummyClock();
		var cm = new DummyComponentManager(clock);
		var sum = new DummySum();
		new ControllerTest(new ControllerShiHeatPumpImpl()) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("componentManager", cm) //
				.addReference("sum", sum) //
				.addReference("predictorManager", sunnyPredictor(cm, sum, Instant.now(clock))) //
				.addReference("heatPump", new DummyHeatShiHeatPump("heatPump0")) //
				.addComponent(new DummyManagedSymmetricEss("ess0") //
						.setPower(new DummyPower(10_000))) //
				.activate(MyConfig.create() //
						.setId("ctrl0") //
						.setHeatPumpId("heatPump0") //
						.setEssId("ess0") //
						.setHeatPumpPosition(HeatPumpPosition.GRID_SIDE_OF_GRID_METER) //
						.setBoostConfirmationSeconds(20) //
						.build()) //
				.next(new TestCase("Conditions fulfilled: pending, not yet elevated") //
						.input("_sum", Sum.ChannelId.GRID_ACTIVE_POWER, -4000) //
						.input("_sum", Sum.ChannelId.ESS_DISCHARGE_POWER, 0) //
						.input("_sum", Sum.ChannelId.ESS_SOC, 65) //
						.input("_sum", Sum.ChannelId.ESS_CAPACITY, 10_000) //
						.input("heatPump0", ElectricityMeter.ChannelId.ACTIVE_POWER, 500) //
						.output(ControllerShiHeatPump.ChannelId.ELEVATED_MODE_ACTIVE, false) //
						.output(ControllerShiHeatPump.ChannelId.BOOST_PENDING, true)) //
				.next(new TestCase("Surplus dip: no longer pending (progress decays, not reset)") //
						.input("_sum", Sum.ChannelId.GRID_ACTIVE_POWER, 0) //
						.output(ControllerShiHeatPump.ChannelId.BOOST_PENDING, false)) //
				.next(new TestCase("Surplus back: pending again") //
						.input("_sum", Sum.ChannelId.GRID_ACTIVE_POWER, -4000) //
						.output(ControllerShiHeatPump.ChannelId.BOOST_PENDING, true)) //
				// 10 s accumulated (< 20 s) -> still pending, then another 10 s reaches it.
				.next(new TestCase("10 s confirmation (< 20 s): still pending") //
						.timeleap(clock, 10, ChronoUnit.SECONDS) //
						.input("_sum", Sum.ChannelId.GRID_ACTIVE_POWER, -4000) //
						.output(ControllerShiHeatPump.ChannelId.ELEVATED_MODE_ACTIVE, false) //
						.output(ControllerShiHeatPump.ChannelId.BOOST_PENDING, true)) //
				.next(new TestCase("Confirmation time reached: elevated mode") //
						.timeleap(clock, 10, ChronoUnit.SECONDS) //
						.input("_sum", Sum.ChannelId.GRID_ACTIVE_POWER, -4000) //
						.output(ControllerShiHeatPump.ChannelId.ELEVATED_MODE_ACTIVE, true) //
						.output(ControllerShiHeatPump.ChannelId.BOOST_PENDING, false)) //
				.deactivate();
	}

	@Test
	void testLargeGapResetsConfirmationProgress() throws Exception {
		var clock = createDummyClock();
		var cm = new DummyComponentManager(clock);
		var sum = new DummySum();
		// No Cycle reference -> the gap threshold falls back to the floor (10 s), so
		// the <=10 s steps below are credited while the 10-minute gap is a discontinuity.
		new ControllerTest(new ControllerShiHeatPumpImpl()) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("componentManager", cm) //
				.addReference("sum", sum) //
				.addReference("predictorManager", sunnyPredictor(cm, sum, Instant.now(clock))) //
				.addReference("heatPump", new DummyHeatShiHeatPump("heatPump0")) //
				.addComponent(new DummyManagedSymmetricEss("ess0") //
						.setPower(new DummyPower(10_000))) //
				.activate(MyConfig.create() //
						.setId("ctrl0") //
						.setHeatPumpId("heatPump0") //
						.setEssId("ess0") //
						.setHeatPumpPosition(HeatPumpPosition.GRID_SIDE_OF_GRID_METER) //
						.setBoostConfirmationSeconds(20) //
						.build()) //
				.next(new TestCase("Conditions fulfilled: confirmation starts at 0") //
						.input("_sum", Sum.ChannelId.GRID_ACTIVE_POWER, -4000) //
						.input("_sum", Sum.ChannelId.ESS_DISCHARGE_POWER, 0) //
						.input("_sum", Sum.ChannelId.ESS_SOC, 65) //
						.input("_sum", Sum.ChannelId.ESS_CAPACITY, 10_000) //
						.input("heatPump0", ElectricityMeter.ChannelId.ACTIVE_POWER, 500) //
						.output(ControllerShiHeatPump.ChannelId.ELEVATED_MODE_ACTIVE, false)) //
				.next(new TestCase("After 10 s: 10/20 s confirmed, still not elevated") //
						.timeleap(clock, 10, ChronoUnit.SECONDS) //
						.input("_sum", Sum.ChannelId.GRID_ACTIVE_POWER, -4000) //
						.input("_sum", Sum.ChannelId.ESS_DISCHARGE_POWER, 0) //
						.input("_sum", Sum.ChannelId.ESS_SOC, 65) //
						.input("_sum", Sum.ChannelId.ESS_CAPACITY, 10_000) //
						.input("heatPump0", ElectricityMeter.ChannelId.ACTIVE_POWER, 500) //
						.output(ControllerShiHeatPump.ChannelId.ELEVATED_MODE_ACTIVE, false)) //
				.next(new TestCase("After 8 s more: 18/20 s confirmed, nearly there") //
						.timeleap(clock, 8, ChronoUnit.SECONDS) //
						.input("_sum", Sum.ChannelId.GRID_ACTIVE_POWER, -4000) //
						.input("_sum", Sum.ChannelId.ESS_DISCHARGE_POWER, 0) //
						.input("_sum", Sum.ChannelId.ESS_SOC, 65) //
						.input("_sum", Sum.ChannelId.ESS_CAPACITY, 10_000) //
						.input("heatPump0", ElectricityMeter.ChannelId.ACTIVE_POWER, 500) //
						.output(ControllerShiHeatPump.ChannelId.ELEVATED_MODE_ACTIVE, false)) //
				// A 10-minute gap (controller not scheduled) is a discontinuity: it not
				// only fails to credit the unobserved time, it also resets the 18 s of
				// progress collected before it.
				.next(new TestCase("Large gap resets progress: still not elevated") //
						.timeleap(clock, 10, ChronoUnit.MINUTES) //
						.input("_sum", Sum.ChannelId.GRID_ACTIVE_POWER, -4000) //
						.input("_sum", Sum.ChannelId.ESS_DISCHARGE_POWER, 0) //
						.input("_sum", Sum.ChannelId.ESS_SOC, 65) //
						.input("_sum", Sum.ChannelId.ESS_CAPACITY, 10_000) //
						.input("heatPump0", ElectricityMeter.ChannelId.ACTIVE_POWER, 500) //
						.output(ControllerShiHeatPump.ChannelId.ELEVATED_MODE_ACTIVE, false)) //
				// Only 10 s have accrued since the reset (not 18 + 10 = 28), so the boost
				// is NOT confirmed yet -> proves the progress was cleared, not just paused.
				.next(new TestCase("10 s after reset: only 10/20 s, still not elevated") //
						.timeleap(clock, 10, ChronoUnit.SECONDS) //
						.input("_sum", Sum.ChannelId.GRID_ACTIVE_POWER, -4000) //
						.input("_sum", Sum.ChannelId.ESS_DISCHARGE_POWER, 0) //
						.input("_sum", Sum.ChannelId.ESS_SOC, 65) //
						.input("_sum", Sum.ChannelId.ESS_CAPACITY, 10_000) //
						.input("heatPump0", ElectricityMeter.ChannelId.ACTIVE_POWER, 500) //
						.output(ControllerShiHeatPump.ChannelId.ELEVATED_MODE_ACTIVE, false)) //
				.deactivate();
	}

	@Test
	void testForecastVetoBlocksEntry() throws Exception {
		var clock = createDummyClock();
		final var cm = new DummyComponentManager(clock);
		final var sum = new DummySum();
		// Production is 0 for the current quarter (cloud now) but high afterwards, so
		// the night reserve stays small while the veto - which requires the sun alone
		// to sustain the commit - fires on the current quarter.
		var prod = new Integer[96];
		var cons = new Integer[96];
		Arrays.fill(prod, 5000);
		prod[0] = 0;
		Arrays.fill(cons, 500);
		var predictorManager = new DummyPredictorManager(//
				new DummyPredictor("predictor0", cm, Prediction.from(sum, SUM_PRODUCTION_ACTIVE_POWER, Instant.now(clock),
						prod), SUM_PRODUCTION_ACTIVE_POWER),
				new DummyPredictor("predictor1", cm, Prediction.from(sum, SUM_CONSUMPTION_ACTIVE_POWER, Instant.now(clock),
						cons), SUM_CONSUMPTION_ACTIVE_POWER));
		new ControllerTest(new ControllerShiHeatPumpImpl()) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("componentManager", cm) //
				.addReference("sum", sum) //
				.addReference("predictorManager", predictorManager) //
				.addReference("heatPump", new DummyHeatShiHeatPump("heatPump0")) //
				.addComponent(new DummyManagedSymmetricEss("ess0") //
						.setPower(new DummyPower(10_000))) //
				.activate(MyConfig.create() //
						.setId("ctrl0") //
						.setHeatPumpId("heatPump0") //
						.setEssId("ess0") //
						.setHeatPumpPosition(HeatPumpPosition.GRID_SIDE_OF_GRID_METER) //
						.setForecastVetoEnabled(true) //
						.build()) //
				.next(new TestCase("Measured export present, but forecast vetoes entry") //
						.input("_sum", Sum.ChannelId.GRID_ACTIVE_POWER, -4000) //
						.input("_sum", Sum.ChannelId.ESS_DISCHARGE_POWER, 0) //
						.input("_sum", Sum.ChannelId.ESS_SOC, 65) //
						.input("_sum", Sum.ChannelId.ESS_CAPACITY, 10_000) //
						.input("heatPump0", ElectricityMeter.ChannelId.ACTIVE_POWER, 500) //
						.output(ControllerShiHeatPump.ChannelId.ELEVATED_MODE_ACTIVE, false) //
						.output(ControllerShiHeatPump.ChannelId.BOOST_FORECAST_VETO, true)) //
				.deactivate();
	}

	@Test
	void testForecastVetoAlignsGapsByTimeNotIndex() throws Exception {
		var clock = createDummyClock();
		final var cm = new DummyComponentManager(clock);
		final var sum = new DummySum();
		// Production has a gap in quarter 1, consumption a gap in quarter 2 (different
		// positions). Aligned by time, every quarter present in BOTH forecasts is
		// strong (production - consumption >> minimum power), so the veto must NOT fire.
		// If the two series were instead aligned by array index (asArray dropping the
		// gaps), consumption would shift left and the strong-production quarter would be
		// paired with the 5000 W consumption of quarter 1 -> a false veto. Asserting the
		// veto stays off guards against that regression.
		var prod = new Integer[96];
		var cons = new Integer[96];
		Arrays.fill(prod, 3000);
		Arrays.fill(cons, 400);
		prod[1] = null; // production gap at quarter 1
		prod[2] = 600; // still fine vs. its own consumption (400), but a trap under index shift
		cons[1] = 5000; // ignored under time alignment (production is a gap in this quarter)
		cons[2] = null; // consumption gap at quarter 2
		var predictorManager = new DummyPredictorManager(//
				new DummyPredictor("predictor0", cm, Prediction.from(sum, SUM_PRODUCTION_ACTIVE_POWER, Instant.now(clock),
						prod), SUM_PRODUCTION_ACTIVE_POWER),
				new DummyPredictor("predictor1", cm, Prediction.from(sum, SUM_CONSUMPTION_ACTIVE_POWER, Instant.now(clock),
						cons), SUM_CONSUMPTION_ACTIVE_POWER));
		new ControllerTest(new ControllerShiHeatPumpImpl()) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("componentManager", cm) //
				.addReference("sum", sum) //
				.addReference("predictorManager", predictorManager) //
				.addReference("heatPump", new DummyHeatShiHeatPump("heatPump0")) //
				.addComponent(new DummyManagedSymmetricEss("ess0") //
						.setPower(new DummyPower(10_000))) //
				.activate(MyConfig.create() //
						.setId("ctrl0") //
						.setHeatPumpId("heatPump0") //
						.setEssId("ess0") //
						.setHeatPumpPosition(HeatPumpPosition.GRID_SIDE_OF_GRID_METER) //
						.setForecastVetoEnabled(true) //
						.setMinimumSurplusPowerForElevatedMode(500) //
						// Long commit window so it spans the quarters holding the two gaps.
						.setMinimumSwitchingTime(5400) //
						.build()) //
				.next(new TestCase("Gaps in different quarters: no false veto, boost proceeds") //
						.input("_sum", Sum.ChannelId.GRID_ACTIVE_POWER, -4000) //
						.input("_sum", Sum.ChannelId.ESS_DISCHARGE_POWER, 0) //
						.input("_sum", Sum.ChannelId.ESS_SOC, 65) //
						.input("_sum", Sum.ChannelId.ESS_CAPACITY, 10_000) //
						.input("heatPump0", ElectricityMeter.ChannelId.ACTIVE_POWER, 500) //
						.output(ControllerShiHeatPump.ChannelId.BOOST_FORECAST_VETO, false) //
						.output(ControllerShiHeatPump.ChannelId.ELEVATED_MODE_ACTIVE, true)) //
				.deactivate();
	}

	@Test
	void testForecastVetoAllowsEntryWhenSurplusPredicted() throws Exception {
		var clock = createDummyClock();
		var cm = new DummyComponentManager(clock);
		var sum = new DummySum();
		new ControllerTest(new ControllerShiHeatPumpImpl()) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("componentManager", cm) //
				.addReference("sum", sum) //
				.addReference("predictorManager", sunnyPredictor(cm, sum, Instant.now(clock))) //
				.addReference("heatPump", new DummyHeatShiHeatPump("heatPump0")) //
				.addComponent(new DummyManagedSymmetricEss("ess0") //
						.setPower(new DummyPower(10_000))) //
				.activate(MyConfig.create() //
						.setId("ctrl0") //
						.setHeatPumpId("heatPump0") //
						.setEssId("ess0") //
						.setHeatPumpPosition(HeatPumpPosition.GRID_SIDE_OF_GRID_METER) //
						.setForecastVetoEnabled(true) //
						.build()) //
				.next(new TestCase("Forecast confirms surplus: elevated mode starts") //
						.input("_sum", Sum.ChannelId.GRID_ACTIVE_POWER, -4000) //
						.input("_sum", Sum.ChannelId.ESS_DISCHARGE_POWER, 0) //
						.input("_sum", Sum.ChannelId.ESS_SOC, 65) //
						.input("_sum", Sum.ChannelId.ESS_CAPACITY, 10_000) //
						.input("heatPump0", ElectricityMeter.ChannelId.ACTIVE_POWER, 500) //
						.output(ControllerShiHeatPump.ChannelId.ELEVATED_MODE_ACTIVE, true) //
						.output(ControllerShiHeatPump.ChannelId.BOOST_FORECAST_VETO, false)) //
				.deactivate();
	}

	@Test
	void testMinPredictedPowerRaisesThreshold() throws Exception {
		var clock = createDummyClock();
		var cm = new DummyComponentManager(clock);
		var sum = new DummySum();
		new ControllerTest(new ControllerShiHeatPumpImpl()) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("componentManager", cm) //
				.addReference("sum", sum) //
				.addReference("predictorManager", sunnyPredictor(cm, sum, Instant.now(clock))) //
				.addReference("heatPump", new DummyHeatShiHeatPump("heatPump0")) //
				.addComponent(new DummyManagedSymmetricEss("ess0") //
						.setPower(new DummyPower(10_000))) //
				.activate(MyConfig.create() //
						.setId("ctrl0") //
						.setHeatPumpId("heatPump0") //
						.setEssId("ess0") //
						.setHeatPumpPosition(HeatPumpPosition.GRID_SIDE_OF_GRID_METER) //
						.setMinimumSurplusPowerForElevatedMode(2500) //
						.build()) //
				.next(new TestCase("Export below reported minimum consumption: no elevated mode") //
						.input("_sum", Sum.ChannelId.GRID_ACTIVE_POWER, -4000) //
						.input("_sum", Sum.ChannelId.ESS_DISCHARGE_POWER, 0) //
						.input("_sum", Sum.ChannelId.ESS_SOC, 65) //
						.input("_sum", Sum.ChannelId.ESS_CAPACITY, 10_000) //
						.input("heatPump0", ElectricityMeter.ChannelId.ACTIVE_POWER, 0) //
						.input("heatPump0", HeatShiHeatPump.ChannelId.MIN_PREDICTED_ACTIVE_POWER, 4500) //
						.output(ControllerShiHeatPump.ChannelId.ELEVATED_MODE_ACTIVE, false)) //
				.next(new TestCase("Export reaches reported minimum consumption: elevated mode") //
						.timeleap(clock, 6, ChronoUnit.MINUTES) //
						.input("_sum", Sum.ChannelId.GRID_ACTIVE_POWER, -5000) //
						.input("_sum", Sum.ChannelId.ESS_DISCHARGE_POWER, 0) //
						.input("_sum", Sum.ChannelId.ESS_SOC, 65) //
						.input("_sum", Sum.ChannelId.ESS_CAPACITY, 10_000) //
						.input("heatPump0", ElectricityMeter.ChannelId.ACTIVE_POWER, 0) //
						.input("heatPump0", HeatShiHeatPump.ChannelId.MIN_PREDICTED_ACTIVE_POWER, 4500) //
						.output(ControllerShiHeatPump.ChannelId.ELEVATED_MODE_ACTIVE, true)) //
				.deactivate();
	}

	@Test
	void testHysteresisRespectsCompressorCycleLimits() throws Exception {
		var clock = createDummyClock();
		var cm = new DummyComponentManager(clock);
		var sum = new DummySum();
		new ControllerTest(new ControllerShiHeatPumpImpl()) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("componentManager", cm) //
				.addReference("sum", sum) //
				.addReference("predictorManager", sunnyPredictor(cm, sum, Instant.now(clock))) //
				.addReference("heatPump", new DummyHeatShiHeatPump("heatPump0")) //
				.addComponent(new DummyManagedSymmetricEss("ess0") //
						.setPower(new DummyPower(10_000))) //
				.activate(MyConfig.create() //
						.setId("ctrl0") //
						.setHeatPumpId("heatPump0") //
						.setEssId("ess0") //
						.setHeatPumpPosition(HeatPumpPosition.GRID_SIDE_OF_GRID_METER) //
						.build()) //
				.next(new TestCase("Elevated mode starts") //
						.input("heatPump0", HeatShiHeatPump.ChannelId.MIN_RUNTIME, 20) //
						.input("heatPump0", HeatShiHeatPump.ChannelId.MIN_STANDSTILL_TIME, 20) //
						.input("_sum", Sum.ChannelId.GRID_ACTIVE_POWER, -4000) //
						.input("_sum", Sum.ChannelId.ESS_DISCHARGE_POWER, 0) //
						.input("_sum", Sum.ChannelId.ESS_SOC, 65) //
						.input("_sum", Sum.ChannelId.ESS_CAPACITY, 10_000) //
						.input("heatPump0", ElectricityMeter.ChannelId.ACTIVE_POWER, 500) //
						.output(ControllerShiHeatPump.ChannelId.ELEVATED_MODE_ACTIVE, true)) //
				.next(new TestCase("After 6 minutes: minimum runtime keeps elevated mode") //
						.timeleap(clock, 6, ChronoUnit.MINUTES) //
						.input("_sum", Sum.ChannelId.GRID_ACTIVE_POWER, 0) //
						.input("_sum", Sum.ChannelId.ESS_DISCHARGE_POWER, 0) //
						.output(ControllerShiHeatPump.ChannelId.ELEVATED_MODE_ACTIVE, true)) //
				.next(new TestCase("After 21 minutes: back to normal mode") //
						.timeleap(clock, 15, ChronoUnit.MINUTES) //
						.input("_sum", Sum.ChannelId.GRID_ACTIVE_POWER, 0) //
						.input("_sum", Sum.ChannelId.ESS_DISCHARGE_POWER, 0) //
						.output(ControllerShiHeatPump.ChannelId.ELEVATED_MODE_ACTIVE, false)) //
				.next(new TestCase("Surplus back after 6 minutes: restart lock blocks re-entry") //
						.timeleap(clock, 6, ChronoUnit.MINUTES) //
						.input("_sum", Sum.ChannelId.GRID_ACTIVE_POWER, -4000) //
						.input("_sum", Sum.ChannelId.ESS_DISCHARGE_POWER, 0) //
						.output(ControllerShiHeatPump.ChannelId.ELEVATED_MODE_ACTIVE, false)) //
				.next(new TestCase("Restart lock expired: elevated mode again") //
						.timeleap(clock, 15, ChronoUnit.MINUTES) //
						.input("_sum", Sum.ChannelId.GRID_ACTIVE_POWER, -4000) //
						.input("_sum", Sum.ChannelId.ESS_DISCHARGE_POWER, 0) //
						.output(ControllerShiHeatPump.ChannelId.ELEVATED_MODE_ACTIVE, true)) //
				.deactivate();
	}

	@Test
	void testSkipsHeatingWritesWhileHeatingDisabled() throws Exception {
		var clock = createDummyClock();
		var cm = new DummyComponentManager(clock);
		var sum = new DummySum();
		new ControllerTest(new ControllerShiHeatPumpImpl()) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("componentManager", cm) //
				.addReference("sum", sum) //
				.addReference("predictorManager", sunnyPredictor(cm, sum, Instant.now(clock))) //
				.addReference("heatPump", new DummyHeatShiHeatPump("heatPump0")) //
				.addComponent(new DummyManagedSymmetricEss("ess0") //
						.setPower(new DummyPower(10_000))) //
				.activate(MyConfig.create() //
						.setId("ctrl0") //
						.setHeatPumpId("heatPump0") //
						.setEssId("ess0") //
						.setHeatPumpPosition(HeatPumpPosition.GRID_SIDE_OF_GRID_METER) //
						.build()) //
				// Heating operating mode is "Off" (summer): only hot water is influenced
				.next(new TestCase("Heating disabled: only hot water is influenced") //
						.input("_sum", Sum.ChannelId.GRID_ACTIVE_POWER, -4000) //
						.input("_sum", Sum.ChannelId.ESS_DISCHARGE_POWER, 0) //
						.input("_sum", Sum.ChannelId.ESS_SOC, 65) //
						.input("_sum", Sum.ChannelId.ESS_CAPACITY, 10_000) //
						.input("heatPump0", ElectricityMeter.ChannelId.ACTIVE_POWER, 0) //
						.input("heatPump0", HeatShiHeatPump.ChannelId.HEATING_STATUS, 0) //
						.output("heatPump0", HeatShiHeatPump.ChannelId.HEATING_MODE, null) //
						.output("heatPump0", HeatShiHeatPump.ChannelId.HEATING_SETPOINT, null) //
						.output("heatPump0", HeatShiHeatPump.ChannelId.HOT_WATER_MODE, 1) //
						.output("heatPump0", HeatShiHeatPump.ChannelId.HOT_WATER_SETPOINT, 550) //
						.output("heatPump0", HeatShiHeatPump.ChannelId.LPC_MODE, 1) //
						.output(ControllerShiHeatPump.ChannelId.ELEVATED_MODE_ACTIVE, true)) //
				.deactivate();
	}

	@Test
	void testGridSidePassiveSupportCoversNaturalRun() throws Exception {
		var clock = createDummyClock();
		var cm = new DummyComponentManager(clock);
		var sum = new DummySum();
		new ControllerTest(new ControllerShiHeatPumpImpl()) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("componentManager", cm) //
				.addReference("sum", sum) //
				.addReference("predictorManager", sunnyPredictor(cm, sum, Instant.now(clock))) //
				.addReference("heatPump", new DummyHeatShiHeatPump("heatPump0")) //
				.addComponent(new DummyManagedSymmetricEss("ess0") //
						.setPower(new DummyPower(10_000))) //
				.activate(MyConfig.create() //
						.setId("ctrl0") //
						.setHeatPumpId("heatPump0") //
						.setEssId("ess0") //
						.setHeatPumpPosition(HeatPumpPosition.GRID_SIDE_OF_GRID_METER) //
						.setMinimumSurplusPowerForElevatedMode(5000) //
						.build()) //
				// No PV surplus, but a natural heat-pump run (2000 W) and free battery
				// energy -> battery covers the full run via forced export; not elevated.
				.next(new TestCase("Natural run without surplus: battery covers it") //
						.input("_sum", Sum.ChannelId.GRID_ACTIVE_POWER, 0) //
						.input("_sum", Sum.ChannelId.ESS_DISCHARGE_POWER, 0) //
						.input("_sum", Sum.ChannelId.ESS_ACTIVE_POWER, 0) //
						.input("_sum", Sum.ChannelId.ESS_SOC, 65) //
						.input("_sum", Sum.ChannelId.ESS_CAPACITY, 10_000) //
						.input("heatPump0", ElectricityMeter.ChannelId.ACTIVE_POWER, 2000) //
						.output("ess0", ManagedSymmetricEss.ChannelId.SET_ACTIVE_POWER_GREATER_OR_EQUALS, 2000) //
						.output(ControllerShiHeatPump.ChannelId.ELEVATED_MODE_ACTIVE, false) //
						.output(ControllerShiHeatPump.ChannelId.ESS_FORCED_EXPORT_POWER, 2000) //
						.output(ControllerShiHeatPump.ChannelId.ESS_SUPPORT_POWER, 2000)) //
				.next(new TestCase("Heat pump off: no support") //
						.input("heatPump0", ElectricityMeter.ChannelId.ACTIVE_POWER, 0) //
						.output(ControllerShiHeatPump.ChannelId.ESS_FORCED_EXPORT_POWER, 0) //
						.output(ControllerShiHeatPump.ChannelId.ESS_SUPPORT_POWER, 0)) //
				.deactivate();
	}

	@Test
	void testBoostWithoutSupportWhenReserveClaimsEnergy() throws Exception {
		var clock = createDummyClock();
		var cm = new DummyComponentManager(clock);
		var sum = new DummySum();
		// Flat forecast: no production, 1500 W household -> big night reserve that
		// claims all usable energy at 40 % SoC -> no free energy -> no battery
		// support. The strong PV surplus alone still starts the boost, though.
		new ControllerTest(new ControllerShiHeatPumpImpl()) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("componentManager", cm) //
				.addReference("sum", sum) //
				.addReference("predictorManager", flatPredictor(cm, sum, Instant.now(clock), 0, 1500)) //
				.addReference("heatPump", new DummyHeatShiHeatPump("heatPump0")) //
				.addComponent(new DummyManagedSymmetricEss("ess0") //
						.setPower(new DummyPower(10_000))) //
				.activate(MyConfig.create() //
						.setId("ctrl0") //
						.setHeatPumpId("heatPump0") //
						.setEssId("ess0") //
						.setHeatPumpPosition(HeatPumpPosition.GRID_SIDE_OF_GRID_METER) //
						.setMinSoc(15) //
						.setNightReserveBuffer(100) //
						.build()) //
				.next(new TestCase("Reserve claims all usable energy: boost on sun, no support") //
						.input("_sum", Sum.ChannelId.GRID_ACTIVE_POWER, -4000) //
						.input("_sum", Sum.ChannelId.ESS_DISCHARGE_POWER, 0) //
						.input("_sum", Sum.ChannelId.ESS_SOC, 40) //
						.input("_sum", Sum.ChannelId.ESS_CAPACITY, 10_000) //
						.input("heatPump0", ElectricityMeter.ChannelId.ACTIVE_POWER, 2000) //
						.output(ControllerShiHeatPump.ChannelId.ELEVATED_MODE_ACTIVE, true) //
						.output(ControllerShiHeatPump.ChannelId.ESS_FORCED_EXPORT_POWER, 0) //
						.output(ControllerShiHeatPump.ChannelId.FREE_BATTERY_ENERGY, 0)) //
				.deactivate();
	}

	@Test
	void testRunExtensionOnNaturalHotWaterRun() throws Exception {
		var clock = createDummyClock();
		var cm = new DummyComponentManager(clock);
		var sum = new DummySum();
		new ControllerTest(new ControllerShiHeatPumpImpl()) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("componentManager", cm) //
				.addReference("sum", sum) //
				.addReference("predictorManager", sunnyPredictor(cm, sum, Instant.now(clock))) //
				.addReference("heatPump", new DummyHeatShiHeatPump("heatPump0")) //
				.addComponent(new DummyManagedSymmetricEss("ess0") //
						.setPower(new DummyPower(10_000))) //
				.activate(MyConfig.create() //
						.setId("ctrl0") //
						.setHeatPumpId("heatPump0") //
						.setEssId("ess0") //
						.setHeatPumpPosition(HeatPumpPosition.GRID_SIDE_OF_GRID_METER) //
						.setMinimumSurplusPowerForElevatedMode(4000) //
						.build()) //
				// Natural hot-water run (2500 W), only 800 W export -> battery covers the
				// rest; natural setpoint 48 degC, elevated 55 degC -> delta 7 K.
				.next(new TestCase("Natural run covered by battery: extension starts") //
						.input("_sum", Sum.ChannelId.GRID_ACTIVE_POWER, -800) //
						.input("_sum", Sum.ChannelId.ESS_DISCHARGE_POWER, 0) //
						.input("_sum", Sum.ChannelId.ESS_ACTIVE_POWER, 0) //
						.input("_sum", Sum.ChannelId.ESS_SOC, 65) //
						.input("_sum", Sum.ChannelId.ESS_CAPACITY, 10_000) //
						.input("heatPump0", ElectricityMeter.ChannelId.ACTIVE_POWER, 2500) //
						.input("heatPump0", HeatShiHeatPump.ChannelId.OPERATING_MODE_STATUS, 1) //
						.input("heatPump0", HeatShiHeatPump.ChannelId.HOT_WATER_STATUS, 3) //
						.input("heatPump0", HeatShiHeatPump.ChannelId.HOT_WATER_MODE, 0) //
						.input("heatPump0", HeatShiHeatPump.ChannelId.HOT_WATER_ACTIVE_SETPOINT, 480) //
						.output("heatPump0", HeatShiHeatPump.ChannelId.HOT_WATER_MODE, 1) //
						.output("heatPump0", HeatShiHeatPump.ChannelId.HOT_WATER_SETPOINT, 550) //
						.output("heatPump0", HeatShiHeatPump.ChannelId.HEATING_MODE, 0) //
						.output("heatPump0", HeatShiHeatPump.ChannelId.LPC_MODE, 1) //
						.output(ControllerShiHeatPump.ChannelId.ELEVATED_MODE_ACTIVE, false) //
						.output(ControllerShiHeatPump.ChannelId.RUN_EXTENSION_ACTIVE, true) //
						.output(ControllerShiHeatPump.ChannelId.NATURAL_HOT_WATER_SETPOINT, 480)) //
				.next(new TestCase("Run finished: extension ends") //
						.input("heatPump0", HeatShiHeatPump.ChannelId.HOT_WATER_STATUS, 1) //
						.output("heatPump0", HeatShiHeatPump.ChannelId.HOT_WATER_MODE, 0) //
						.output("heatPump0", HeatShiHeatPump.ChannelId.LPC_MODE, 0) //
						.output(ControllerShiHeatPump.ChannelId.RUN_EXTENSION_ACTIVE, false)) //
				.deactivate();
	}

	@Test
	void testSoftLimitWrittenOnNaturalHeatingRun() throws Exception {
		final var clock = createDummyClock();
		final var cm = new DummyComponentManager(clock);
		final var sum = new DummySum();
		new ControllerTest(new ControllerShiHeatPumpImpl()) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("componentManager", cm) //
				.addReference("sum", sum) //
				.addReference("predictorManager", sunnyPredictor(cm, sum, Instant.now(clock))) //
				.addReference("heatPump", new DummyHeatShiHeatPump("heatPump0")) //
				.addComponent(new DummyManagedSymmetricEss("ess0") //
						.setPower(new DummyPower(10_000))) //
				.activate(MyConfig.create() //
						.setId("ctrl0") //
						.setHeatPumpId("heatPump0") //
						.setEssId("ess0") //
						.setHeatPumpPosition(HeatPumpPosition.GRID_SIDE_OF_GRID_METER) //
						.setMinimumSurplusPowerForElevatedMode(2500) //
						.setMaxBatterySupportPower(2000) //
						.build()) //
				// Heating run the heat pump started on its own: 800 W export plus 2000 W
				// deliverable battery support = 2800 W covered, above the 2500 W minimum.
				// The heating setpoint stays released (elevating it would overheat the
				// house), but the soft power limit is written to the covered power.
				.next(new TestCase("Covered heating run: soft limit written, setpoint released") //
						.input("_sum", Sum.ChannelId.GRID_ACTIVE_POWER, -800) //
						.input("_sum", Sum.ChannelId.ESS_DISCHARGE_POWER, 0) //
						.input("_sum", Sum.ChannelId.ESS_ACTIVE_POWER, 0) //
						.input("_sum", Sum.ChannelId.ESS_SOC, 65) //
						.input("_sum", Sum.ChannelId.ESS_CAPACITY, 10_000) //
						.input("heatPump0", ElectricityMeter.ChannelId.ACTIVE_POWER, 2500) //
						.input("heatPump0", HeatShiHeatPump.ChannelId.HEATING_STATUS, 3) //
						.output("heatPump0", HeatShiHeatPump.ChannelId.HEATING_MODE, 0) //
						.output("heatPump0", HeatShiHeatPump.ChannelId.LPC_MODE, 1) //
						.output("heatPump0", HeatShiHeatPump.ChannelId.PC_LIMIT, 2800) //
						.output(ControllerShiHeatPump.ChannelId.ELEVATED_MODE_ACTIVE, false) //
						.output(ControllerShiHeatPump.ChannelId.RUN_EXTENSION_ACTIVE, false)) //
				// Only 200 W export left -> 2200 W covered, below the minimum power. The
				// limit is released entirely instead of throttling a genuine heat demand.
				.next(new TestCase("Coverage too weak: limit released, run left alone") //
						.input("_sum", Sum.ChannelId.GRID_ACTIVE_POWER, -200) //
						.input("_sum", Sum.ChannelId.ESS_DISCHARGE_POWER, 0) //
						.input("_sum", Sum.ChannelId.ESS_ACTIVE_POWER, 0) //
						.input("_sum", Sum.ChannelId.ESS_SOC, 65) //
						.input("_sum", Sum.ChannelId.ESS_CAPACITY, 10_000) //
						.input("heatPump0", ElectricityMeter.ChannelId.ACTIVE_POWER, 2500) //
						.input("heatPump0", HeatShiHeatPump.ChannelId.HEATING_STATUS, 3) //
						.output("heatPump0", HeatShiHeatPump.ChannelId.LPC_MODE, 0) //
						.output("heatPump0", HeatShiHeatPump.ChannelId.PC_LIMIT, 0)) //
				// Heating enabled but no active run (status 1): nothing to modulate.
				.next(new TestCase("No active run: limit stays released") //
						.input("_sum", Sum.ChannelId.GRID_ACTIVE_POWER, -800) //
						.input("_sum", Sum.ChannelId.ESS_DISCHARGE_POWER, 0) //
						.input("_sum", Sum.ChannelId.ESS_ACTIVE_POWER, 0) //
						.input("_sum", Sum.ChannelId.ESS_SOC, 65) //
						.input("_sum", Sum.ChannelId.ESS_CAPACITY, 10_000) //
						.input("heatPump0", ElectricityMeter.ChannelId.ACTIVE_POWER, 0) //
						.input("heatPump0", HeatShiHeatPump.ChannelId.HEATING_STATUS, 1) //
						.output("heatPump0", HeatShiHeatPump.ChannelId.LPC_MODE, 0) //
						.output("heatPump0", HeatShiHeatPump.ChannelId.PC_LIMIT, 0)) //
				.deactivate();
	}

	@Test
	void testRunExtensionSkippedForSmallDelta() throws Exception {
		var clock = createDummyClock();
		var cm = new DummyComponentManager(clock);
		var sum = new DummySum();
		new ControllerTest(new ControllerShiHeatPumpImpl()) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("componentManager", cm) //
				.addReference("sum", sum) //
				.addReference("predictorManager", sunnyPredictor(cm, sum, Instant.now(clock))) //
				.addReference("heatPump", new DummyHeatShiHeatPump("heatPump0")) //
				.addComponent(new DummyManagedSymmetricEss("ess0") //
						.setPower(new DummyPower(10_000))) //
				.activate(MyConfig.create() //
						.setId("ctrl0") //
						.setHeatPumpId("heatPump0") //
						.setEssId("ess0") //
						.setHeatPumpPosition(HeatPumpPosition.GRID_SIDE_OF_GRID_METER) //
						.setMinimumSurplusPowerForElevatedMode(4000) //
						.build()) //
				// Natural setpoint 53 degC -> delta to 55 degC is only 2 K < 3 K
				.next(new TestCase("Delta too small: no extension") //
						.input("_sum", Sum.ChannelId.GRID_ACTIVE_POWER, -800) //
						.input("_sum", Sum.ChannelId.ESS_DISCHARGE_POWER, 0) //
						.input("_sum", Sum.ChannelId.ESS_ACTIVE_POWER, 0) //
						.input("_sum", Sum.ChannelId.ESS_SOC, 65) //
						.input("_sum", Sum.ChannelId.ESS_CAPACITY, 10_000) //
						.input("heatPump0", ElectricityMeter.ChannelId.ACTIVE_POWER, 2500) //
						.input("heatPump0", HeatShiHeatPump.ChannelId.OPERATING_MODE_STATUS, 1) //
						.input("heatPump0", HeatShiHeatPump.ChannelId.HOT_WATER_STATUS, 3) //
						.input("heatPump0", HeatShiHeatPump.ChannelId.HOT_WATER_MODE, 0) //
						.input("heatPump0", HeatShiHeatPump.ChannelId.HOT_WATER_ACTIVE_SETPOINT, 530) //
						.output("heatPump0", HeatShiHeatPump.ChannelId.HOT_WATER_MODE, 0) //
						.output(ControllerShiHeatPump.ChannelId.RUN_EXTENSION_ACTIVE, false)) //
				.deactivate();
	}

	@Test
	void testBehindMeterLimitsDischargeAndAddsHeatPumpToSurplus() throws Exception {
		var clock = createDummyClock();
		new ControllerTest(new ControllerShiHeatPumpImpl()) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("componentManager", new DummyComponentManager(clock)) //
				.addReference("sum", new DummySum()) //
				.addReference("heatPump", new DummyHeatShiHeatPump("heatPump0") //
						.withMeterType(MeterType.CONSUMPTION_METERED)) //
				.addComponent(new DummyManagedSymmetricEss("ess0") //
						.setPower(new DummyPower(10_000))) //
				.activate(MyConfig.create() //
						.setId("ctrl0") //
						.setHeatPumpId("heatPump0") //
						.setEssId("ess0") //
						.setHeatPumpPosition(HeatPumpPosition.BEHIND_GRID_METER) //
						.build()) //
				// No free energy (no prediction): battery may only serve the household
				// share (2500 W discharge - 2000 W heat pump = 500 W household)
				.next(new TestCase("Discharge limited to household share") //
						.input("_sum", Sum.ChannelId.GRID_ACTIVE_POWER, 0) //
						.input("_sum", Sum.ChannelId.ESS_DISCHARGE_POWER, 2500) //
						.input("_sum", Sum.ChannelId.ESS_ACTIVE_POWER, 2500) //
						.input("heatPump0", ElectricityMeter.ChannelId.ACTIVE_POWER, 2000) //
						.output("heatPump0", HeatShiHeatPump.ChannelId.HEATING_MODE, 0) //
						.output("ess0", ManagedSymmetricEss.ChannelId.SET_ACTIVE_POWER_LESS_OR_EQUALS, 500) //
						.output(ControllerShiHeatPump.ChannelId.ELEVATED_MODE_ACTIVE, false) //
						.output(ControllerShiHeatPump.ChannelId.ESS_DISCHARGE_LIMIT, 500) //
						.output(ControllerShiHeatPump.ChannelId.METER_TYPE_MISMATCH, false)) //
				.deactivate();
	}

	@Test
	void testBehindMeterPassiveSupportRaisesDischargeLimit() throws Exception {
		var clock = createDummyClock();
		var cm = new DummyComponentManager(clock);
		var sum = new DummySum();
		new ControllerTest(new ControllerShiHeatPumpImpl()) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("componentManager", cm) //
				.addReference("sum", sum) //
				.addReference("predictorManager", sunnyPredictor(cm, sum, Instant.now(clock))) //
				.addReference("heatPump", new DummyHeatShiHeatPump("heatPump0") //
						.withMeterType(MeterType.CONSUMPTION_METERED)) //
				.addComponent(new DummyManagedSymmetricEss("ess0") //
						.setPower(new DummyPower(10_000))) //
				.activate(MyConfig.create() //
						.setId("ctrl0") //
						.setHeatPumpId("heatPump0") //
						.setEssId("ess0") //
						.setHeatPumpPosition(HeatPumpPosition.BEHIND_GRID_METER) //
						.setMinSoc(15) //
						.setNightReserveBuffer(100) //
						.build()) //
				// Battery covers household (500 W) plus the full heat-pump run (2000 W)
				.next(new TestCase("Discharge limit raised by allowed support") //
						.input("_sum", Sum.ChannelId.GRID_ACTIVE_POWER, 0) //
						.input("_sum", Sum.ChannelId.ESS_DISCHARGE_POWER, 2500) //
						.input("_sum", Sum.ChannelId.ESS_ACTIVE_POWER, 2500) //
						.input("_sum", Sum.ChannelId.ESS_SOC, 65) //
						.input("_sum", Sum.ChannelId.ESS_CAPACITY, 10_000) //
						.input("heatPump0", ElectricityMeter.ChannelId.ACTIVE_POWER, 2000) //
						.output("heatPump0", HeatShiHeatPump.ChannelId.HEATING_MODE, 0) //
						.output("ess0", ManagedSymmetricEss.ChannelId.SET_ACTIVE_POWER_LESS_OR_EQUALS, 2500) //
						.output(ControllerShiHeatPump.ChannelId.ESS_DISCHARGE_LIMIT, 2500) //
						.output(ControllerShiHeatPump.ChannelId.ESS_SUPPORT_POWER, 2000)) //
				.deactivate();
	}

	@Test
	void testBehindMeterActiveSupportZeroWhenBatteryIdle() throws Exception {
		var clock = createDummyClock();
		var cm = new DummyComponentManager(clock);
		var sum = new DummySum();
		new ControllerTest(new ControllerShiHeatPumpImpl()) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("componentManager", cm) //
				.addReference("sum", sum) //
				.addReference("predictorManager", sunnyPredictor(cm, sum, Instant.now(clock))) //
				.addReference("heatPump", new DummyHeatShiHeatPump("heatPump0") //
						.withMeterType(MeterType.CONSUMPTION_METERED)) //
				.addComponent(new DummyManagedSymmetricEss("ess0") //
						.setPower(new DummyPower(10_000))) //
				.activate(MyConfig.create() //
						.setId("ctrl0") //
						.setHeatPumpId("heatPump0") //
						.setEssId("ess0") //
						.setHeatPumpPosition(HeatPumpPosition.BEHIND_GRID_METER) //
						.setMinimumSurplusPowerForElevatedMode(5000) //
						.build()) //
				// PV exports (grid -1000 W), the battery is idle (0 W) and covers the
				// household from PV. The discharge allowance for the heat pump is raised
				// to 2000 W, but the battery delivers nothing - so the ACTIVE support is
				// 0, not the granted allowance.
				.next(new TestCase("Battery idle while PV exports: active support is 0") //
						.input("_sum", Sum.ChannelId.GRID_ACTIVE_POWER, -1000) //
						.input("_sum", Sum.ChannelId.ESS_DISCHARGE_POWER, 0) //
						.input("_sum", Sum.ChannelId.ESS_ACTIVE_POWER, 0) //
						.input("_sum", Sum.ChannelId.ESS_SOC, 65) //
						.input("_sum", Sum.ChannelId.ESS_CAPACITY, 10_000) //
						.input("heatPump0", ElectricityMeter.ChannelId.ACTIVE_POWER, 2000) //
						.output(ControllerShiHeatPump.ChannelId.ELEVATED_MODE_ACTIVE, false) //
						.output(ControllerShiHeatPump.ChannelId.ESS_DISCHARGE_LIMIT, 2000) //
						.output(ControllerShiHeatPump.ChannelId.ESS_SUPPORT_POWER, 0)) //
				.deactivate();
	}

	@Test
	void testWeakBatteryLimitsSupportAndCoverage() throws Exception {
		var clock = createDummyClock();
		var cm = new DummyComponentManager(clock);
		var sum = new DummySum();
		new ControllerTest(new ControllerShiHeatPumpImpl()) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("componentManager", cm) //
				.addReference("sum", sum) //
				.addReference("predictorManager", sunnyPredictor(cm, sum, Instant.now(clock))) //
				.addReference("heatPump", new DummyHeatShiHeatPump("heatPump0")) //
				// The ESS can only deliver 1000 W right now, even though plenty of energy
				// is free above the reserve.
				.addComponent(new DummyManagedSymmetricEss("ess0") //
						.setPower(new DummyPower(1000))) //
				.activate(MyConfig.create() //
						.setId("ctrl0") //
						.setHeatPumpId("heatPump0") //
						.setEssId("ess0") //
						.setHeatPumpPosition(HeatPumpPosition.GRID_SIDE_OF_GRID_METER) //
						.setMinimumSurplusPowerForElevatedMode(4000) //
						.build()) //
				// Natural hot-water run of 2500 W, 800 W export. The support power is
				// bounded by the deliverable ESS power (1000 W), so the forced export is
				// 1000 W - not the 1700 W the heat pump could take. And since 800 + 1000
				// < 2500, the run is NOT fully covered, so the run extension does not
				// start (with the old 30 kW assumption it wrongly would, then draw grid).
				.next(new TestCase("Weak battery: support capped, coverage not assumed") //
						.input("_sum", Sum.ChannelId.GRID_ACTIVE_POWER, -800) //
						.input("_sum", Sum.ChannelId.ESS_DISCHARGE_POWER, 0) //
						.input("_sum", Sum.ChannelId.ESS_ACTIVE_POWER, 0) //
						.input("_sum", Sum.ChannelId.ESS_SOC, 65) //
						.input("_sum", Sum.ChannelId.ESS_CAPACITY, 10_000) //
						.input("heatPump0", ElectricityMeter.ChannelId.ACTIVE_POWER, 2500) //
						.input("heatPump0", HeatShiHeatPump.ChannelId.OPERATING_MODE_STATUS, 1) //
						.input("heatPump0", HeatShiHeatPump.ChannelId.HOT_WATER_STATUS, 3) //
						.input("heatPump0", HeatShiHeatPump.ChannelId.HOT_WATER_MODE, 0) //
						.input("heatPump0", HeatShiHeatPump.ChannelId.HOT_WATER_ACTIVE_SETPOINT, 480) //
						.output(ControllerShiHeatPump.ChannelId.ELEVATED_MODE_ACTIVE, false) //
						.output(ControllerShiHeatPump.ChannelId.RUN_EXTENSION_ACTIVE, false) //
						.output(ControllerShiHeatPump.ChannelId.ESS_FORCED_EXPORT_POWER, 1000)) //
				.deactivate();
	}

	@Test
	void testHouseholdShareReservedFromSupport() throws Exception {
		var clock = createDummyClock();
		var cm = new DummyComponentManager(clock);
		var sum = new DummySum();
		new ControllerTest(new ControllerShiHeatPumpImpl()) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("componentManager", cm) //
				.addReference("sum", sum) //
				.addReference("predictorManager", sunnyPredictor(cm, sum, Instant.now(clock))) //
				.addReference("heatPump", new DummyHeatShiHeatPump("heatPump0")) //
				// The ESS can discharge 3 kW in total.
				.addComponent(new DummyManagedSymmetricEss("ess0") //
						.setPower(new DummyPower(3000))) //
				.activate(MyConfig.create() //
						.setId("ctrl0") //
						.setHeatPumpId("heatPump0") //
						.setEssId("ess0") //
						.setHeatPumpPosition(HeatPumpPosition.GRID_SIDE_OF_GRID_METER) //
						.setMinimumSurplusPowerForElevatedMode(5000) //
						.build()) //
				// The household already draws 2 kW from the battery (ess discharge 2000 W,
				// grid 0, no PV surplus). Of the 3 kW the ESS can deliver, only 1 kW is
				// left for the heat pump. A natural 2500 W hot-water run is therefore NOT
				// fully covered (0 surplus + 1 kW < 2500 W) -> no run extension; and the
				// forced export is limited to the remaining 1 kW. With the household share
				// ignored, the coverage check would wrongly assume 3 kW and start the
				// extension, then draw the shortfall from the grid.
				.next(new TestCase("Household needs 2 kW of a 3 kW battery: only 1 kW supports the heat pump") //
						.input("_sum", Sum.ChannelId.GRID_ACTIVE_POWER, 0) //
						.input("_sum", Sum.ChannelId.ESS_DISCHARGE_POWER, 2000) //
						.input("_sum", Sum.ChannelId.ESS_ACTIVE_POWER, 2000) //
						.input("_sum", Sum.ChannelId.ESS_SOC, 65) //
						.input("_sum", Sum.ChannelId.ESS_CAPACITY, 10_000) //
						.input("heatPump0", ElectricityMeter.ChannelId.ACTIVE_POWER, 2500) //
						.input("heatPump0", HeatShiHeatPump.ChannelId.OPERATING_MODE_STATUS, 1) //
						.input("heatPump0", HeatShiHeatPump.ChannelId.HOT_WATER_STATUS, 3) //
						.input("heatPump0", HeatShiHeatPump.ChannelId.HOT_WATER_MODE, 0) //
						.input("heatPump0", HeatShiHeatPump.ChannelId.HOT_WATER_ACTIVE_SETPOINT, 480) //
						.output(ControllerShiHeatPump.ChannelId.ELEVATED_MODE_ACTIVE, false) //
						.output(ControllerShiHeatPump.ChannelId.RUN_EXTENSION_ACTIVE, false) //
						.output(ControllerShiHeatPump.ChannelId.ESS_FORCED_EXPORT_POWER, 1000)) //
				.deactivate();
	}

	@Test
	void testPredictionsFromUnmanagedChannels() throws Exception {
		var clock = createDummyClock();
		var cm = new DummyComponentManager(clock);
		var sum = new DummySum();
		var now = Instant.now(clock);
		// Predictors serve only the 'Unmanaged' channels (weather-based LinearModel)
		var prod = new Integer[96];
		var cons = new Integer[96];
		Arrays.fill(prod, 5000);
		Arrays.fill(cons, 500);
		var predictorManager = new DummyPredictorManager(//
				new DummyPredictor("predictor0", cm,
						Prediction.from(sum, SUM_UNMANAGED_PRODUCTION_ACTIVE_POWER, now, prod),
						SUM_UNMANAGED_PRODUCTION_ACTIVE_POWER),
				new DummyPredictor("predictor1", cm,
						Prediction.from(sum, SUM_UNMANAGED_CONSUMPTION_ACTIVE_POWER, now, cons),
						SUM_UNMANAGED_CONSUMPTION_ACTIVE_POWER));

		new ControllerTest(new ControllerShiHeatPumpImpl()) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("componentManager", cm) //
				.addReference("sum", sum) //
				.addReference("predictorManager", predictorManager) //
				.addReference("heatPump", new DummyHeatShiHeatPump("heatPump0")) //
				.addComponent(new DummyManagedSymmetricEss("ess0") //
						.setPower(new DummyPower(10_000))) //
				.activate(MyConfig.create() //
						.setId("ctrl0") //
						.setHeatPumpId("heatPump0") //
						.setEssId("ess0") //
						.setHeatPumpPosition(HeatPumpPosition.GRID_SIDE_OF_GRID_METER) //
						.setMinSoc(15) //
						.build()) //
				// Free energy is computed from the Unmanaged predictions -> support works
				.next(new TestCase("Support driven by Unmanaged-channel predictions") //
						.input("_sum", Sum.ChannelId.GRID_ACTIVE_POWER, 0) //
						.input("_sum", Sum.ChannelId.ESS_DISCHARGE_POWER, 0) //
						.input("_sum", Sum.ChannelId.ESS_ACTIVE_POWER, 0) //
						.input("_sum", Sum.ChannelId.ESS_SOC, 65) //
						.input("_sum", Sum.ChannelId.ESS_CAPACITY, 10_000) //
						.input("heatPump0", ElectricityMeter.ChannelId.ACTIVE_POWER, 1000) //
						.output(ControllerShiHeatPump.ChannelId.ESS_FORCED_EXPORT_POWER, 1000) //
						.output(ControllerShiHeatPump.ChannelId.NO_PREDICTION_AVAILABLE, false)) //
				.deactivate();
	}

	@Test
	void testNormalModeWithoutPrediction() throws Exception {
		var clock = createDummyClock();
		new ControllerTest(new ControllerShiHeatPumpImpl()) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("componentManager", new DummyComponentManager(clock)) //
				.addReference("sum", new DummySum()) //
				.addReference("heatPump", new DummyHeatShiHeatPump("heatPump0")) //
				.addComponent(new DummyManagedSymmetricEss("ess0") //
						.setPower(new DummyPower(10_000))) //
				.activate(MyConfig.create() //
						.setId("ctrl0") //
						.setHeatPumpId("heatPump0") //
						.setEssId("ess0") //
						.setHeatPumpPosition(HeatPumpPosition.GRID_SIDE_OF_GRID_METER) //
						.build()) //
				.next(new TestCase("No prediction: heat pump stays on grid tariff, warning set") //
						.input("_sum", Sum.ChannelId.GRID_ACTIVE_POWER, 1000) //
						.input("_sum", Sum.ChannelId.ESS_DISCHARGE_POWER, 0) //
						.input("_sum", Sum.ChannelId.ESS_SOC, 50) //
						.input("_sum", Sum.ChannelId.ESS_CAPACITY, 10_000) //
						.input("heatPump0", ElectricityMeter.ChannelId.ACTIVE_POWER, 2000) //
						.output("heatPump0", HeatShiHeatPump.ChannelId.HEATING_MODE, 0) //
						.output(ControllerShiHeatPump.ChannelId.ELEVATED_MODE_ACTIVE, false) //
						.output(ControllerShiHeatPump.ChannelId.ESS_FORCED_EXPORT_POWER, 0) //
						.output(ControllerShiHeatPump.ChannelId.NO_PREDICTION_AVAILABLE, true) //
						.output(ControllerShiHeatPump.ChannelId.NIGHT_RESERVE_ENERGY, null)) //
				.deactivate();
	}

	@Test
	void testUnmanagedGapFilledFromTotalConsumption() throws Exception {
		var clock = createDummyClock();
		final var cm = new DummyComponentManager(clock);
		final var sum = new DummySum();
		var prod = new Integer[96];
		var unmanaged = new Integer[96];
		var total = new Integer[96];
		Arrays.fill(prod, 5000);
		Arrays.fill(unmanaged, 500);
		Arrays.fill(total, 500);
		unmanaged[10] = null; // gap in the Unmanaged channel, present in the total channel
		new ControllerTest(new ControllerShiHeatPumpImpl()) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("componentManager", cm) //
				.addReference("sum", sum) //
				.addReference("predictorManager",
						consumptionSplitPredictor(cm, sum, Instant.now(clock), prod, unmanaged, total)) //
				.addReference("heatPump", new DummyHeatShiHeatPump("heatPump0")) //
				.addComponent(new DummyManagedSymmetricEss("ess0") //
						.setPower(new DummyPower(10_000))) //
				.activate(MyConfig.create() //
						.setId("ctrl0") //
						.setHeatPumpId("heatPump0") //
						.setEssId("ess0") //
						.setHeatPumpPosition(HeatPumpPosition.GRID_SIDE_OF_GRID_METER) //
						.build()) //
				// The Unmanaged gap is filled from the total consumption forecast ->
				// forecast counts as valid -> battery support is granted.
				.next(new TestCase("Unmanaged gap filled from total consumption: support granted") //
						.input("_sum", Sum.ChannelId.GRID_ACTIVE_POWER, 0) //
						.input("_sum", Sum.ChannelId.ESS_DISCHARGE_POWER, 0) //
						.input("_sum", Sum.ChannelId.ESS_ACTIVE_POWER, 0) //
						.input("_sum", Sum.ChannelId.ESS_SOC, 65) //
						.input("_sum", Sum.ChannelId.ESS_CAPACITY, 10_000) //
						.input("heatPump0", ElectricityMeter.ChannelId.ACTIVE_POWER, 1000) //
						.output(ControllerShiHeatPump.ChannelId.NO_PREDICTION_AVAILABLE, false) //
						.output(ControllerShiHeatPump.ChannelId.ESS_FORCED_EXPORT_POWER, 1000)) //
				.deactivate();
	}

	@Test
	void testBothConsumptionForecastsGapFailsSafe() throws Exception {
		var clock = createDummyClock();
		final var cm = new DummyComponentManager(clock);
		final var sum = new DummySum();
		var prod = new Integer[96];
		var unmanaged = new Integer[96];
		var total = new Integer[96];
		Arrays.fill(prod, 5000);
		Arrays.fill(unmanaged, 500);
		Arrays.fill(total, 500);
		unmanaged[10] = null; // same quarter missing in BOTH channels
		total[10] = null;
		new ControllerTest(new ControllerShiHeatPumpImpl()) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("componentManager", cm) //
				.addReference("sum", sum) //
				.addReference("predictorManager",
						consumptionSplitPredictor(cm, sum, Instant.now(clock), prod, unmanaged, total)) //
				.addReference("heatPump", new DummyHeatShiHeatPump("heatPump0")) //
				.addComponent(new DummyManagedSymmetricEss("ess0") //
						.setPower(new DummyPower(10_000))) //
				.activate(MyConfig.create() //
						.setId("ctrl0") //
						.setHeatPumpId("heatPump0") //
						.setEssId("ess0") //
						.setHeatPumpPosition(HeatPumpPosition.GRID_SIDE_OF_GRID_METER) //
						.build()) //
				// No forecast covers the missing quarter -> fail-safe: no battery
				// released, warning set.
				.next(new TestCase("Gap in both consumption channels: fail-safe, no support") //
						.input("_sum", Sum.ChannelId.GRID_ACTIVE_POWER, 0) //
						.input("_sum", Sum.ChannelId.ESS_DISCHARGE_POWER, 0) //
						.input("_sum", Sum.ChannelId.ESS_ACTIVE_POWER, 0) //
						.input("_sum", Sum.ChannelId.ESS_SOC, 65) //
						.input("_sum", Sum.ChannelId.ESS_CAPACITY, 10_000) //
						.input("heatPump0", ElectricityMeter.ChannelId.ACTIVE_POWER, 1000) //
						.output(ControllerShiHeatPump.ChannelId.NO_PREDICTION_AVAILABLE, true) //
						.output(ControllerShiHeatPump.ChannelId.FREE_BATTERY_ENERGY, 0) //
						.output(ControllerShiHeatPump.ChannelId.ESS_FORCED_EXPORT_POWER, 0)) //
				.deactivate();
	}

	@Test
	void testFallbackConsumptionEntersNightReserve() throws Exception {
		var clock = createDummyClock();
		final var cm = new DummyComponentManager(clock);
		final var sum = new DummySum();
		var prod = new Integer[96];
		var unmanaged = new Integer[96];
		var total = new Integer[96];
		Arrays.fill(prod, 0); // no production
		Arrays.fill(unmanaged, 0); // no household load ...
		Arrays.fill(total, 0);
		unmanaged[10] = null; // ... except a gap at quarter 10, where only the total
		total[10] = 4000; // forecast has 4000 W -> 1000 Wh deficit -> 1200 Wh at 120 %
		new ControllerTest(new ControllerShiHeatPumpImpl()) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("componentManager", cm) //
				.addReference("sum", sum) //
				.addReference("predictorManager",
						consumptionSplitPredictor(cm, sum, Instant.now(clock), prod, unmanaged, total)) //
				.addReference("heatPump", new DummyHeatShiHeatPump("heatPump0")) //
				.addComponent(new DummyManagedSymmetricEss("ess0") //
						.setPower(new DummyPower(10_000))) //
				.activate(MyConfig.create() //
						.setId("ctrl0") //
						.setHeatPumpId("heatPump0") //
						.setEssId("ess0") //
						.setHeatPumpPosition(HeatPumpPosition.GRID_SIDE_OF_GRID_METER) //
						.setMinSoc(15) //
						.build()) // MAX_DEFICIT + 120 % buffer are the defaults
				// The substituted total value (4000 W) is the ONLY load and must drive
				// the reserve exactly: 4000 W over a quarter = 1000 Wh, x 120 % = 1200 Wh.
				.next(new TestCase("Fallback value drives the night reserve exactly") //
						.input("_sum", Sum.ChannelId.GRID_ACTIVE_POWER, 0) //
						.input("_sum", Sum.ChannelId.ESS_DISCHARGE_POWER, 0) //
						.input("_sum", Sum.ChannelId.ESS_ACTIVE_POWER, 0) //
						.input("_sum", Sum.ChannelId.ESS_SOC, 65) //
						.input("_sum", Sum.ChannelId.ESS_CAPACITY, 10_000) //
						.input("heatPump0", ElectricityMeter.ChannelId.ACTIVE_POWER, 0) //
						.output(ControllerShiHeatPump.ChannelId.NO_PREDICTION_AVAILABLE, false) //
						.output(ControllerShiHeatPump.ChannelId.NIGHT_RESERVE_ENERGY, 1200)) //
				.deactivate();
	}

	@Test
	void testDisabledEssSupportStillBoostsWithoutSupport() throws Exception {
		var clock = createDummyClock();
		var cm = new DummyComponentManager(clock);
		var sum = new DummySum();
		new ControllerTest(new ControllerShiHeatPumpImpl()) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("componentManager", cm) //
				.addReference("sum", sum) //
				.addReference("predictorManager", sunnyPredictor(cm, sum, Instant.now(clock))) //
				.addReference("heatPump", new DummyHeatShiHeatPump("heatPump0")) //
				.addComponent(new DummyManagedSymmetricEss("ess0") //
						.setPower(new DummyPower(10_000))) //
				.activate(MyConfig.create() //
						.setId("ctrl0") //
						.setHeatPumpId("heatPump0") //
						.setEssId("ess0") //
						.setHeatPumpPosition(HeatPumpPosition.GRID_SIDE_OF_GRID_METER) //
						.setEssSupportEnabled(false) //
						.build()) //
				// Support disabled -> the battery contributes nothing (no free energy
				// credited, no forced export). The boost itself still runs on the PV
				// surplus alone - disabling battery support must not disable the boost.
				.next(new TestCase("Disabled support: boost on surplus, no forced export") //
						.input("_sum", Sum.ChannelId.GRID_ACTIVE_POWER, -4000) //
						.input("_sum", Sum.ChannelId.ESS_DISCHARGE_POWER, 0) //
						.input("_sum", Sum.ChannelId.ESS_SOC, 65) //
						.input("_sum", Sum.ChannelId.ESS_CAPACITY, 10_000) //
						.input("heatPump0", ElectricityMeter.ChannelId.ACTIVE_POWER, 2000) //
						.output(ControllerShiHeatPump.ChannelId.ELEVATED_MODE_ACTIVE, true) //
						.output(ControllerShiHeatPump.ChannelId.FREE_BATTERY_ENERGY, 0) //
						.output(ControllerShiHeatPump.ChannelId.ESS_FORCED_EXPORT_POWER, 0)) //
				.deactivate();
	}

	@Test
	void testDeactivateReleasesHeatPump() throws Exception {
		var clock = createDummyClock();
		var heatPump = new DummyHeatShiHeatPump("heatPump0");
		var test = new ControllerTest(new ControllerShiHeatPumpImpl()) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("componentManager", new DummyComponentManager(clock)) //
				.addReference("sum", new DummySum()) //
				.addReference("heatPump", heatPump) //
				.addComponent(new DummyManagedSymmetricEss("ess0") //
						.setPower(new DummyPower(10_000))) //
				.activate(MyConfig.create() //
						.setId("ctrl0") //
						.setHeatPumpId("heatPump0") //
						.setEssId("ess0") //
						.setHeatPumpPosition(HeatPumpPosition.GRID_SIDE_OF_GRID_METER) //
						.build()) //
				// Raise the setpoints via a boost on strong export first.
				.next(new TestCase("Strong export: elevated") //
						.input("_sum", Sum.ChannelId.GRID_ACTIVE_POWER, -4000) //
						.input("_sum", Sum.ChannelId.ESS_DISCHARGE_POWER, 0) //
						.input("heatPump0", ElectricityMeter.ChannelId.ACTIVE_POWER, 0) //
						.output("heatPump0", HeatShiHeatPump.ChannelId.HEATING_MODE, HeatShiHeatPump.MODE_SETPOINT) //
						.output(ControllerShiHeatPump.ChannelId.ELEVATED_MODE_ACTIVE, true));
		// Deactivating the Controller must release all external influence, so no
		// setpoint elevation persists on the still-connected heat pump.
		test.deactivate();
		assertEquals(Integer.valueOf(HeatShiHeatPump.MODE_NONE),
				heatPump.getHeatingModeChannel().getNextWriteValue().orElse(null));
		assertEquals(Integer.valueOf(HeatShiHeatPump.MODE_NONE),
				heatPump.getHotWaterModeChannel().getNextWriteValue().orElse(null));
		assertEquals(Integer.valueOf(HeatShiHeatPump.LPC_MODE_NONE),
				heatPump.getLpcModeChannel().getNextWriteValue().orElse(null));
		assertEquals(Integer.valueOf(0), heatPump.getPcLimitChannel().getNextWriteValue().orElse(null));
	}

	@Test
	void testPowerMeasurementUnavailableBlocksBoost() throws Exception {
		var clock = createDummyClock();
		var cm = new DummyComponentManager(clock);
		var sum = new DummySum();
		new ControllerTest(new ControllerShiHeatPumpImpl()) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("componentManager", cm) //
				.addReference("sum", sum) //
				.addReference("predictorManager", sunnyPredictor(cm, sum, Instant.now(clock))) //
				.addReference("heatPump", new DummyHeatShiHeatPump("heatPump0") //
						.withMeterType(MeterType.CONSUMPTION_METERED)) //
				.addComponent(new DummyManagedSymmetricEss("ess0") //
						.setPower(new DummyPower(10_000))) //
				.activate(MyConfig.create() //
						.setId("ctrl0") //
						.setHeatPumpId("heatPump0") //
						.setEssId("ess0") //
						.setHeatPumpPosition(HeatPumpPosition.BEHIND_GRID_METER) //
						.build()) //
				// Grid and ESS power are not available (channels undefined). The heat pump
				// reports 3 kW - behind the meter the surplus formula would read the
				// missing values as 0 W and fake a 3 kW surplus. Fail-safe: no boost, no
				// support, warning raised.
				.next(new TestCase("Missing grid/ESS measurement: no boost despite reported heat-pump power") //
						.input("_sum", Sum.ChannelId.ESS_SOC, 65) //
						.input("_sum", Sum.ChannelId.ESS_CAPACITY, 10_000) //
						.input("heatPump0", ElectricityMeter.ChannelId.ACTIVE_POWER, 3000) //
						.output("heatPump0", HeatShiHeatPump.ChannelId.HEATING_MODE, 0) //
						.output(ControllerShiHeatPump.ChannelId.ELEVATED_MODE_ACTIVE, false) //
						.output(ControllerShiHeatPump.ChannelId.POWER_MEASUREMENT_UNAVAILABLE, true) //
						.output(ControllerShiHeatPump.ChannelId.ESS_SUPPORT_POWER, 0)) //
				.deactivate();
	}

	@Test
	void testRunExtensionReleasesOnCoverageLossWithReentryLock() throws Exception {
		var clock = createDummyClock();
		var cm = new DummyComponentManager(clock);
		var sum = new DummySum();
		new ControllerTest(new ControllerShiHeatPumpImpl()) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("componentManager", cm) //
				.addReference("sum", sum) //
				.addReference("predictorManager", sunnyPredictor(cm, sum, Instant.now(clock))) //
				.addReference("heatPump", new DummyHeatShiHeatPump("heatPump0")) //
				// Weak battery: 500 W deliverable.
				.addComponent(new DummyManagedSymmetricEss("ess0") //
						.setPower(new DummyPower(500))) //
				.activate(MyConfig.create() //
						.setId("ctrl0") //
						.setHeatPumpId("heatPump0") //
						.setEssId("ess0") //
						.setHeatPumpPosition(HeatPumpPosition.GRID_SIDE_OF_GRID_METER) //
						.setMinimumSurplusPowerForElevatedMode(5000) //
						.build()) //
				// Natural 2500 W hot-water run, coverage 2500 surplus + 500 battery = 3000
				// > 2500 + margin -> extension starts.
				.next(new TestCase("Covered with margin: extension starts") //
						.input("_sum", Sum.ChannelId.GRID_ACTIVE_POWER, -2500) //
						.input("_sum", Sum.ChannelId.ESS_DISCHARGE_POWER, 0) //
						.input("_sum", Sum.ChannelId.ESS_ACTIVE_POWER, 0) //
						.input("_sum", Sum.ChannelId.ESS_SOC, 65) //
						.input("_sum", Sum.ChannelId.ESS_CAPACITY, 10_000) //
						.input("heatPump0", ElectricityMeter.ChannelId.ACTIVE_POWER, 2500) //
						.input("heatPump0", HeatShiHeatPump.ChannelId.OPERATING_MODE_STATUS, 1) //
						.input("heatPump0", HeatShiHeatPump.ChannelId.HOT_WATER_STATUS, 3) //
						.input("heatPump0", HeatShiHeatPump.ChannelId.HOT_WATER_MODE, 0) //
						.input("heatPump0", HeatShiHeatPump.ChannelId.HOT_WATER_ACTIVE_SETPOINT, 480) //
						.output("heatPump0", HeatShiHeatPump.ChannelId.HOT_WATER_MODE, 1) //
						.output(ControllerShiHeatPump.ChannelId.RUN_EXTENSION_ACTIVE, true)) //
				// Coverage drops below the heat-pump power: released immediately (the soft
				// limit is only a recommendation, so no holding while uncovered).
				.next(new TestCase("Coverage lost: extension released immediately") //
						.input("_sum", Sum.ChannelId.GRID_ACTIVE_POWER, -1000) //
						.output("heatPump0", HeatShiHeatPump.ChannelId.HOT_WATER_MODE, 0) //
						.output(ControllerShiHeatPump.ChannelId.RUN_EXTENSION_ACTIVE, false)) //
				// Coverage returns immediately, but re-entry is locked for the minimum
				// switching time -> no restart yet (prevents toggling).
				.next(new TestCase("Coverage back but re-entry locked: no restart") //
						.input("_sum", Sum.ChannelId.GRID_ACTIVE_POWER, -2500) //
						.output(ControllerShiHeatPump.ChannelId.RUN_EXTENSION_ACTIVE, false)) //
				// After the re-entry lock elapses, the extension may start again.
				.next(new TestCase("Re-entry lock elapsed: extension restarts") //
						.timeleap(clock, 6, ChronoUnit.MINUTES) //
						.input("_sum", Sum.ChannelId.GRID_ACTIVE_POWER, -2500) //
						.output("heatPump0", HeatShiHeatPump.ChannelId.HOT_WATER_MODE, 1) //
						.output(ControllerShiHeatPump.ChannelId.RUN_EXTENSION_ACTIVE, true)) //
				.deactivate();
	}

	@Test
	void testMeasurementDropoutDuringExtensionArmsReentryLock() throws Exception {
		var clock = createDummyClock();
		var cm = new DummyComponentManager(clock);
		var sum = new DummySum();
		new ControllerTest(new ControllerShiHeatPumpImpl()) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("componentManager", cm) //
				.addReference("sum", sum) //
				.addReference("predictorManager", sunnyPredictor(cm, sum, Instant.now(clock))) //
				.addReference("heatPump", new DummyHeatShiHeatPump("heatPump0")) //
				.addComponent(new DummyManagedSymmetricEss("ess0") //
						.setPower(new DummyPower(500))) //
				.activate(MyConfig.create() //
						.setId("ctrl0") //
						.setHeatPumpId("heatPump0") //
						.setEssId("ess0") //
						.setHeatPumpPosition(HeatPumpPosition.GRID_SIDE_OF_GRID_METER) //
						.setMinimumSurplusPowerForElevatedMode(5000) //
						.build()) //
				.next(new TestCase("Covered with margin: extension starts") //
						.input("_sum", Sum.ChannelId.GRID_ACTIVE_POWER, -2500) //
						.input("_sum", Sum.ChannelId.ESS_DISCHARGE_POWER, 0) //
						.input("_sum", Sum.ChannelId.ESS_ACTIVE_POWER, 0) //
						.input("_sum", Sum.ChannelId.ESS_SOC, 65) //
						.input("_sum", Sum.ChannelId.ESS_CAPACITY, 10_000) //
						.input("heatPump0", ElectricityMeter.ChannelId.ACTIVE_POWER, 2500) //
						.input("heatPump0", HeatShiHeatPump.ChannelId.OPERATING_MODE_STATUS, 1) //
						.input("heatPump0", HeatShiHeatPump.ChannelId.HOT_WATER_STATUS, 3) //
						.input("heatPump0", HeatShiHeatPump.ChannelId.HOT_WATER_MODE, 0) //
						.input("heatPump0", HeatShiHeatPump.ChannelId.HOT_WATER_ACTIVE_SETPOINT, 480) //
						.output(ControllerShiHeatPump.ChannelId.RUN_EXTENSION_ACTIVE, true)) //
				// Grid measurement drops out: fail-safe releases the extension and the
				// diagnostic channel must read false immediately.
				.next(new TestCase("Grid measurement lost: extension released, channel false") //
						.input("_sum", Sum.ChannelId.GRID_ACTIVE_POWER, null) //
						.output(ControllerShiHeatPump.ChannelId.POWER_MEASUREMENT_UNAVAILABLE, true) //
						.output(ControllerShiHeatPump.ChannelId.RUN_EXTENSION_ACTIVE, false)) //
				// Measurement returns with good coverage, but the re-entry lock (armed by
				// the release) prevents an immediate restart - so flicker cannot toggle.
				.next(new TestCase("Measurement back but re-entry locked: no restart") //
						.input("_sum", Sum.ChannelId.GRID_ACTIVE_POWER, -2500) //
						.output(ControllerShiHeatPump.ChannelId.POWER_MEASUREMENT_UNAVAILABLE, false) //
						.output(ControllerShiHeatPump.ChannelId.RUN_EXTENSION_ACTIVE, false)) //
				// After the re-entry lock elapses, the extension may start again.
				.next(new TestCase("Re-entry lock elapsed: extension restarts") //
						.timeleap(clock, 6, ChronoUnit.MINUTES) //
						.input("_sum", Sum.ChannelId.GRID_ACTIVE_POWER, -2500) //
						.output(ControllerShiHeatPump.ChannelId.RUN_EXTENSION_ACTIVE, true)) //
				.deactivate();
	}

	@Test
	void testShortForecastDisablesSupport() throws Exception {
		var clock = createDummyClock();
		var cm = new DummyComponentManager(clock);
		var sum = new DummySum();
		// The forecast has no interior gap, but only covers the next hour (4
		// quarters). It cannot back the overnight reserve, so it counts as no
		// prediction and no battery energy is released.
		var prod = new Integer[96];
		Arrays.fill(prod, 5000);
		var cons = new Integer[4];
		Arrays.fill(cons, 500);
		var predictorManager = new DummyPredictorManager(//
				new DummyPredictor("predictor0", cm, Prediction.from(sum, SUM_PRODUCTION_ACTIVE_POWER, Instant.now(clock),
						prod), SUM_PRODUCTION_ACTIVE_POWER),
				new DummyPredictor("predictor1", cm, Prediction.from(sum, SUM_CONSUMPTION_ACTIVE_POWER, Instant.now(clock),
						cons), SUM_CONSUMPTION_ACTIVE_POWER));
		new ControllerTest(new ControllerShiHeatPumpImpl()) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("componentManager", cm) //
				.addReference("sum", sum) //
				.addReference("predictorManager", predictorManager) //
				.addReference("heatPump", new DummyHeatShiHeatPump("heatPump0")) //
				.addComponent(new DummyManagedSymmetricEss("ess0") //
						.setPower(new DummyPower(10_000))) //
				.activate(MyConfig.create() //
						.setId("ctrl0") //
						.setHeatPumpId("heatPump0") //
						.setEssId("ess0") //
						.setHeatPumpPosition(HeatPumpPosition.GRID_SIDE_OF_GRID_METER) //
						.setMinimumSurplusPowerForElevatedMode(5000) //
						.build()) //
				.next(new TestCase("Forecast too short for the night: no released energy, no support") //
						.input("_sum", Sum.ChannelId.GRID_ACTIVE_POWER, 0) //
						.input("_sum", Sum.ChannelId.ESS_DISCHARGE_POWER, 0) //
						.input("_sum", Sum.ChannelId.ESS_ACTIVE_POWER, 0) //
						.input("_sum", Sum.ChannelId.ESS_SOC, 65) //
						.input("_sum", Sum.ChannelId.ESS_CAPACITY, 10_000) //
						.input("heatPump0", ElectricityMeter.ChannelId.ACTIVE_POWER, 2000) //
						.output(ControllerShiHeatPump.ChannelId.NO_PREDICTION_AVAILABLE, true) //
						.output(ControllerShiHeatPump.ChannelId.FREE_BATTERY_ENERGY, 0) //
						.output(ControllerShiHeatPump.ChannelId.ESS_FORCED_EXPORT_POWER, 0)) //
				.deactivate();
	}

	@Test
	void testConsumptionForecastGapDisablesSupport() throws Exception {
		var clock = createDummyClock();
		final var cm = new DummyComponentManager(clock);
		final var sum = new DummySum();
		// Full production forecast, but the consumption forecast has a gap (a null
		// quarter) and no Unmanaged forecast to fall back to, so the missing quarter
		// stays unavailable -> the forecast counts as incomplete and no battery energy
		// is released.
		var prod = new Integer[96];
		var cons = new Integer[96];
		Arrays.fill(prod, 5000);
		Arrays.fill(cons, 500);
		cons[10] = null;
		var predictorManager = new DummyPredictorManager(//
				new DummyPredictor("predictor0", cm, Prediction.from(sum, SUM_PRODUCTION_ACTIVE_POWER, Instant.now(clock),
						prod), SUM_PRODUCTION_ACTIVE_POWER),
				new DummyPredictor("predictor1", cm, Prediction.from(sum, SUM_CONSUMPTION_ACTIVE_POWER, Instant.now(clock),
						cons), SUM_CONSUMPTION_ACTIVE_POWER));
		new ControllerTest(new ControllerShiHeatPumpImpl()) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("componentManager", cm) //
				.addReference("sum", sum) //
				.addReference("predictorManager", predictorManager) //
				.addReference("heatPump", new DummyHeatShiHeatPump("heatPump0")) //
				.addComponent(new DummyManagedSymmetricEss("ess0") //
						.setPower(new DummyPower(10_000))) //
				.activate(MyConfig.create() //
						.setId("ctrl0") //
						.setHeatPumpId("heatPump0") //
						.setEssId("ess0") //
						.setHeatPumpPosition(HeatPumpPosition.GRID_SIDE_OF_GRID_METER) //
						.setMinimumSurplusPowerForElevatedMode(5000) //
						.build()) //
				.next(new TestCase("Consumption forecast gap: no released energy, no support") //
						.input("_sum", Sum.ChannelId.GRID_ACTIVE_POWER, 0) //
						.input("_sum", Sum.ChannelId.ESS_DISCHARGE_POWER, 0) //
						.input("_sum", Sum.ChannelId.ESS_ACTIVE_POWER, 0) //
						.input("_sum", Sum.ChannelId.ESS_SOC, 65) //
						.input("_sum", Sum.ChannelId.ESS_CAPACITY, 10_000) //
						.input("heatPump0", ElectricityMeter.ChannelId.ACTIVE_POWER, 2000) //
						.output(ControllerShiHeatPump.ChannelId.ELEVATED_MODE_ACTIVE, false) //
						.output(ControllerShiHeatPump.ChannelId.NO_PREDICTION_AVAILABLE, true) //
						.output(ControllerShiHeatPump.ChannelId.FREE_BATTERY_ENERGY, 0) //
						.output(ControllerShiHeatPump.ChannelId.ESS_FORCED_EXPORT_POWER, 0)) //
				.deactivate();
	}

	@Test
	void testNightReserveMaxDeficitBlocksMorningEnergy() throws Exception {
		var clock = createDummyClock();
		var cm = new DummyComponentManager(clock);
		var sum = new DummySum();
		new ControllerTest(new ControllerShiHeatPumpImpl()) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("componentManager", cm) //
				.addReference("sum", sum) //
				.addReference("predictorManager", morningRecoveryPredictor(cm, sum, Instant.now(clock))) //
				.addReference("heatPump", new DummyHeatShiHeatPump("heatPump0")) //
				.addComponent(new DummyManagedSymmetricEss("ess0") //
						.setPower(new DummyPower(10_000))) //
				.activate(MyConfig.create() //
						.setId("ctrl0") //
						.setHeatPumpId("heatPump0") //
						.setEssId("ess0") //
						.setHeatPumpPosition(HeatPumpPosition.GRID_SIDE_OF_GRID_METER) //
						.setMinSoc(15) //
						.setNightReserveBuffer(100) //
						.setNightReserveMode(NightReserveMode.MAX_DEFICIT) //
						.build()) //
				// Low morning SoC (25 % -> 1000 Wh usable). The overnight deficit is far
				// larger, so MAX_DEFICIT reserves everything -> nothing free.
				.next(new TestCase("Max-deficit reserve blocks the morning") //
						.input("_sum", Sum.ChannelId.GRID_ACTIVE_POWER, 0) //
						.input("_sum", Sum.ChannelId.ESS_DISCHARGE_POWER, 0) //
						.input("_sum", Sum.ChannelId.ESS_ACTIVE_POWER, 0) //
						.input("_sum", Sum.ChannelId.ESS_SOC, 25) //
						.input("_sum", Sum.ChannelId.ESS_CAPACITY, 10_000) //
						.input("heatPump0", ElectricityMeter.ChannelId.ACTIVE_POWER, 0) //
						.output(ControllerShiHeatPump.ChannelId.FREE_BATTERY_ENERGY, 0)) //
				.deactivate();
	}

	@Test
	void testNightReserveTrajectoryFreesMorningEnergy() throws Exception {
		var clock = createDummyClock();
		var cm = new DummyComponentManager(clock);
		var sum = new DummySum();
		new ControllerTest(new ControllerShiHeatPumpImpl()) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("componentManager", cm) //
				.addReference("sum", sum) //
				.addReference("predictorManager", morningRecoveryPredictor(cm, sum, Instant.now(clock))) //
				.addReference("heatPump", new DummyHeatShiHeatPump("heatPump0")) //
				.addComponent(new DummyManagedSymmetricEss("ess0") //
						.setPower(new DummyPower(10_000))) //
				.activate(MyConfig.create() //
						.setId("ctrl0") //
						.setHeatPumpId("heatPump0") //
						.setEssId("ess0") //
						.setHeatPumpPosition(HeatPumpPosition.GRID_SIDE_OF_GRID_METER) //
						.setMinSoc(15) //
						.setNightReserveBuffer(100) //
						.setNightReserveMode(NightReserveMode.SOC_TRAJECTORY) //
						.build()) //
				// Same forecast and SoC as above, no buffer margin: the daytime PV
				// refills the battery before the evening, so the trajectory frees the
				// full 1000 Wh now.
				.next(new TestCase("Trajectory reserve frees the morning") //
						.input("_sum", Sum.ChannelId.GRID_ACTIVE_POWER, 0) //
						.input("_sum", Sum.ChannelId.ESS_DISCHARGE_POWER, 0) //
						.input("_sum", Sum.ChannelId.ESS_ACTIVE_POWER, 0) //
						.input("_sum", Sum.ChannelId.ESS_SOC, 25) //
						.input("_sum", Sum.ChannelId.ESS_CAPACITY, 10_000) //
						.input("heatPump0", ElectricityMeter.ChannelId.ACTIVE_POWER, 0) //
						.output(ControllerShiHeatPump.ChannelId.FREE_BATTERY_ENERGY, 1000)) //
				.deactivate();
	}

	@Test
	void testNightReserveTrajectoryBufferHoldsBack() throws Exception {
		var clock = createDummyClock();
		var cm = new DummyComponentManager(clock);
		var sum = new DummySum();
		new ControllerTest(new ControllerShiHeatPumpImpl()) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("componentManager", cm) //
				.addReference("sum", sum) //
				.addReference("predictorManager", morningRecoveryPredictor(cm, sum, Instant.now(clock))) //
				.addReference("heatPump", new DummyHeatShiHeatPump("heatPump0")) //
				.addComponent(new DummyManagedSymmetricEss("ess0") //
						.setPower(new DummyPower(10_000))) //
				.activate(MyConfig.create() //
						.setId("ctrl0") //
						.setHeatPumpId("heatPump0") //
						.setEssId("ess0") //
						.setHeatPumpPosition(HeatPumpPosition.GRID_SIDE_OF_GRID_METER) //
						.setMinSoc(15) //
						.setNightReserveBuffer(120) //
						.setNightReserveMode(NightReserveMode.SOC_TRAJECTORY) //
						.build()) //
				// Same forecast as the freeing test, but with a 120 % buffer. The forecast
				// only just covers the night (ends near Min-SoC), so it does not leave the
				// buffer cushion on top -> the trajectory frees nothing, even though the
				// nominal reserve would be 0. This is the safety margin the buffer must
				// provide when relying on the future PV recharge.
				.next(new TestCase("Buffer cushion holds back the razor-thin forecast") //
						.input("_sum", Sum.ChannelId.GRID_ACTIVE_POWER, 0) //
						.input("_sum", Sum.ChannelId.ESS_DISCHARGE_POWER, 0) //
						.input("_sum", Sum.ChannelId.ESS_ACTIVE_POWER, 0) //
						.input("_sum", Sum.ChannelId.ESS_SOC, 25) //
						.input("_sum", Sum.ChannelId.ESS_CAPACITY, 10_000) //
						.input("heatPump0", ElectricityMeter.ChannelId.ACTIVE_POWER, 0) //
						.output(ControllerShiHeatPump.ChannelId.FREE_BATTERY_ENERGY, 0)) //
				.deactivate();
	}

	@Test
	void testCloudBufferBoostFollowsSunNotBattery() throws Exception {
		var clock = createDummyClock();
		var cm = new DummyComponentManager(clock);
		var sum = new DummySum();
		new ControllerTest(new ControllerShiHeatPumpImpl()) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("componentManager", cm) //
				.addReference("sum", sum) //
				.addReference("predictorManager", sunnyPredictor(cm, sum, Instant.now(clock))) //
				.addReference("heatPump", new DummyHeatShiHeatPump("heatPump0")) //
				.addComponent(new DummyManagedSymmetricEss("ess0") //
						.setPower(new DummyPower(10_000))) //
				.activate(MyConfig.create() //
						.setId("ctrl0") //
						.setHeatPumpId("heatPump0") //
						.setEssId("ess0") //
						.setHeatPumpPosition(HeatPumpPosition.GRID_SIDE_OF_GRID_METER) //
						.setBatterySupportMode(BatterySupportMode.CLOUD_BUFFER) //
						.build()) //
				// 3000 W export, plenty of free battery energy. In CLOUD_BUFFER the boost
				// soft limit is the surplus ALONE (3000 W), not surplus + battery - the
				// heat pump follows the sun and is not driven harder by the battery.
				.next(new TestCase("Cloud-buffer boost limits to the surplus") //
						.input("_sum", Sum.ChannelId.GRID_ACTIVE_POWER, -3000) //
						.input("_sum", Sum.ChannelId.ESS_DISCHARGE_POWER, 0) //
						.input("_sum", Sum.ChannelId.ESS_ACTIVE_POWER, 0) //
						.input("_sum", Sum.ChannelId.ESS_SOC, 65) //
						.input("_sum", Sum.ChannelId.ESS_CAPACITY, 10_000) //
						.input("heatPump0", ElectricityMeter.ChannelId.ACTIVE_POWER, 0) //
						.output("heatPump0", HeatShiHeatPump.ChannelId.PC_LIMIT, 3000) //
						.output(ControllerShiHeatPump.ChannelId.ELEVATED_MODE_ACTIVE, true)) //
				.deactivate();
	}

	@Test
	void testCloudBufferFundsNaturalRunButNoExtension() throws Exception {
		var clock = createDummyClock();
		var cm = new DummyComponentManager(clock);
		var sum = new DummySum();
		new ControllerTest(new ControllerShiHeatPumpImpl()) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("componentManager", cm) //
				.addReference("sum", sum) //
				.addReference("predictorManager", sunnyPredictor(cm, sum, Instant.now(clock))) //
				.addReference("heatPump", new DummyHeatShiHeatPump("heatPump0")) //
				.addComponent(new DummyManagedSymmetricEss("ess0") //
						.setPower(new DummyPower(10_000))) //
				.activate(MyConfig.create() //
						.setId("ctrl0") //
						.setHeatPumpId("heatPump0") //
						.setEssId("ess0") //
						.setHeatPumpPosition(HeatPumpPosition.GRID_SIDE_OF_GRID_METER) //
						.setBatterySupportMode(BatterySupportMode.CLOUD_BUFFER) //
						.setMinimumSurplusPowerForElevatedMode(5000) //
						.build()) //
				// Natural 2000 W hot-water run, no PV. In CLOUD_BUFFER the battery is not
				// invited to drive the extension (no surplus -> no extension), but the
				// passive support still pays for the run the heat pump does anyway.
				.next(new TestCase("Cloud-buffer: run funded, not extended") //
						.input("_sum", Sum.ChannelId.GRID_ACTIVE_POWER, 0) //
						.input("_sum", Sum.ChannelId.ESS_DISCHARGE_POWER, 0) //
						.input("_sum", Sum.ChannelId.ESS_ACTIVE_POWER, 0) //
						.input("_sum", Sum.ChannelId.ESS_SOC, 65) //
						.input("_sum", Sum.ChannelId.ESS_CAPACITY, 10_000) //
						.input("heatPump0", ElectricityMeter.ChannelId.ACTIVE_POWER, 2000) //
						.input("heatPump0", HeatShiHeatPump.ChannelId.OPERATING_MODE_STATUS, 1) //
						.input("heatPump0", HeatShiHeatPump.ChannelId.HOT_WATER_STATUS, 3) //
						.input("heatPump0", HeatShiHeatPump.ChannelId.HOT_WATER_MODE, 0) //
						.input("heatPump0", HeatShiHeatPump.ChannelId.HOT_WATER_ACTIVE_SETPOINT, 480) //
						.output(ControllerShiHeatPump.ChannelId.ELEVATED_MODE_ACTIVE, false) //
						.output(ControllerShiHeatPump.ChannelId.RUN_EXTENSION_ACTIVE, false) //
						.output(ControllerShiHeatPump.ChannelId.ESS_FORCED_EXPORT_POWER, 2000)) //
				.deactivate();
	}

	@Test
	void testControlNotAllowedWarningOnReadOnlyDevice() throws Exception {
		var clock = createDummyClock();
		new ControllerTest(new ControllerShiHeatPumpImpl()) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("componentManager", new DummyComponentManager(clock)) //
				.addReference("sum", new DummySum()) //
				.addReference("heatPump", new DummyHeatShiHeatPump("heatPump0")) //
				.addComponent(new DummyManagedSymmetricEss("ess0") //
						.setPower(new DummyPower(10_000))) //
				.activate(MyConfig.create() //
						.setId("ctrl0") //
						.setHeatPumpId("heatPump0") //
						.setEssId("ess0") //
						.setHeatPumpPosition(HeatPumpPosition.GRID_SIDE_OF_GRID_METER) //
						.build()) //
				.next(new TestCase("Device in read-only mode: warning raised") //
						.input("heatPump0", HeatShiHeatPump.ChannelId.READ_ONLY_MODE, true) //
						.output(ControllerShiHeatPump.ChannelId.CONTROL_NOT_ALLOWED, true)) //
				.next(new TestCase("Device writable: warning cleared") //
						.input("heatPump0", HeatShiHeatPump.ChannelId.READ_ONLY_MODE, false) //
						.output(ControllerShiHeatPump.ChannelId.CONTROL_NOT_ALLOWED, false)) //
				.deactivate();
	}

	@Test
	void testMeterTypeMismatchWarning() throws Exception {
		var clock = createDummyClock();
		new ControllerTest(new ControllerShiHeatPumpImpl()) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("componentManager", new DummyComponentManager(clock)) //
				.addReference("sum", new DummySum()) //
				// Dummy heat pump defaults to CONSUMPTION_NOT_METERED, which does not
				// match position BEHIND_GRID_METER
				.addReference("heatPump", new DummyHeatShiHeatPump("heatPump0")) //
				.addComponent(new DummyManagedSymmetricEss("ess0") //
						.setPower(new DummyPower(10_000))) //
				.activate(MyConfig.create() //
						.setId("ctrl0") //
						.setHeatPumpId("heatPump0") //
						.setEssId("ess0") //
						.setHeatPumpPosition(HeatPumpPosition.BEHIND_GRID_METER) //
						.build()) //
				.next(new TestCase("Meter-Type does not match heat pump position") //
						.output(ControllerShiHeatPump.ChannelId.METER_TYPE_MISMATCH, true)) //
				.deactivate();
	}
}
