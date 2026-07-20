package io.openems.edge.controller.heatpump.shi;

import static io.openems.common.test.TestUtils.createDummyClock;

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
						.build()) //
				// Strong export and free battery energy (SoC 65 %, sunny forecast) ->
				// the commit can be carried by the battery -> elevated mode
				.next(new TestCase("Grid export above minimum with free energy: elevated") //
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
	void testBoostBlockedWithoutFreeEnergy() throws Exception {
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
				// Strong export but no prediction -> free energy 0 -> the commit cannot
				// be backed by the battery -> no boost (the live grid-feeding case)
				.next(new TestCase("Strong export but no free battery energy: no boost") //
						.input("_sum", Sum.ChannelId.GRID_ACTIVE_POWER, -4000) //
						.input("_sum", Sum.ChannelId.ESS_DISCHARGE_POWER, 0) //
						.input("heatPump0", ElectricityMeter.ChannelId.ACTIVE_POWER, 0) //
						.output(ControllerShiHeatPump.ChannelId.ELEVATED_MODE_ACTIVE, false)) //
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
						.setBoostConfirmationSeconds(240) //
						.build()) //
				.next(new TestCase("Conditions fulfilled: pending, not yet elevated") //
						.input("_sum", Sum.ChannelId.GRID_ACTIVE_POWER, -4000) //
						.input("_sum", Sum.ChannelId.ESS_DISCHARGE_POWER, 0) //
						.input("_sum", Sum.ChannelId.ESS_SOC, 65) //
						.input("_sum", Sum.ChannelId.ESS_CAPACITY, 10_000) //
						.input("heatPump0", ElectricityMeter.ChannelId.ACTIVE_POWER, 500) //
						.output(ControllerShiHeatPump.ChannelId.ELEVATED_MODE_ACTIVE, false) //
						.output(ControllerShiHeatPump.ChannelId.BOOST_PENDING, true)) //
				.next(new TestCase("Surplus dip resets the confirmation") //
						.input("_sum", Sum.ChannelId.GRID_ACTIVE_POWER, 0) //
						.output(ControllerShiHeatPump.ChannelId.BOOST_PENDING, false)) //
				.next(new TestCase("Surplus back: confirmation restarts") //
						.input("_sum", Sum.ChannelId.GRID_ACTIVE_POWER, -4000) //
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
		var cm = new DummyComponentManager(clock);
		var sum = new DummySum();
		// Production is 0 for the current quarter (cloud now) but high afterwards, so
		// the night reserve stays small (free energy exists and the commit-energy
		// check passes) while the veto - which requires the sun alone to sustain the
		// commit - fires on the current quarter.
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
	void testNoSupportWhenReserveClaimsEnergy() throws Exception {
		var clock = createDummyClock();
		var cm = new DummyComponentManager(clock);
		var sum = new DummySum();
		// Flat forecast: no production, 1500 W household -> big night reserve that
		// claims all usable energy at 40 % SoC -> no free energy -> no support.
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
				.next(new TestCase("Reserve claims all usable energy: no support, no boost") //
						.input("_sum", Sum.ChannelId.GRID_ACTIVE_POWER, -4000) //
						.input("_sum", Sum.ChannelId.ESS_DISCHARGE_POWER, 0) //
						.input("_sum", Sum.ChannelId.ESS_SOC, 40) //
						.input("_sum", Sum.ChannelId.ESS_CAPACITY, 10_000) //
						.input("heatPump0", ElectricityMeter.ChannelId.ACTIVE_POWER, 2000) //
						.output(ControllerShiHeatPump.ChannelId.ELEVATED_MODE_ACTIVE, false) //
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
						.output(ControllerShiHeatPump.ChannelId.NO_PREDICTION_AVAILABLE, true)) //
				.deactivate();
	}

	@Test
	void testDisabledEssSupportBlocksBoostAndSupport() throws Exception {
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
				// Support disabled -> no free energy is credited -> no boost, no forced
				// export, even with a sunny forecast.
				.next(new TestCase("Disabled support: no boost, no forced export") //
						.input("_sum", Sum.ChannelId.GRID_ACTIVE_POWER, -4000) //
						.input("_sum", Sum.ChannelId.ESS_DISCHARGE_POWER, 0) //
						.input("_sum", Sum.ChannelId.ESS_SOC, 65) //
						.input("_sum", Sum.ChannelId.ESS_CAPACITY, 10_000) //
						.input("heatPump0", ElectricityMeter.ChannelId.ACTIVE_POWER, 2000) //
						.output(ControllerShiHeatPump.ChannelId.ELEVATED_MODE_ACTIVE, false) //
						.output(ControllerShiHeatPump.ChannelId.ESS_FORCED_EXPORT_POWER, 0)) //
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
