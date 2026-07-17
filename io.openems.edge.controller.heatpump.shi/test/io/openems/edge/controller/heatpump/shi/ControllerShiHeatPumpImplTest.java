package io.openems.edge.controller.heatpump.shi;

import static io.openems.common.test.TestUtils.createDummyClock;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

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

	@Test
	void testElevatedModeOnGridExportWithHysteresis() throws Exception {
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
				// Heat pump sits grid-side of the grid meter: export is what it may consume
				.next(new TestCase("Grid export above minimum: elevated mode starts") //
						.input("_sum", Sum.ChannelId.GRID_ACTIVE_POWER, -4000) //
						.input("_sum", Sum.ChannelId.ESS_DISCHARGE_POWER, 0) //
						.input("heatPump0", ElectricityMeter.ChannelId.ACTIVE_POWER, 500) //
						.output("heatPump0", HeatShiHeatPump.ChannelId.HEATING_MODE, 1) //
						.output("heatPump0", HeatShiHeatPump.ChannelId.HEATING_SETPOINT, 550) //
						.output("heatPump0", HeatShiHeatPump.ChannelId.HOT_WATER_MODE, 1) //
						.output("heatPump0", HeatShiHeatPump.ChannelId.HOT_WATER_SETPOINT, 550) //
						.output("heatPump0", HeatShiHeatPump.ChannelId.LPC_MODE, 1) //
						.output("heatPump0", HeatShiHeatPump.ChannelId.PC_LIMIT, 4000) //
						.output("ess0", ManagedSymmetricEss.ChannelId.SET_ACTIVE_POWER_GREATER_OR_EQUALS, null) //
						.output(ControllerShiHeatPump.ChannelId.ELEVATED_MODE_ACTIVE, true) //
						.output(ControllerShiHeatPump.ChannelId.ESS_FORCED_EXPORT_POWER, 0)) //
				// Battery discharge for the household does not count as surplus. The
				// soft power limit stays at the minimum power during the hysteresis,
				// so a short surplus dip does not shut down the compressor
				.next(new TestCase("Export gone: hysteresis keeps elevated mode") //
						.input("_sum", Sum.ChannelId.GRID_ACTIVE_POWER, 0) //
						.input("_sum", Sum.ChannelId.ESS_DISCHARGE_POWER, 1000) //
						.input("heatPump0", ElectricityMeter.ChannelId.ACTIVE_POWER, 500) //
						.output("heatPump0", HeatShiHeatPump.ChannelId.HEATING_MODE, 1) //
						.output("heatPump0", HeatShiHeatPump.ChannelId.PC_LIMIT, 2500) //
						.output(ControllerShiHeatPump.ChannelId.ELEVATED_MODE_ACTIVE, true)) //
				.next(new TestCase("Hysteresis expired: back to normal mode") //
						.timeleap(clock, 6, ChronoUnit.MINUTES) //
						.input("_sum", Sum.ChannelId.GRID_ACTIVE_POWER, 0) //
						.input("_sum", Sum.ChannelId.ESS_DISCHARGE_POWER, 1000) //
						.input("heatPump0", ElectricityMeter.ChannelId.ACTIVE_POWER, 500) //
						.output("heatPump0", HeatShiHeatPump.ChannelId.HEATING_MODE, 0) //
						.output("heatPump0", HeatShiHeatPump.ChannelId.HOT_WATER_MODE, 0) //
						.output("heatPump0", HeatShiHeatPump.ChannelId.LPC_MODE, 0) //
						.output("ess0", ManagedSymmetricEss.ChannelId.SET_ACTIVE_POWER_GREATER_OR_EQUALS, null) //
						.output(ControllerShiHeatPump.ChannelId.ELEVATED_MODE_ACTIVE, false)) //
				.deactivate();
	}

	@Test
	void testBoostConfirmationDelaysEntry() throws Exception {
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
						.setBoostConfirmationSeconds(240) //
						.build()) //
				.next(new TestCase("Conditions fulfilled: pending, not yet elevated") //
						.input("_sum", Sum.ChannelId.GRID_ACTIVE_POWER, -4000) //
						.input("_sum", Sum.ChannelId.ESS_DISCHARGE_POWER, 0) //
						.input("heatPump0", ElectricityMeter.ChannelId.ACTIVE_POWER, 500) //
						.output(ControllerShiHeatPump.ChannelId.ELEVATED_MODE_ACTIVE, false) //
						.output(ControllerShiHeatPump.ChannelId.BOOST_PENDING, true)) //
				.next(new TestCase("After 3 minutes: still pending") //
						.timeleap(clock, 3, ChronoUnit.MINUTES) //
						.input("_sum", Sum.ChannelId.GRID_ACTIVE_POWER, -4000) //
						.output(ControllerShiHeatPump.ChannelId.ELEVATED_MODE_ACTIVE, false) //
						.output(ControllerShiHeatPump.ChannelId.BOOST_PENDING, true)) //
				.next(new TestCase("Surplus dip resets the confirmation") //
						.input("_sum", Sum.ChannelId.GRID_ACTIVE_POWER, 0) //
						.output(ControllerShiHeatPump.ChannelId.ELEVATED_MODE_ACTIVE, false) //
						.output(ControllerShiHeatPump.ChannelId.BOOST_PENDING, false)) //
				.next(new TestCase("Surplus back: confirmation restarts") //
						.input("_sum", Sum.ChannelId.GRID_ACTIVE_POWER, -4000) //
						.output(ControllerShiHeatPump.ChannelId.ELEVATED_MODE_ACTIVE, false) //
						.output(ControllerShiHeatPump.ChannelId.BOOST_PENDING, true)) //
				.next(new TestCase("Confirmation time passed: elevated mode") //
						.timeleap(clock, 5, ChronoUnit.MINUTES) //
						.input("_sum", Sum.ChannelId.GRID_ACTIVE_POWER, -4000) //
						.output(ControllerShiHeatPump.ChannelId.ELEVATED_MODE_ACTIVE, true) //
						.output(ControllerShiHeatPump.ChannelId.BOOST_PENDING, false)) //
				.deactivate();
	}

	@Test
	void testForecastVetoBlocksEntry() throws Exception {
		var clock = createDummyClock();
		var componentManager = new DummyComponentManager(clock);
		var sum = new DummySum();
		var now = Instant.now(clock);

		// Forecast shows no surplus at all
		var productionValues = new Integer[24];
		var consumptionValues = new Integer[24];
		Arrays.fill(productionValues, 0);
		Arrays.fill(consumptionValues, 500);
		var predictorManager = new DummyPredictorManager(//
				new DummyPredictor("predictor0", componentManager,
						Prediction.from(sum, SUM_PRODUCTION_ACTIVE_POWER, now, productionValues),
						SUM_PRODUCTION_ACTIVE_POWER),
				new DummyPredictor("predictor1", componentManager,
						Prediction.from(sum, SUM_CONSUMPTION_ACTIVE_POWER, now, consumptionValues),
						SUM_CONSUMPTION_ACTIVE_POWER));

		new ControllerTest(new ControllerShiHeatPumpImpl()) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("componentManager", componentManager) //
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
				.next(new TestCase("Measured surplus present, but forecast vetoes entry") //
						.input("_sum", Sum.ChannelId.GRID_ACTIVE_POWER, -4000) //
						.input("_sum", Sum.ChannelId.ESS_DISCHARGE_POWER, 0) //
						.input("heatPump0", ElectricityMeter.ChannelId.ACTIVE_POWER, 500) //
						.output(ControllerShiHeatPump.ChannelId.ELEVATED_MODE_ACTIVE, false) //
						.output(ControllerShiHeatPump.ChannelId.BOOST_FORECAST_VETO, true)) //
				.deactivate();
	}

	@Test
	void testForecastVetoAllowsEntryWhenSurplusPredicted() throws Exception {
		var clock = createDummyClock();
		var componentManager = new DummyComponentManager(clock);
		var sum = new DummySum();
		var now = Instant.now(clock);

		// Forecast confirms the surplus
		var productionValues = new Integer[24];
		var consumptionValues = new Integer[24];
		Arrays.fill(productionValues, 5000);
		Arrays.fill(consumptionValues, 500);
		var predictorManager = new DummyPredictorManager(//
				new DummyPredictor("predictor0", componentManager,
						Prediction.from(sum, SUM_PRODUCTION_ACTIVE_POWER, now, productionValues),
						SUM_PRODUCTION_ACTIVE_POWER),
				new DummyPredictor("predictor1", componentManager,
						Prediction.from(sum, SUM_CONSUMPTION_ACTIVE_POWER, now, consumptionValues),
						SUM_CONSUMPTION_ACTIVE_POWER));

		new ControllerTest(new ControllerShiHeatPumpImpl()) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("componentManager", componentManager) //
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
				.next(new TestCase("Forecast confirms surplus: elevated mode starts") //
						.input("_sum", Sum.ChannelId.GRID_ACTIVE_POWER, -4000) //
						.input("_sum", Sum.ChannelId.ESS_DISCHARGE_POWER, 0) //
						.input("heatPump0", ElectricityMeter.ChannelId.ACTIVE_POWER, 500) //
						.output(ControllerShiHeatPump.ChannelId.ELEVATED_MODE_ACTIVE, true) //
						.output(ControllerShiHeatPump.ChannelId.BOOST_FORECAST_VETO, false)) //
				.deactivate();
	}

	@Test
	void testRunExtensionOnNaturalHotWaterRun() throws Exception {
		var clock = createDummyClock();
		var componentManager = new DummyComponentManager(clock);
		var sum = new DummySum();
		var now = Instant.now(clock);

		// Night reserve 3000 Wh -> spare power 2000 W
		var productionValues = new Integer[24];
		var consumptionValues = new Integer[24];
		Arrays.fill(productionValues, 0);
		Arrays.fill(consumptionValues, 500);
		var predictorManager = new DummyPredictorManager(//
				new DummyPredictor("predictor0", componentManager,
						Prediction.from(sum, SUM_PRODUCTION_ACTIVE_POWER, now, productionValues),
						SUM_PRODUCTION_ACTIVE_POWER),
				new DummyPredictor("predictor1", componentManager,
						Prediction.from(sum, SUM_CONSUMPTION_ACTIVE_POWER, now, consumptionValues),
						SUM_CONSUMPTION_ACTIVE_POWER));

		new ControllerTest(new ControllerShiHeatPumpImpl()) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("componentManager", componentManager) //
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
						.setMinimumSurplusPowerForElevatedMode(4000) //
						.setMinSoc(15) //
						.setNightReserveBuffer(100) //
						.setEssSupportDurationMinutes(60) //
						.build()) //
				// Natural hot-water run (2500 W), surplus 800 W + spare 2000 W cover it;
				// natural setpoint 48.0 degC, elevated 55.0 degC -> delta 7 K >= 3 K
				.next(new TestCase("Natural run fully covered: extension starts") //
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
						.output(ControllerShiHeatPump.ChannelId.ELEVATED_MODE_ACTIVE, false) //
						.output(ControllerShiHeatPump.ChannelId.RUN_EXTENSION_ACTIVE, true) //
						.output(ControllerShiHeatPump.ChannelId.NATURAL_HOT_WATER_SETPOINT, 480)) //
				// Heat pump finished the run on its own: extension releases the registers
				.next(new TestCase("Run finished: extension ends") //
						.input("heatPump0", HeatShiHeatPump.ChannelId.HOT_WATER_STATUS, 1) //
						.output("heatPump0", HeatShiHeatPump.ChannelId.HOT_WATER_MODE, 0) //
						.output(ControllerShiHeatPump.ChannelId.RUN_EXTENSION_ACTIVE, false)) //
				.deactivate();
	}

	@Test
	void testRunExtensionSkippedForSmallDelta() throws Exception {
		var clock = createDummyClock();
		var componentManager = new DummyComponentManager(clock);
		var sum = new DummySum();
		var now = Instant.now(clock);

		var productionValues = new Integer[24];
		var consumptionValues = new Integer[24];
		Arrays.fill(productionValues, 0);
		Arrays.fill(consumptionValues, 500);
		var predictorManager = new DummyPredictorManager(//
				new DummyPredictor("predictor0", componentManager,
						Prediction.from(sum, SUM_PRODUCTION_ACTIVE_POWER, now, productionValues),
						SUM_PRODUCTION_ACTIVE_POWER),
				new DummyPredictor("predictor1", componentManager,
						Prediction.from(sum, SUM_CONSUMPTION_ACTIVE_POWER, now, consumptionValues),
						SUM_CONSUMPTION_ACTIVE_POWER));

		new ControllerTest(new ControllerShiHeatPumpImpl()) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("componentManager", componentManager) //
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
						.setMinimumSurplusPowerForElevatedMode(4000) //
						.setMinSoc(15) //
						.setNightReserveBuffer(100) //
						.setEssSupportDurationMinutes(60) //
						.build()) //
				// Natural setpoint 53.0 degC -> delta to 55.0 degC is only 2 K < 3 K
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
	void testHysteresisRespectsCompressorCycleLimits() throws Exception {
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
				// Heat pump reports 20 minutes minimum runtime and restart lock
				.next(new TestCase("Elevated mode starts") //
						.input("heatPump0", HeatShiHeatPump.ChannelId.MIN_RUNTIME, 20) //
						.input("heatPump0", HeatShiHeatPump.ChannelId.MIN_STANDSTILL_TIME, 20) //
						.input("_sum", Sum.ChannelId.GRID_ACTIVE_POWER, -4000) //
						.input("_sum", Sum.ChannelId.ESS_DISCHARGE_POWER, 0) //
						.input("heatPump0", ElectricityMeter.ChannelId.ACTIVE_POWER, 500) //
						.output(ControllerShiHeatPump.ChannelId.ELEVATED_MODE_ACTIVE, true)) //
				// Configured minimum switching time (300 s) has passed, but the
				// compressor minimum runtime (20 min) commits the elevated mode
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
				// Surplus returns, but the compressor restart lock blocks re-entry
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
				// Heating operating mode is "Off" (e.g. summer): the SHI would reject
				// heating commands, so only hot water is influenced
				.next(new TestCase("Heating disabled: only hot water is influenced") //
						.input("_sum", Sum.ChannelId.GRID_ACTIVE_POWER, -4000) //
						.input("_sum", Sum.ChannelId.ESS_DISCHARGE_POWER, 0) //
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
	void testMinPredictedPowerRaisesThreshold() throws Exception {
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
						.setMinimumSurplusPowerForElevatedMode(2500) //
						.build()) //
				// Export satisfies the configured minimum, but stays below the minimum
				// predicted heat-pump consumption reported via IR10302
				.next(new TestCase("Export below reported minimum consumption: no elevated mode") //
						.input("_sum", Sum.ChannelId.GRID_ACTIVE_POWER, -4000) //
						.input("_sum", Sum.ChannelId.ESS_DISCHARGE_POWER, 0) //
						.input("heatPump0", ElectricityMeter.ChannelId.ACTIVE_POWER, 0) //
						.input("heatPump0", HeatShiHeatPump.ChannelId.MIN_PREDICTED_ACTIVE_POWER, 4500) //
						.output("heatPump0", HeatShiHeatPump.ChannelId.HEATING_MODE, 0) //
						.output(ControllerShiHeatPump.ChannelId.ELEVATED_MODE_ACTIVE, false)) //
				.next(new TestCase("Export reaches reported minimum consumption: elevated mode") //
						.timeleap(clock, 6, ChronoUnit.MINUTES) //
						.input("_sum", Sum.ChannelId.GRID_ACTIVE_POWER, -5000) //
						.input("_sum", Sum.ChannelId.ESS_DISCHARGE_POWER, 0) //
						.input("heatPump0", ElectricityMeter.ChannelId.ACTIVE_POWER, 0) //
						.input("heatPump0", HeatShiHeatPump.ChannelId.MIN_PREDICTED_ACTIVE_POWER, 4500) //
						.output("heatPump0", HeatShiHeatPump.ChannelId.HEATING_MODE, 1) //
						.output("heatPump0", HeatShiHeatPump.ChannelId.PC_LIMIT, 5000) //
						.output(ControllerShiHeatPump.ChannelId.ELEVATED_MODE_ACTIVE, true)) //
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
				// No spare energy (no prediction): battery may only serve the household
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
				// Grid export 2000 W plus running heat pump 1000 W = 3000 W surplus:
				// the heat pump does not eat its own surplus signal
				.next(new TestCase("Elevated mode: heat-pump power added back to surplus") //
						.input("_sum", Sum.ChannelId.GRID_ACTIVE_POWER, -2000) //
						.input("_sum", Sum.ChannelId.ESS_DISCHARGE_POWER, 0) //
						.input("_sum", Sum.ChannelId.ESS_ACTIVE_POWER, 0) //
						.input("heatPump0", ElectricityMeter.ChannelId.ACTIVE_POWER, 1000) //
						.output("heatPump0", HeatShiHeatPump.ChannelId.HEATING_MODE, 1) //
						.output("heatPump0", HeatShiHeatPump.ChannelId.PC_LIMIT, 3000) //
						.output(ControllerShiHeatPump.ChannelId.ELEVATED_MODE_ACTIVE, true)) //
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

	@Test
	void testBehindMeterPassiveSupportRaisesDischargeLimit() throws Exception {
		var clock = createDummyClock();
		var componentManager = new DummyComponentManager(clock);
		var sum = new DummySum();
		var now = Instant.now(clock);

		// Night reserve 3000 Wh; usable 5000 Wh -> spare power 2000 W
		var productionValues = new Integer[24];
		var consumptionValues = new Integer[24];
		Arrays.fill(productionValues, 0);
		Arrays.fill(consumptionValues, 500);
		var predictorManager = new DummyPredictorManager(//
				new DummyPredictor("predictor0", componentManager,
						Prediction.from(sum, SUM_PRODUCTION_ACTIVE_POWER, now, productionValues),
						SUM_PRODUCTION_ACTIVE_POWER),
				new DummyPredictor("predictor1", componentManager,
						Prediction.from(sum, SUM_CONSUMPTION_ACTIVE_POWER, now, consumptionValues),
						SUM_CONSUMPTION_ACTIVE_POWER));

		new ControllerTest(new ControllerShiHeatPumpImpl()) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("componentManager", componentManager) //
				.addReference("sum", sum) //
				.addReference("predictorManager", predictorManager) //
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
						.setEssSupportDurationMinutes(60) //
						.build()) //
				// Battery covers household (500 W) plus the heat pump run (2000 W),
				// because spare power (2000 W) allows it
				.next(new TestCase("Discharge limit raised by allowed support") //
						.input("_sum", Sum.ChannelId.GRID_ACTIVE_POWER, 0) //
						.input("_sum", Sum.ChannelId.ESS_DISCHARGE_POWER, 2500) //
						.input("_sum", Sum.ChannelId.ESS_ACTIVE_POWER, 2500) //
						.input("_sum", Sum.ChannelId.ESS_SOC, 65) //
						.input("_sum", Sum.ChannelId.ESS_CAPACITY, 10_000) //
						.input("heatPump0", ElectricityMeter.ChannelId.ACTIVE_POWER, 2000) //
						.output("heatPump0", HeatShiHeatPump.ChannelId.HEATING_MODE, 0) //
						.output("ess0", ManagedSymmetricEss.ChannelId.SET_ACTIVE_POWER_LESS_OR_EQUALS, 2500) //
						.output(ControllerShiHeatPump.ChannelId.ELEVATED_MODE_ACTIVE, false) //
						.output(ControllerShiHeatPump.ChannelId.ESS_SUPPORT_POWER, 2000) //
						.output(ControllerShiHeatPump.ChannelId.ESS_DISCHARGE_LIMIT, 2500) //
						.output(ControllerShiHeatPump.ChannelId.NIGHT_RESERVE_ENERGY, 3000)) //
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
				.next(new TestCase("No export, no prediction: heat pump stays on grid tariff") //
						.input("_sum", Sum.ChannelId.GRID_ACTIVE_POWER, 1000) //
						.input("_sum", Sum.ChannelId.ESS_DISCHARGE_POWER, 0) //
						.input("_sum", Sum.ChannelId.ESS_SOC, 50) //
						.input("_sum", Sum.ChannelId.ESS_CAPACITY, 10_000) //
						.input("heatPump0", ElectricityMeter.ChannelId.ACTIVE_POWER, 2000) //
						.output("heatPump0", HeatShiHeatPump.ChannelId.HEATING_MODE, 0) //
						.output("heatPump0", HeatShiHeatPump.ChannelId.HOT_WATER_MODE, 0) //
						.output("heatPump0", HeatShiHeatPump.ChannelId.LPC_MODE, 0) //
						.output("ess0", ManagedSymmetricEss.ChannelId.SET_ACTIVE_POWER_GREATER_OR_EQUALS, null) //
						.output(ControllerShiHeatPump.ChannelId.ELEVATED_MODE_ACTIVE, false) //
						.output(ControllerShiHeatPump.ChannelId.ESS_SUPPORT_POWER, 0) //
						.output(ControllerShiHeatPump.ChannelId.NO_PREDICTION_AVAILABLE, true)) //
				.deactivate();
	}

	@Test
	void testPassiveEssSupportCoversHeatPumpRuns() throws Exception {
		var clock = createDummyClock();
		var componentManager = new DummyComponentManager(clock);
		var sum = new DummySum();
		var now = Instant.now(clock);

		// Flat forecast: no production, 500 W household consumption for the next 6
		// hours -> night reserve of 3000 Wh
		var productionValues = new Integer[24];
		var consumptionValues = new Integer[24];
		Arrays.fill(productionValues, 0);
		Arrays.fill(consumptionValues, 500);
		var predictorManager = new DummyPredictorManager(//
				new DummyPredictor("predictor0", componentManager,
						Prediction.from(sum, SUM_PRODUCTION_ACTIVE_POWER, now, productionValues),
						SUM_PRODUCTION_ACTIVE_POWER),
				new DummyPredictor("predictor1", componentManager,
						Prediction.from(sum, SUM_CONSUMPTION_ACTIVE_POWER, now, consumptionValues),
						SUM_CONSUMPTION_ACTIVE_POWER));

		new ControllerTest(new ControllerShiHeatPumpImpl()) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("componentManager", componentManager) //
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
						.setMinimumSurplusPowerForElevatedMode(2000) //
						.setMinSoc(15) //
						.setNightReserveBuffer(100) //
						.setEssSupportDurationMinutes(60) //
						.build()) //
				// Usable: 10 kWh * (65-15)% = 5000 Wh; reserve 3000 Wh -> spare 2000 Wh
				// -> spare power 2000 W. No PV surplus -> NO elevated mode, but the
				// heat pump's own run (500 W, e.g. hot water in the evening) is
				// passively covered by forced battery export.
				.next(new TestCase("No surplus: heat-pump run passively covered by battery") //
						.input("_sum", Sum.ChannelId.GRID_ACTIVE_POWER, 0) //
						.input("_sum", Sum.ChannelId.ESS_DISCHARGE_POWER, 0) //
						.input("_sum", Sum.ChannelId.ESS_ACTIVE_POWER, 0) //
						.input("_sum", Sum.ChannelId.ESS_SOC, 65) //
						.input("_sum", Sum.ChannelId.ESS_CAPACITY, 10_000) //
						.input("heatPump0", ElectricityMeter.ChannelId.ACTIVE_POWER, 500) //
						.output("heatPump0", HeatShiHeatPump.ChannelId.HEATING_MODE, 0) //
						.output("heatPump0", HeatShiHeatPump.ChannelId.PC_LIMIT, 0) //
						.output("ess0", ManagedSymmetricEss.ChannelId.SET_ACTIVE_POWER_GREATER_OR_EQUALS, 500) //
						.output(ControllerShiHeatPump.ChannelId.ELEVATED_MODE_ACTIVE, false) //
						.output(ControllerShiHeatPump.ChannelId.ESS_SUPPORT_POWER, 2000) //
						.output(ControllerShiHeatPump.ChannelId.ESS_FORCED_EXPORT_POWER, 500) //
						.output(ControllerShiHeatPump.ChannelId.NIGHT_RESERVE_ENERGY, 3000) //
						.output(ControllerShiHeatPump.ChannelId.NO_PREDICTION_AVAILABLE, false)) //
				// Heat pump ramped up: forced export follows up to the spare power
				.next(new TestCase("Heat pump ramped up: forced export at spare-power limit") //
						.input("_sum", Sum.ChannelId.GRID_ACTIVE_POWER, -500) //
						.input("_sum", Sum.ChannelId.ESS_DISCHARGE_POWER, 500) //
						.input("_sum", Sum.ChannelId.ESS_ACTIVE_POWER, 500) //
						.input("_sum", Sum.ChannelId.ESS_SOC, 65) //
						.input("_sum", Sum.ChannelId.ESS_CAPACITY, 10_000) //
						.input("heatPump0", ElectricityMeter.ChannelId.ACTIVE_POWER, 2500) //
						.output("ess0", ManagedSymmetricEss.ChannelId.SET_ACTIVE_POWER_GREATER_OR_EQUALS, 2000) //
						.output(ControllerShiHeatPump.ChannelId.ESS_FORCED_EXPORT_POWER, 2000)) //
				.deactivate();
	}

	@Test
	void testElevatedModeWithEssBridge() throws Exception {
		var clock = createDummyClock();
		var componentManager = new DummyComponentManager(clock);
		var sum = new DummySum();
		var now = Instant.now(clock);

		// Same forecast as above: night reserve 3000 Wh -> spare power 2000 W
		var productionValues = new Integer[24];
		var consumptionValues = new Integer[24];
		Arrays.fill(productionValues, 0);
		Arrays.fill(consumptionValues, 500);
		var predictorManager = new DummyPredictorManager(//
				new DummyPredictor("predictor0", componentManager,
						Prediction.from(sum, SUM_PRODUCTION_ACTIVE_POWER, now, productionValues),
						SUM_PRODUCTION_ACTIVE_POWER),
				new DummyPredictor("predictor1", componentManager,
						Prediction.from(sum, SUM_CONSUMPTION_ACTIVE_POWER, now, consumptionValues),
						SUM_CONSUMPTION_ACTIVE_POWER));

		new ControllerTest(new ControllerShiHeatPumpImpl()) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("componentManager", componentManager) //
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
						.setMinimumSurplusPowerForElevatedMode(2000) //
						.setMinSoc(15) //
						.setNightReserveBuffer(100) //
						.setEssSupportDurationMinutes(60) //
						.build()) //
				// PV export 1500 W is below the minimum of 2000 W, but battery support
				// bridges the missing 500 W -> elevated mode with PC limit 2000 W
				.next(new TestCase("Surplus plus battery bridge reaches minimum: elevated mode") //
						.input("_sum", Sum.ChannelId.GRID_ACTIVE_POWER, -1500) //
						.input("_sum", Sum.ChannelId.ESS_DISCHARGE_POWER, 0) //
						.input("_sum", Sum.ChannelId.ESS_ACTIVE_POWER, 0) //
						.input("_sum", Sum.ChannelId.ESS_SOC, 65) //
						.input("_sum", Sum.ChannelId.ESS_CAPACITY, 10_000) //
						.input("heatPump0", ElectricityMeter.ChannelId.ACTIVE_POWER, 500) //
						.output("heatPump0", HeatShiHeatPump.ChannelId.HEATING_MODE, 1) //
						.output("heatPump0", HeatShiHeatPump.ChannelId.PC_LIMIT, 2000) //
						.output("ess0", ManagedSymmetricEss.ChannelId.SET_ACTIVE_POWER_GREATER_OR_EQUALS, null) //
						.output(ControllerShiHeatPump.ChannelId.ELEVATED_MODE_ACTIVE, true) //
						.output(ControllerShiHeatPump.ChannelId.ESS_FORCED_EXPORT_POWER, 0)) //
				.deactivate();
	}

	@Test
	void testDisabledEssSupportExcludedFromElevationDecision() throws Exception {
		var clock = createDummyClock();
		var componentManager = new DummyComponentManager(clock);
		var sum = new DummySum();
		var now = Instant.now(clock);

		// Spare energy would allow 2000 W battery bridge...
		var productionValues = new Integer[24];
		var consumptionValues = new Integer[24];
		Arrays.fill(productionValues, 0);
		Arrays.fill(consumptionValues, 500);
		var predictorManager = new DummyPredictorManager(//
				new DummyPredictor("predictor0", componentManager,
						Prediction.from(sum, SUM_PRODUCTION_ACTIVE_POWER, now, productionValues),
						SUM_PRODUCTION_ACTIVE_POWER),
				new DummyPredictor("predictor1", componentManager,
						Prediction.from(sum, SUM_CONSUMPTION_ACTIVE_POWER, now, consumptionValues),
						SUM_CONSUMPTION_ACTIVE_POWER));

		new ControllerTest(new ControllerShiHeatPumpImpl()) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("componentManager", componentManager) //
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
						.setMinimumSurplusPowerForElevatedMode(2000) //
						.setEssSupportEnabled(false) //
						.setMinSoc(15) //
						.setNightReserveBuffer(100) //
						.setEssSupportDurationMinutes(60) //
						.build()) //
				// ...but with disabled ESS support the battery bridge must not count
				// towards the elevated-mode decision: surplus 1500 < 2000 -> normal
				.next(new TestCase("Disabled support: no elevation on battery promise") //
						.input("_sum", Sum.ChannelId.GRID_ACTIVE_POWER, -1500) //
						.input("_sum", Sum.ChannelId.ESS_DISCHARGE_POWER, 0) //
						.input("_sum", Sum.ChannelId.ESS_SOC, 65) //
						.input("_sum", Sum.ChannelId.ESS_CAPACITY, 10_000) //
						.input("heatPump0", ElectricityMeter.ChannelId.ACTIVE_POWER, 500) //
						.output("heatPump0", HeatShiHeatPump.ChannelId.HEATING_MODE, 0) //
						.output("ess0", ManagedSymmetricEss.ChannelId.SET_ACTIVE_POWER_GREATER_OR_EQUALS, null) //
						.output(ControllerShiHeatPump.ChannelId.ELEVATED_MODE_ACTIVE, false) //
						.output(ControllerShiHeatPump.ChannelId.ESS_SUPPORT_POWER, 0)) //
				.deactivate();
	}

	@Test
	void testInsufficientReserveKeepsNormalMode() throws Exception {
		var clock = createDummyClock();
		var componentManager = new DummyComponentManager(clock);
		var sum = new DummySum();
		var now = Instant.now(clock);

		// Flat forecast: no production, 1500 W household consumption for the next 6
		// hours -> night reserve of 9000 Wh exceeds the usable battery energy
		var productionValues = new Integer[24];
		var consumptionValues = new Integer[24];
		Arrays.fill(productionValues, 0);
		Arrays.fill(consumptionValues, 1500);
		var predictorManager = new DummyPredictorManager(//
				new DummyPredictor("predictor0", componentManager,
						Prediction.from(sum, SUM_PRODUCTION_ACTIVE_POWER, now, productionValues),
						SUM_PRODUCTION_ACTIVE_POWER),
				new DummyPredictor("predictor1", componentManager,
						Prediction.from(sum, SUM_CONSUMPTION_ACTIVE_POWER, now, consumptionValues),
						SUM_CONSUMPTION_ACTIVE_POWER));

		new ControllerTest(new ControllerShiHeatPumpImpl()) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("componentManager", componentManager) //
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
						.setMinimumSurplusPowerForElevatedMode(2000) //
						.setMinSoc(15) //
						.setNightReserveBuffer(100) //
						.setEssSupportDurationMinutes(60) //
						.build()) //
				.next(new TestCase("Night reserve exceeds usable energy: no battery support") //
						.input("_sum", Sum.ChannelId.GRID_ACTIVE_POWER, 0) //
						.input("_sum", Sum.ChannelId.ESS_DISCHARGE_POWER, 0) //
						.input("_sum", Sum.ChannelId.ESS_SOC, 65) //
						.input("_sum", Sum.ChannelId.ESS_CAPACITY, 10_000) //
						.input("heatPump0", ElectricityMeter.ChannelId.ACTIVE_POWER, 500) //
						.output("heatPump0", HeatShiHeatPump.ChannelId.HEATING_MODE, 0) //
						.output("ess0", ManagedSymmetricEss.ChannelId.SET_ACTIVE_POWER_GREATER_OR_EQUALS, null) //
						.output(ControllerShiHeatPump.ChannelId.ELEVATED_MODE_ACTIVE, false) //
						.output(ControllerShiHeatPump.ChannelId.ESS_SUPPORT_POWER, 0) //
						.output(ControllerShiHeatPump.ChannelId.ESS_FORCED_EXPORT_POWER, 0) //
						.output(ControllerShiHeatPump.ChannelId.NIGHT_RESERVE_ENERGY, 9000)) //
				.deactivate();
	}
}
