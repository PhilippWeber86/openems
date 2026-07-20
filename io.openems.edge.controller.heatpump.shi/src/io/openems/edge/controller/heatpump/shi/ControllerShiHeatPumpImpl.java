package io.openems.edge.controller.heatpump.shi;

import static io.openems.edge.common.type.Phase.SingleOrAllPhase.ALL;
import static io.openems.edge.ess.power.api.Pwr.ACTIVE;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoField;

import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.component.annotations.ReferencePolicyOption;
import org.osgi.service.metatype.annotations.Designate;

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.common.types.ChannelAddress;
import io.openems.common.types.MeterType;
import io.openems.edge.common.component.AbstractOpenemsComponent;
import io.openems.edge.common.component.ComponentManager;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.sum.Sum;
import io.openems.edge.controller.api.Controller;
import io.openems.edge.ess.api.ManagedSymmetricEss;
import io.openems.edge.heat.shi.HeatShiHeatPump;
import io.openems.edge.meter.api.ElectricityMeter;
import io.openems.edge.predictor.api.manager.PredictorManager;
import io.openems.edge.predictor.api.prediction.Prediction;

@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "Controller.HeatPump.Shi", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE)
public class ControllerShiHeatPumpImpl extends AbstractOpenemsComponent
		implements ControllerShiHeatPump, Controller, OpenemsComponent {

	private static final ChannelAddress SUM_PRODUCTION_ACTIVE_POWER = new ChannelAddress("_sum",
			Sum.ChannelId.PRODUCTION_ACTIVE_POWER.id());
	private static final ChannelAddress SUM_CONSUMPTION_ACTIVE_POWER = new ChannelAddress("_sum",
			Sum.ChannelId.CONSUMPTION_ACTIVE_POWER.id());
	private static final ChannelAddress SUM_UNMANAGED_PRODUCTION_ACTIVE_POWER = new ChannelAddress("_sum",
			Sum.ChannelId.UNMANAGED_PRODUCTION_ACTIVE_POWER.id());
	private static final ChannelAddress SUM_UNMANAGED_CONSUMPTION_ACTIVE_POWER = new ChannelAddress("_sum",
			Sum.ChannelId.UNMANAGED_CONSUMPTION_ACTIVE_POWER.id());

	/** Upper bound of SHI register HR10041 (300 x 0.1 kW). */
	private static final int MAX_PC_LIMIT = 30_000; // [W]

	@Reference
	private ConfigurationAdmin cm;

	@Reference
	private ComponentManager componentManager;

	@Reference
	private Sum sum;

	@Reference(policy = ReferencePolicy.DYNAMIC, policyOption = ReferencePolicyOption.GREEDY, cardinality = ReferenceCardinality.OPTIONAL)
	private volatile PredictorManager predictorManager;

	@Reference(policy = ReferencePolicy.DYNAMIC, policyOption = ReferencePolicyOption.GREEDY, cardinality = ReferenceCardinality.MANDATORY)
	private volatile HeatShiHeatPump heatPump;

	private Config config;
	// Config temperatures in °C, converted once to the SHI-native 0.1 °C/K
	private int heatingSetpointDeciDegree;
	private int hotWaterSetpointDeciDegree;
	private int extensionMinDeltaDeciKelvin;
	private Instant lastModeChange = Instant.MIN;
	private boolean elevatedModeActive = false;
	private Instant entryConditionsSince = null;
	private boolean runExtensionActive = false;
	private Integer naturalHotWaterSetpoint = null;

	public ControllerShiHeatPumpImpl() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				Controller.ChannelId.values(), //
				ControllerShiHeatPump.ChannelId.values() //
		);
	}

	@Activate
	private void activate(ComponentContext context, Config config) throws OpenemsNamedException {
		super.activate(context, config.id(), config.alias(), config.enabled());
		this.updateConfig(config);
	}

	@Modified
	private void modified(ComponentContext context, Config config) throws OpenemsNamedException {
		super.modified(context, config.id(), config.alias(), config.enabled());
		this.updateConfig(config);
	}

	private void updateConfig(Config config) {
		this.config = config;
		this.heatingSetpointDeciDegree = (int) Math.round(config.heatingSetpoint() * 10);
		this.hotWaterSetpointDeciDegree = (int) Math.round(config.hotWaterSetpoint() * 10);
		this.extensionMinDeltaDeciKelvin = (int) Math.round(config.extensionMinTemperatureDelta() * 10);
		OpenemsComponent.updateReferenceFilter(this.cm, this.servicePid(), "heatPump", config.heatPump_id());
	}

	@Override
	@Deactivate
	protected void deactivate() {
		super.deactivate();
	}

	@Override
	public void run() throws OpenemsNamedException {
		this.checkHeatPumpMeterType();
		// Surface silently ignored writes: with the device in read-only mode all
		// setpoint commands of this Controller have no effect
		this._setControlNotAllowed(this.heatPump.getReadOnlyMode().orElse(false));

		var gridActivePower = this.sum.getGridActivePower().orElse(0);
		var essDischargePower = Math.max(0, this.sum.getEssDischargePower().orElse(0));
		final var heatPumpPower = Math.max(0, this.heatPump.getActivePower().orElse(0));
		// PV surplus available for the heat pump, after household and battery
		// charging took their share and without counting battery discharge:
		// - BEHIND_GRID_METER: heat pump consumption is part of the grid
		// measurement, so it must be added back to avoid eating its own surplus.
		// - GRID_SIDE_OF_GRID_METER: heat pump consumption is invisible at the
		// grid meter and served by whatever this segment exports.
		var surplusPower = switch (this.config.heatPumpPosition()) {
		case BEHIND_GRID_METER -> Math.max(0, -gridActivePower - essDischargePower + heatPumpPower);
		case GRID_SIDE_OF_GRID_METER -> Math.max(0, -gridActivePower - essDischargePower);
		};

		// The heat pump reports its minimum predicted power consumption (IR10302);
		// starting elevated mode below it would only shift grid consumption
		var minimumPower = Math.max(this.config.minimumSurplusPowerForElevatedMode(),
				this.heatPump.getMinPredictedActivePower().orElse(0));

		// Energy release gate: as long as free battery energy above the night
		// reserve exists, the battery may support the heat pump. The support POWER
		// is NOT derived from this energy via an assumed duration - it is the real,
		// currently needed and deliverable power (uncovered heat-pump power, clamped
		// by the ESS and an optional cap). maxSupportPower is that upper bound; it is
		// 0 when no energy is free (or support is disabled), MAX_PC_LIMIT for "as
		// much as the ESS can deliver", or the configured cap.
		var spareEssEnergy = this.calculateSpareEssEnergy();
		var energyAvailable = this.config.essSupportEnabled() && spareEssEnergy > 0;
		final var maxSupportPower = !energyAvailable ? 0
				: this.config.maxBatterySupportPower() > 0 ? this.config.maxBatterySupportPower() : MAX_PC_LIMIT;

		this.updateNaturalHotWaterSetpoint();

		// Elevated mode requires REAL PV surplus as its base (no battery bridging of
		// a weak surplus). Committing a boost cycle additionally requires that the
		// free battery energy can carry the heat pump through the compressor minimum
		// runtime, so a cloud during the committed cycle can be ridden through from
		// the battery instead of the grid. This uses the heat pump's real minimum
		// runtime, not an assumed support duration. Once elevated, the boost is held
		// on the surplus alone, so strong sun does not abort it when the battery
		// drains.
		var sunSufficient = surplusPower >= minimumPower;
		var canSustainCommit = energyAvailable && spareEssEnergy >= this.requiredCommitEnergy(minimumPower);
		var startConditions = sunSufficient && canSustainCommit;
		var boostConfirmed = this.updateBoostConfirmation(startConditions);
		var forecastVetoed = !this.elevatedModeActive && startConditions
				&& this.isForecastVetoed(minimumPower);
		this._setBoostForecastVeto(forecastVetoed);

		var shouldElevate = this.elevatedModeActive //
				? sunSufficient //
				: startConditions && boostConfirmed && !forecastVetoed;
		if (this.isHysteresisActive() && shouldElevate != this.elevatedModeActive) {
			shouldElevate = this.elevatedModeActive;
		}
		if (shouldElevate != this.elevatedModeActive) {
			this.elevatedModeActive = shouldElevate;
			this.lastModeChange = Instant.now(this.componentManager.getClock());
		}

		if (this.elevatedModeActive) {
			// A running extension is absorbed by the elevated mode
			this.runExtensionActive = false;
			// Never push the soft power limit below the minimum power while elevated:
			// during the switching hysteresis a short surplus dip would otherwise
			// shut down the compressor via a 0 W limit - the SHI documentation
			// explicitly recommends a switch-off delay for PV-surplus operation
			this.applyElevatedMode(Math.max(minimumPower, surplusPower + maxSupportPower));
		} else {
			this.handleRunExtension(surplusPower, maxSupportPower, heatPumpPower);
			this.applyNormalMode();
		}
		var appliedSupport = this.applyEssSupport(maxSupportPower, surplusPower, heatPumpPower);

		this._setElevatedModeActive(this.elevatedModeActive);
		this._setFreeBatteryEnergy(spareEssEnergy);
		this._setEssSupportPower(appliedSupport);
		this._setRunExtensionActive(this.runExtensionActive);
	}

	/**
	 * Energy the free battery reserve must hold to commit a boost cycle: the
	 * boost power carried through the compressor minimum runtime, so a cloud
	 * during the committed cycle is ridden through from the battery. The runtime
	 * is the real value reported by the heat pump (IR10204), with the configured
	 * minimum switching time as lower bound - not an assumed support duration.
	 *
	 * @param minimumPower effective minimum power for elevated mode in W
	 * @return required free battery energy in Wh
	 */
	private int requiredCommitEnergy(int minimumPower) {
		var runtimeSeconds = Math.max(this.config.minimumSwitchingTime(), this.heatPump.getMinRuntime().orElse(0) * 60);
		return Math.round(minimumPower * runtimeSeconds / 3600F);
	}

	/**
	 * Plausibility check: the Meter-Type of the heat pump device and the
	 * configured heat pump position describe the same wiring and must match;
	 * raises a warning otherwise.
	 */
	private void checkHeatPumpMeterType() {
		var expectedMeterType = switch (this.config.heatPumpPosition()) {
		case BEHIND_GRID_METER -> MeterType.CONSUMPTION_METERED;
		case GRID_SIDE_OF_GRID_METER -> MeterType.CONSUMPTION_NOT_METERED;
		};
		this._setMeterTypeMismatch(this.heatPump.getMeterType() != expectedMeterType);
	}

	/**
	 * The hysteresis is aligned with the compressor cycle limits reported by the
	 * heat pump: while elevated, the mode is committed for at least the minimum
	 * runtime (IR10204) - what we start, we finish; after leaving elevated mode,
	 * re-entry is blocked for at least the restart lock (IR10203) - the
	 * compressor could not start earlier anyway. The configured minimum
	 * switching time acts as lower bound and as fallback if the registers are
	 * not available.
	 *
	 * @return true while mode changes are blocked
	 */
	private boolean isHysteresisActive() {
		var heatPumpMinutes = this.elevatedModeActive //
				? this.heatPump.getMinRuntime().orElse(0) //
				: this.heatPump.getMinStandstillTime().orElse(0);
		var seconds = Math.max(this.config.minimumSwitchingTime(), heatPumpMinutes * 60L);
		return this.lastModeChange.plusSeconds(seconds)
				.isAfter(Instant.now(this.componentManager.getClock()));
	}

	private void applyElevatedMode(int availablePower) throws OpenemsNamedException {
		// The SHI rejects heating/hot-water commands while the corresponding
		// operating mode is disabled at the heat pump (status 0 = "Off", e.g.
		// heating in summer)
		if (this.heatPump.getHeatingStatus().orElse(0) > 0) {
			this.heatPump.setHeatingMode(HeatShiHeatPump.MODE_SETPOINT);
			this.heatPump.setHeatingSetpoint(this.heatingSetpointDeciDegree);
		}
		if (this.heatPump.getHotWaterStatus().orElse(0) > 0) {
			this.heatPump.setHotWaterMode(HeatShiHeatPump.MODE_SETPOINT);
			this.heatPump.setHotWaterSetpoint(this.hotWaterSetpointDeciDegree);
		}
		this.heatPump.setLpcMode(HeatShiHeatPump.LPC_MODE_SOFT);
		this.heatPump.setPcLimit(Math.max(0, Math.min(MAX_PC_LIMIT, availablePower)));
	}

	private void applyNormalMode() throws OpenemsNamedException {
		if (this.heatPump.getHeatingStatus().orElse(0) > 0) {
			this.heatPump.setHeatingMode(HeatShiHeatPump.MODE_NONE);
		}
		// The hot-water and LPC registers belong to the run extension while it is
		// active
		if (!this.runExtensionActive) {
			if (this.heatPump.getHotWaterStatus().orElse(0) > 0) {
				this.heatPump.setHotWaterMode(HeatShiHeatPump.MODE_NONE);
			}
			this.heatPump.setLpcMode(HeatShiHeatPump.LPC_MODE_NONE);
			this.heatPump.setPcLimit(0);
		}
	}

	/**
	 * Latches the heat pump's own hot-water setpoint. The readback of IR10121
	 * only reflects the natural setpoint while no external influence is active,
	 * so it is sampled exclusively while the hot-water mode readback (HR10005)
	 * shows "no influence". This also covers controller restarts during an
	 * active influence and foreign Modbus masters.
	 */
	private void updateNaturalHotWaterSetpoint() {
		if (this.heatPump.getHotWaterModeChannel().value().orElse(-1) == HeatShiHeatPump.MODE_NONE) {
			var setpoint = this.heatPump.getHotWaterActiveSetpoint().get();
			if (setpoint != null) {
				this.naturalHotWaterSetpoint = setpoint;
			}
		}
		this._setNaturalHotWaterSetpoint(this.naturalHotWaterSetpoint);
	}

	/**
	 * Requires the elevated-mode entry conditions to be fulfilled continuously
	 * for the configured confirmation time before entry, so short surplus spikes
	 * do not trigger a committed compressor cycle.
	 *
	 * @param entryConditions whether the entry conditions are currently fulfilled
	 * @return true once the conditions lasted for the confirmation time
	 */
	private boolean updateBoostConfirmation(boolean entryConditions) {
		if (this.elevatedModeActive || !entryConditions) {
			this.entryConditionsSince = null;
			this._setBoostPending(false);
			return false;
		}
		var now = Instant.now(this.componentManager.getClock());
		if (this.entryConditionsSince == null) {
			this.entryConditionsSince = now;
		}
		var confirmed = !this.entryConditionsSince.plusSeconds(this.config.boostConfirmationSeconds()).isAfter(now);
		this._setBoostPending(!confirmed);
		return confirmed;
	}

	/**
	 * Optional forecast veto for elevated-mode entry: the prediction must show
	 * enough surplus (plus allowed battery support) for the duration of a
	 * compressor cycle. Lenient by design - without a prediction or with missing
	 * values there is no veto, and it never overrules an active elevated mode.
	 *
	 * @param minimumPower effective minimum power for elevated mode in W
	 * @return true if entry should be vetoed
	 */
	private boolean isForecastVetoed(int minimumPower) {
		if (!this.config.forecastVetoEnabled() || this.predictorManager == null) {
			return false;
		}
		var productionPrediction = this.getProductionPrediction();
		var consumptionPrediction = this.getConsumptionPrediction();
		if (productionPrediction.isEmpty() || consumptionPrediction.isEmpty()) {
			return false;
		}
		var productions = productionPrediction.asArray();
		var consumptions = consumptionPrediction.asArray();

		// The veto requires the PV surplus ALONE to sustain the commit - the battery
		// is not credited here, because carrying the commit from the battery is
		// already guaranteed by the commit-energy check. Enabling the veto therefore
		// means "only boost if the sun itself will sustain it".
		var commitMinutes = Math.max(this.heatPump.getMinRuntime().orElse(0),
				this.config.minimumSwitchingTime() / 60);
		// The commit starts mid-quarter: it covers the remainder of the current
		// quarter plus the overhang into subsequent quarters (e.g. a 20-minute
		// commit at 12:14 reaches until 12:34 and touches three quarters)
		var now = ZonedDateTime.now(this.componentManager.getClock());
		var remainingSecondsCurrentQuarter = 15 * 60
				- (now.get(ChronoField.MINUTE_OF_HOUR) % 15 * 60 + now.getSecond());
		var overhangSeconds = Math.max(0, commitMinutes * 60 - remainingSecondsCurrentQuarter);
		var quarters = 1 + (overhangSeconds + 15 * 60 - 1) / (15 * 60);
		for (var i = 0; i < Math.min(quarters, Math.min(productions.length, consumptions.length)); i++) {
			var production = productions[i];
			var consumption = consumptions[i];
			if (production == null || consumption == null) {
				continue;
			}
			if (production - consumption < minimumPower) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Opportunistically extends a natural hot-water run of the heat pump to the
	 * elevated hot-water setpoint - the compressor is already running, so the
	 * storage is topped up without an additional compressor start. Requirements,
	 * re-evaluated every cycle: a natural hot-water run is active, PV surplus
	 * plus allowed battery support fully cover the current heat-pump power, and
	 * (at entry) the elevated setpoint exceeds the heat pump's own setpoint by
	 * the configured minimum delta. Ends by simply releasing the setpoint - the
	 * heat pump then finishes the run on its own.
	 *
	 * @param surplusPower    current natural PV surplus in W
	 * @param maxSupportPower upper bound of the battery support power in W (0 if no
	 *                        free energy)
	 * @param heatPumpPower   current heat pump consumption in W
	 * @throws OpenemsNamedException on error
	 */
	private void handleRunExtension(int surplusPower, int maxSupportPower, int heatPumpPower)
			throws OpenemsNamedException {
		if (!this.config.runExtensionEnabled()) {
			this.runExtensionActive = false;
			return;
		}
		var naturalRunActive = this.heatPump.getOperatingModeStatus()
				.orElse(-1) == HeatShiHeatPump.OPERATING_MODE_HOT_WATER
				&& this.heatPump.getHotWaterStatus().orElse(0) == HeatShiHeatPump.STATUS_ACTIVE;
		// Full coverage from PV surplus plus deliverable battery power. Re-evaluated
		// every cycle - when the free energy is exhausted (maxSupportPower drops to
		// 0) and the surplus no longer covers the heat pump, the extension ends. No
		// commit check is needed here: unlike a boost, the extension holds no
		// compressor cycle and simply releases the setpoint.
		var fullyCovered = heatPumpPower > 0 && surplusPower + maxSupportPower >= heatPumpPower;

		if (this.runExtensionActive) {
			if (!naturalRunActive || !fullyCovered) {
				this.runExtensionActive = false;
				return; // applyNormalMode releases the hot-water registers
			}
		} else {
			if (!naturalRunActive || !fullyCovered || this.naturalHotWaterSetpoint == null
					|| this.hotWaterSetpointDeciDegree
							- this.naturalHotWaterSetpoint < this.extensionMinDeltaDeciKelvin) {
				return;
			}
			this.runExtensionActive = true;
		}
		this.heatPump.setHotWaterMode(HeatShiHeatPump.MODE_SETPOINT);
		this.heatPump.setHotWaterSetpoint(this.hotWaterSetpointDeciDegree);
		// Soft-limit the heat pump to the covered power: the raised setpoint
		// invites a power increase, which must not draw grid power before the
		// coverage check of the next cycle would end the extension
		this.heatPump.setLpcMode(HeatShiHeatPump.LPC_MODE_SOFT);
		this.heatPump.setPcLimit(Math.max(0, Math.min(MAX_PC_LIMIT, surplusPower + maxSupportPower)));
	}

	/**
	 * Ensures the battery serves the heat pump exactly with the allowed support
	 * power — independent of elevated mode, so regular heat-pump runs (e.g. hot
	 * water in the evening) are also covered while the night reserve is
	 * guaranteed. Depending on the topology this works in opposite directions.
	 * Requires this Controller to run before the Balancing Controller in the
	 * Scheduler.
	 *
	 * <p>
	 * BEHIND_GRID_METER: Balancing would serve the heat pump from the battery by
	 * default, so ESS discharge is LIMITED to the household demand plus the
	 * allowed support power (applied even with disabled support, to protect the
	 * battery for the household).
	 *
	 * <p>
	 * GRID_SIDE_OF_GRID_METER: Balancing never sees the heat pump, so a minimum
	 * ESS power FORCES the support power out through the grid meter. The export is
	 * capped at the current heat-pump consumption that is not already covered by
	 * PV export, so battery energy is not sold to the grid.
	 *
	 * @param maxSupportPower upper bound of the battery support power in W (0 if no
	 *                        free energy); the real support is the uncovered
	 *                        heat-pump power capped at this and clamped by the ESS
	 * @param surplusPower    current natural PV surplus in W
	 * @param heatPumpPower   current heat pump consumption in W
	 * @return the battery support power actually applied in W
	 * @throws OpenemsNamedException on error
	 */
	private int applyEssSupport(int maxSupportPower, int surplusPower, int heatPumpPower) throws OpenemsNamedException {
		switch (this.config.heatPumpPosition()) {
		case BEHIND_GRID_METER -> {
			this._setEssForcedExportPower(null);
			// Only the uncovered part of the heat pump needs battery; capped at the
			// deliverable support power and clamped by the ESS below.
			var supportPower = Math.min(maxSupportPower, heatPumpPower);
			ManagedSymmetricEss ess = this.componentManager.getComponent(this.config.ess_id());
			var householdDischarge = Math.max(0,
					this.sum.getGridActivePower().orElse(0) + this.sum.getEssActivePower().orElse(0) - heatPumpPower);
			var limit = ess.getPower().fitValueIntoMinMaxPower(this.id(), ess, ALL, ACTIVE,
					householdDischarge + supportPower);
			ess.setActivePowerLessOrEquals(limit);
			this._setEssDischargeLimit(limit);
			return supportPower;
		}
		case GRID_SIDE_OF_GRID_METER -> {
			this._setEssDischargeLimit(null);
			var forcedExportPower = Math.min(maxSupportPower, Math.max(0, heatPumpPower - surplusPower));
			this._setEssForcedExportPower(forcedExportPower);
			if (forcedExportPower <= 0) {
				return 0;
			}
			ManagedSymmetricEss ess = this.componentManager.getComponent(this.config.ess_id());
			var requiredPower = this.sum.getEssActivePower().orElse(0) + this.sum.getGridActivePower().orElse(0)
					+ forcedExportPower;
			requiredPower = ess.getPower().fitValueIntoMinMaxPower(this.id(), ess, ALL, ACTIVE, requiredPower);
			ess.setActivePowerGreaterOrEquals(requiredPower);
			return forcedExportPower;
		}
		}
		return 0;
	}

	/**
	 * Calculates the battery energy in Wh that may be used for the heat pump:
	 * usable energy above Min-SoC minus the night reserve, i.e. the forecasted
	 * cumulative household deficit until the next sustained PV surplus.
	 *
	 * @return spare energy in Wh, or 0 if unknown or nothing to spare
	 */
	private int calculateSpareEssEnergy() {
		this._setNoPredictionAvailable(false);
		var essSoc = this.sum.getEssSoc().orElse(0);
		var essCapacity = this.sum.getEssCapacity().orElse(0);
		if (essCapacity <= 0) {
			return 0;
		}
		var usableEnergy = Math.round(essCapacity * (essSoc - this.config.minSoc()) / 100F);
		if (usableEnergy <= 0) {
			return 0;
		}
		if (this.predictorManager == null) {
			this._setNoPredictionAvailable(true);
			return 0;
		}
		var productionPrediction = this.getProductionPrediction();
		var consumptionPrediction = this.getConsumptionPrediction();
		if (productionPrediction.isEmpty() || consumptionPrediction.isEmpty()) {
			this._setNoPredictionAvailable(true);
			return 0;
		}
		// Behind the grid meter the consumption prediction includes the heat pump.
		// If a prediction for the heat-pump channel is available it is subtracted;
		// otherwise the night reserve stays conservative (too high).
		var heatPumpPrediction = this.config.heatPumpPosition() == HeatPumpPosition.BEHIND_GRID_METER
				? this.predictorManager.getPrediction(
						new ChannelAddress(this.heatPump.id(), ElectricityMeter.ChannelId.ACTIVE_POWER.id()))
				: Prediction.EMPTY_PREDICTION;

		var reserveEnergy = Math.round(this.calculateNightReserveEnergy(productionPrediction, consumptionPrediction,
				heatPumpPrediction) * this.config.nightReserveBuffer() / 100F);
		this._setNightReserveEnergy(reserveEnergy);
		return Math.max(0, usableEnergy - reserveEnergy);
	}

	/**
	 * Gets the production prediction. Prefers the 'Unmanaged' channel (served
	 * e.g. by the weather-based Predictor.Production.LinearModel) and falls back
	 * to the plain channel (served e.g. by the Persistence-Model defaults).
	 *
	 * @return the {@link Prediction}; may be empty
	 */
	private Prediction getProductionPrediction() {
		var prediction = this.predictorManager.getPrediction(SUM_UNMANAGED_PRODUCTION_ACTIVE_POWER);
		if (prediction.isEmpty()) {
			prediction = this.predictorManager.getPrediction(SUM_PRODUCTION_ACTIVE_POWER);
		}
		return prediction;
	}

	/**
	 * Gets the consumption prediction. Prefers the 'Unmanaged' channel (managed
	 * consumers like EVCS are planned by the EMS and do not belong into the
	 * household night reserve) and falls back to the plain channel.
	 *
	 * @return the {@link Prediction}; may be empty
	 */
	private Prediction getConsumptionPrediction() {
		var prediction = this.predictorManager.getPrediction(SUM_UNMANAGED_CONSUMPTION_ACTIVE_POWER);
		if (prediction.isEmpty()) {
			prediction = this.predictorManager.getPrediction(SUM_CONSUMPTION_ACTIVE_POWER);
		}
		return prediction;
	}

	/**
	 * Calculates the maximum cumulative energy deficit (household consumption
	 * above production) in Wh over the prediction horizon. Interim surplus reduces
	 * the running deficit, so this reflects the battery energy needed to keep the
	 * household covered until PV recharges the battery again.
	 *
	 * @param productionPrediction  production prediction per quarter-hour
	 * @param consumptionPrediction consumption prediction per quarter-hour
	 * @param heatPumpPrediction    heat-pump consumption prediction per
	 *                              quarter-hour; subtracted from consumption when
	 *                              the heat pump is part of it
	 * @return required reserve energy in Wh
	 */
	private int calculateNightReserveEnergy(Prediction productionPrediction, Prediction consumptionPrediction,
			Prediction heatPumpPrediction) {
		var productions = productionPrediction.asArray();
		var consumptions = consumptionPrediction.asArray();
		var heatPumps = heatPumpPrediction.asArray();

		var now = ZonedDateTime.now(this.componentManager.getClock());
		var remainingHoursCurrentQuarter = Math.max(0F,
				(15F - now.get(ChronoField.MINUTE_OF_HOUR) % 15F - now.getSecond() / 60F) / 60F);

		float running = 0;
		float max = 0;
		for (var i = 0; i < Math.min(productions.length, consumptions.length); i++) {
			var production = productions[i];
			var consumption = consumptions[i];
			if (production == null || consumption == null) {
				continue;
			}
			var heatPump = i < heatPumps.length && heatPumps[i] != null ? Math.max(0, heatPumps[i]) : 0;
			var durationHours = i == 0 ? remainingHoursCurrentQuarter : 0.25F;
			running += (consumption - heatPump - production) * durationHours;
			if (running < 0) {
				running = 0;
			}
			max = Math.max(max, running);
		}
		return Math.round(max);
	}

	@Override
	public String debugLog() {
		return "Elevated=" + this.elevatedModeActive //
				+ "|RunExt=" + this.runExtensionActive //
				+ "|Support=" + this.getEssSupportPower().asOptional().orElse(null) //
				+ (switch (this.config.heatPumpPosition()) {
				case BEHIND_GRID_METER -> "|DischargeLimit=" + this.getEssDischargeLimit().asOptional().orElse(null);
				case GRID_SIDE_OF_GRID_METER ->
					"|ForcedExport=" + this.getEssForcedExportPower().asOptional().orElse(null);
				});
	}
}
