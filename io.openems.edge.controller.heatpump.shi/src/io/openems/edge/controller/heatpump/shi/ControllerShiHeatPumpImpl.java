package io.openems.edge.controller.heatpump.shi;

import static io.openems.edge.common.type.Phase.SingleOrAllPhase.ALL;
import static io.openems.edge.ess.power.api.Pwr.ACTIVE;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoField;
import java.time.temporal.ChronoUnit;
import java.util.NavigableMap;
import java.util.TreeMap;

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
import io.openems.common.utils.DateUtils;
import io.openems.edge.common.component.AbstractOpenemsComponent;
import io.openems.edge.common.component.ComponentManager;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.cycle.Cycle;
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

	/**
	 * Power margin the coverage must exceed to START a run extension. Together with
	 * the re-entry lock this forms an on-margin / re-entry-lock pair that keeps the
	 * extension (and thus HR10005/LPC) from toggling near the coverage limit -
	 * without holding the setpoint elevation while coverage is lost.
	 */
	private static final int RUN_EXTENSION_START_MARGIN = 200; // [W]

	/**
	 * Power margin the coverage may fall short of the heat-pump power before a
	 * running boost is considered uncovered. Together with the switch-off delay it
	 * keeps the elevated mode from dropping on brief coverage dips at the limit
	 * (the soft power limit couples the heat-pump power to the surplus, so the raw
	 * coverage sits near the heat-pump power without this margin).
	 */
	private static final int ELEVATED_HOLD_MARGIN = 200; // [W]

	/**
	 * Coverage must exceed the required power by this margin before a running boost
	 * counts as clearly covered again - the upper edge of the power dead-band. Below
	 * {@code requiredPower - ELEVATED_HOLD_MARGIN} it is uncovered, above
	 * {@code requiredPower + ELEVATED_RECOVERY_MARGIN} clearly covered, and in
	 * between the uncovered time budget is frozen (measurement noise near the limit
	 * barely moves the state).
	 */
	private static final int ELEVATED_RECOVERY_MARGIN = 200; // [W]

	/** The uncovered time budget decays this many times faster than it builds while clearly covered. */
	private static final int UNCOVERED_RECOVERY_FACTOR = 2;

	/** The entry confirmation budget decays this many times faster than it builds while the conditions are not met. */
	private static final int CONFIRMATION_DECAY_FACTOR = 3;

	/**
	 * A gap longer than {@code max(MIN_EVALUATION_GAP_MILLIS, cycleTime x
	 * GAP_CYCLE_FACTOR)} since the previous run (controller not scheduled, restart)
	 * is treated as a discontinuity: the unobserved time is not credited AND the
	 * progress collected before it is reset, so neither an entry nor a drop is based
	 * on stale confirmation / switch-off progress. Deriving the threshold from the
	 * core cycle time keeps the budgets advancing even at long cycle times; the floor
	 * keeps normal cycle jitter from being mistaken for a gap.
	 */
	private static final long MIN_EVALUATION_GAP_MILLIS = 10_000;
	private static final int GAP_CYCLE_FACTOR = 4;

	/** Valid range of the SHI setpoint registers (0.1 degC), from the protocol. */
	private static final int MIN_HEATING_SETPOINT_DECIDEGREE = 150; // HR10001: 15 degC
	private static final int MAX_HEATING_SETPOINT_DECIDEGREE = 750; // HR10001: 75 degC
	private static final int MIN_HOT_WATER_SETPOINT_DECIDEGREE = 300; // HR10006: 30 degC
	private static final int MAX_HOT_WATER_SETPOINT_DECIDEGREE = 750; // HR10006: 75 degC

	/**
	 * Minimum forecast horizon (quarters) that must be present, gap-free and
	 * starting in the current quarter before any battery energy is released. 96 =
	 * 24 h - enough to cover the night deficit the reserve promises to bridge. A
	 * shorter or later-starting forecast is treated as unavailable (conservative).
	 */
	private static final int REQUIRED_FORECAST_QUARTERS = 96;

	@Reference
	private ConfigurationAdmin cm;

	@Reference
	private ComponentManager componentManager;

	@Reference
	private Sum sum;

	@Reference(policy = ReferencePolicy.DYNAMIC, policyOption = ReferencePolicyOption.GREEDY, cardinality = ReferenceCardinality.OPTIONAL)
	private volatile Cycle cycle;

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
	// Time-based hysteresis budgets (ms), evaluated against the wall clock rather
	// than cycle counts so they are independent of the cycle period.
	// confirmationMillis builds toward the boost confirmation time while the entry
	// conditions hold; uncoveredMillis builds toward the switch-off delay while a
	// running boost is uncovered. Both decay instead of resetting, so brief opposite
	// cycles do not wipe the accumulated progress.
	private long confirmationMillis = 0;
	private long uncoveredMillis = 0;
	private Instant lastCoverageEvaluation = null;
	private boolean runExtensionActive = false;
	// End of the last run extension; re-entry is locked until minimumSwitchingTime
	// after it, so the extension cannot restart immediately after being released.
	private Instant runExtensionEndedAt = Instant.MIN;
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
		// On a heat-pump-ID change the old device must be released first, otherwise
		// a previously written setpoint elevation persists on a device this
		// Controller no longer regulates. The reference is still bound to the old
		// device at this point, so the release is queued on its channels.
		if (this.config != null && !this.config.heatPump_id().equals(config.heatPump_id())) {
			this.releaseHeatPump();
		}
		super.modified(context, config.id(), config.alias(), config.enabled());
		this.updateConfig(config);
	}

	private void updateConfig(Config config) {
		this.config = config;
		// Clamp the setpoints into the valid SHI register ranges (HR10001 15..75 degC,
		// HR10006 30..75 degC) so a misconfiguration cannot write an out-of-range
		// value to the heat pump.
		this.heatingSetpointDeciDegree = clamp(MIN_HEATING_SETPOINT_DECIDEGREE,
				(int) Math.round(config.heatingSetpoint() * 10), MAX_HEATING_SETPOINT_DECIDEGREE);
		this.hotWaterSetpointDeciDegree = clamp(MIN_HOT_WATER_SETPOINT_DECIDEGREE,
				(int) Math.round(config.hotWaterSetpoint() * 10), MAX_HOT_WATER_SETPOINT_DECIDEGREE);
		this.extensionMinDeltaDeciKelvin = Math.max(0, (int) Math.round(config.extensionMinTemperatureDelta() * 10));
		OpenemsComponent.updateReferenceFilter(this.cm, this.servicePid(), "heatPump", config.heatPump_id());
	}

	private static int clamp(int min, int value, int max) {
		return Math.max(min, Math.min(max, value));
	}

	@Override
	@Deactivate
	protected void deactivate() {
		// Release the heat pump before unbinding, so no elevation persists once this
		// Controller no longer regulates.
		this.releaseHeatPump();
		super.deactivate();
	}

	/**
	 * Releases all external influence on the heat pump: heating and hot-water
	 * setpoint modes back to "no influence" and the LPC soft limit off. Best-effort
	 * (swallows errors), used on deactivation, on heat-pump-ID changes and when a
	 * cycle cannot complete, so a previously written elevation does not persist
	 * while this Controller is not regulating.
	 */
	private void releaseHeatPump() {
		// Release each register independently and unconditionally: gating on the
		// status channels would skip the release whenever a status is momentarily
		// invalid (orElse(0)), and one register the SHI rejects (e.g. a mode that is
		// off) must not prevent releasing the others.
		this.tryRelease(() -> this.heatPump.setHeatingMode(HeatShiHeatPump.MODE_NONE));
		this.tryRelease(() -> this.heatPump.setHotWaterMode(HeatShiHeatPump.MODE_NONE));
		this.tryRelease(() -> this.heatPump.setLpcMode(HeatShiHeatPump.LPC_MODE_NONE));
		this.tryRelease(() -> this.heatPump.setPcLimit(0));
		// Releasing an active run extension also arms the re-entry lock, so a release
		// caused by e.g. a brief measurement dropout cannot let the extension restart
		// immediately once the measurements return (flicker would re-enable toggling).
		if (this.runExtensionActive) {
			this.runExtensionEndedAt = Instant.now(this.componentManager.getClock());
		}
		this.elevatedModeActive = false;
		this.confirmationMillis = 0;
		this.uncoveredMillis = 0;
		this.lastCoverageEvaluation = null;
		this.runExtensionActive = false;
	}

	private void tryRelease(SetpointWrite write) {
		try {
			write.apply();
		} catch (OpenemsNamedException | RuntimeException e) {
			// best-effort release; nothing more can be done here
		}
	}

	@FunctionalInterface
	private interface SetpointWrite {
		void apply() throws OpenemsNamedException;
	}

	@Override
	public void run() throws OpenemsNamedException {
		try {
			this.runOnce();
		} catch (OpenemsNamedException | RuntimeException e) {
			// Fail-safe: this cycle could not complete (e.g. the ESS component is
			// missing). Release any external influence so a previously written
			// setpoint elevation cannot persist while the Controller is not
			// regulating, then rethrow so the fault stays visible.
			this.releaseHeatPump();
			throw e;
		}
	}

	private void runOnce() throws OpenemsNamedException {
		this.checkHeatPumpMeterType();
		// Surface silently ignored writes: with the device in read-only mode all
		// setpoint commands of this Controller have no effect
		this._setControlNotAllowed(this.heatPump.getReadOnlyMode().orElse(false));

		// Fail-safe against invalid measurements: without a valid grid, ESS and
		// heat-pump power reading the PV surplus cannot be verified (a missing value
		// read as 0 W would fake a surplus). Do not start or hold elevated mode and
		// do not grant battery support; release the heat pump and leave the ESS to
		// the Balancing Controller.
		var gridPowerValue = this.sum.getGridActivePower().asOptional();
		var essPowerValue = this.sum.getEssDischargePower().asOptional();
		var heatPumpPowerValue = this.heatPump.getActivePower().asOptional();
		if (gridPowerValue.isEmpty() || essPowerValue.isEmpty() || heatPumpPowerValue.isEmpty()) {
			this._setPowerMeasurementUnavailable(true);
			this.releaseHeatPump();
			this._setBoostPending(false);
			this._setBoostForecastVeto(false);
			this._setEssForcedExportPower(null);
			this._setEssDischargeLimit(null);
			this._setElevatedModeActive(false);
			this._setRunExtensionActive(false);
			this._setFreeBatteryEnergy(0);
			this._setNightReserveEnergy(null);
			this._setEssSupportPower(0);
			return;
		}
		this._setPowerMeasurementUnavailable(false);

		var gridActivePower = gridPowerValue.get();
		var essDischargePower = Math.max(0, essPowerValue.get());
		final var heatPumpPower = Math.max(0, heatPumpPowerValue.get());
		// PV surplus available for the heat pump, after household and battery
		// charging took their share and without counting battery discharge:
		// - BEHIND_GRID_METER: heat pump consumption is part of the grid
		// measurement, so it must be added back to avoid eating its own surplus.
		// - GRID_SIDE_OF_GRID_METER: heat pump consumption is invisible at the
		// grid meter and served by whatever this segment exports.
		final var surplusPower = switch (this.config.heatPumpPosition()) {
		case BEHIND_GRID_METER -> Math.max(0, -gridActivePower - essDischargePower + heatPumpPower);
		case GRID_SIDE_OF_GRID_METER -> Math.max(0, -gridActivePower - essDischargePower);
		};

		// The heat pump reports its minimum predicted power consumption (IR10302);
		// starting elevated mode below it would only shift grid consumption
		final var minimumPower = Math.max(this.config.minimumSurplusPowerForElevatedMode(),
				this.heatPump.getMinPredictedActivePower().orElse(0));

		// Energy release gate: as long as free battery energy above the night
		// reserve exists, the battery may support the heat pump. The support POWER
		// is NOT derived from this energy via an assumed duration - it is the real,
		// currently needed and deliverable power (uncovered heat-pump power, clamped
		// by the ESS and an optional cap). maxSupportPower is that upper bound: 0
		// when no energy is free (or support is disabled), otherwise the power the
		// ESS can actually deliver right now MINUS the share already needed for the
		// household, capped by the configured maximum. Bounding by the real power
		// left for the heat pump - instead of the full discharge power or the soft-
		// limit range - keeps the boost and run-extension coverage checks honest, so
		// a battery already busy serving the household cannot make the heat pump look
		// fully covered and then leave the gap to the grid.
		var spareEssEnergy = this.calculateSpareEssEnergy();
		var energyAvailable = this.config.essSupportEnabled() && spareEssEnergy > 0;
		final var maxSupportPower = !energyAvailable ? 0
				: this.deliverableSupportPower(gridActivePower, heatPumpPower);
		// Battery power the heat pump may be actively DRIVEN with (boost soft limit,
		// run-extension coverage): the full deliverable support in OFFENSIVE mode, 0
		// in CLOUD_BUFFER mode where the heat pump follows the PV surplus alone. The
		// passive support below always uses maxSupportPower, so a heat pump running
		// on its own is still paid from the battery in both modes.
		final var invitedSupportPower = this.config.batterySupportMode() == BatterySupportMode.OFFENSIVE
				? maxSupportPower
				: 0;

		this.updateNaturalHotWaterSetpoint();

		// Elevated mode starts and holds on REAL PV surplus alone. Running the heat
		// pump on surplus is the base case, so the absence of free battery energy -
		// support disabled, battery drained, or no prediction - must NOT block a
		// boost when the sun alone is strong enough. Free battery energy, when
		// present, is not an entry precondition: it is spent cyclically during the
		// run to ride out clouds from the battery instead of the grid (see
		// applyEssSupport), and it stops when exhausted. To gate entry on the
		// forecast (only start when the sun is predicted to sustain the committed
		// cycle) enable the optional forecast veto.
		var now = Instant.now(this.componentManager.getClock());
		var rawElapsedMillis = this.lastCoverageEvaluation == null ? 0L
				: Math.max(0L, Duration.between(this.lastCoverageEvaluation, now).toMillis());
		this.lastCoverageEvaluation = now;
		var cycleTimeMillis = this.cycle != null ? this.cycle.getCycleTime() : Cycle.DEFAULT_CYCLE_TIME;
		var maxGapMillis = Math.max(MIN_EVALUATION_GAP_MILLIS, (long) cycleTimeMillis * GAP_CYCLE_FACTOR);
		long elapsedMillis;
		if (rawElapsedMillis > maxGapMillis) {
			// Large gap (controller not scheduled, restart): discard the unobserved
			// time AND reset the accumulated progress, so the situation is re-evaluated
			// from the fresh readings rather than an entry or drop firing on stale
			// budgets (e.g. a near-complete confirmation surviving a ten-minute pause).
			this.confirmationMillis = 0;
			this.uncoveredMillis = 0;
			elapsedMillis = 0L;
		} else {
			elapsedMillis = rawElapsedMillis;
		}

		var sunSufficient = surplusPower >= minimumPower;
		var boostConfirmed = this.updateBoostConfirmation(sunSufficient, elapsedMillis);
		var forecastVetoed = !this.elevatedModeActive && sunSufficient
				&& this.isForecastVetoed(minimumPower);
		this._setBoostForecastVeto(forecastVetoed);

		// Hold the running boost while its power stays COVERED and drop it only after
		// coverage has been lost for the switch-off delay in SUM. Coverage is the PV
		// surplus plus the invited battery support (deliverable in OFFENSIVE, 0 in
		// CLOUD_BUFFER) compared against the ACTUAL heat-pump power (the minimum power
		// while the heat pump is momentarily idle), not a fixed threshold - so a boost
		// carried by the battery is only dropped once even the battery can no longer
		// cover it. Two hystereses keep it noise-robust: a POWER dead-band (uncovered
		// only below requiredPower - hold margin, clearly covered only above
		// requiredPower + recovery margin, frozen in between) and a decaying TIME budget
		// (uncovered time accumulates and decays faster while clearly covered), so a few
		// good cycles cannot wipe the whole switch-off progress.
		var coverage = surplusPower + invitedSupportPower;
		var requiredPower = heatPumpPower > 0 ? heatPumpPower : minimumPower;
		var uncovered = coverage < requiredPower - ELEVATED_HOLD_MARGIN;
		var clearlyCovered = coverage > requiredPower + ELEVATED_RECOVERY_MARGIN;
		var switchOffMillis = this.config.switchOffDelay() * 1000L;
		if (this.elevatedModeActive) {
			if (uncovered) {
				this.uncoveredMillis += elapsedMillis;
			} else if (clearlyCovered) {
				this.uncoveredMillis -= elapsedMillis * UNCOVERED_RECOVERY_FACTOR;
			}
			this.uncoveredMillis = Math.max(0L, Math.min(switchOffMillis, this.uncoveredMillis));
		} else {
			this.uncoveredMillis = 0L;
		}
		// With a zero delay the drop follows the current uncovered reading immediately;
		// otherwise the accumulated uncovered time must reach the delay.
		var sustainedUncovered = switchOffMillis <= 0 ? uncovered : this.uncoveredMillis >= switchOffMillis;

		var shouldElevate = this.elevatedModeActive //
				? !sustainedUncovered //
				: sunSufficient && boostConfirmed && !forecastVetoed;
		if (this.isHysteresisActive() && shouldElevate != this.elevatedModeActive) {
			shouldElevate = this.elevatedModeActive;
		}
		if (shouldElevate != this.elevatedModeActive) {
			this.elevatedModeActive = shouldElevate;
			this.lastModeChange = now;
			this.uncoveredMillis = 0L;
		}

		if (this.elevatedModeActive) {
			// A running extension is absorbed by the elevated mode
			this.runExtensionActive = false;
			// Never push the soft power limit below the minimum power while elevated:
			// during the switching hysteresis a short surplus dip would otherwise
			// shut down the compressor via a 0 W limit - the SHI documentation
			// explicitly recommends a switch-off delay for PV-surplus operation
			this.applyElevatedMode(Math.max(minimumPower, surplusPower + invitedSupportPower));
		} else {
			this.handleRunExtension(surplusPower, invitedSupportPower, heatPumpPower);
			// The soft limit of a self-started run follows the PASSIVE support
			// (maxSupportPower), which pays for such a run in BOTH battery-support
			// modes - unlike the invited support, which only drives the boost and the
			// run extension and is 0 in CLOUD_BUFFER.
			this.applyNormalMode(surplusPower + maxSupportPower, minimumPower);
		}
		var appliedSupport = this.applyEssSupport(maxSupportPower, surplusPower, heatPumpPower);

		this._setElevatedModeActive(this.elevatedModeActive);
		// The free battery energy is the energy released to the heat pump: it is the
		// spare energy above the night reserve, but 0 when battery support is
		// disabled (nothing is released, regardless of the physical reserve).
		this._setFreeBatteryEnergy(energyAvailable ? spareEssEnergy : 0);
		this._setEssSupportPower(appliedSupport);
		this._setRunExtensionActive(this.runExtensionActive);
	}

	/**
	 * Upper bound of the battery support power available for the heat pump: the
	 * power the ESS can currently deliver (its allowed discharge power) minus the
	 * share the battery already needs for the household, capped by the configured
	 * maximum if set. Subtracting the household share is essential: a battery that
	 * can discharge 3 kW while the household draws 2 kW has only 1 kW left for the
	 * heat pump, and the coverage checks must see that 1 kW - not the full 3 kW.
	 *
	 * @param gridActivePower current grid power in W (import positive)
	 * @param heatPumpPower   current heat pump consumption in W
	 * @return deliverable support power for the heat pump in W
	 * @throws OpenemsNamedException if the ESS component is not available
	 */
	private int deliverableSupportPower(int gridActivePower, int heatPumpPower) throws OpenemsNamedException {
		ManagedSymmetricEss ess = this.componentManager.getComponent(this.config.ess_id());
		var essActivePower = this.sum.getEssActivePower().orElse(0);
		// Power the battery currently discharges for the household (its share),
		// which must be reserved before offering the remainder to the heat pump.
		// Behind the meter the heat pump is part of the measurement, so it is
		// removed; grid-side it is invisible and only the household segment counts.
		var householdReserved = switch (this.config.heatPumpPosition()) {
		case BEHIND_GRID_METER -> Math.max(0, gridActivePower + essActivePower - heatPumpPower);
		case GRID_SIDE_OF_GRID_METER -> Math.max(0, gridActivePower + essActivePower);
		};
		var deliverable = Math.max(0, ess.getPower().getMaxPower(ess, ALL, ACTIVE) - householdReserved);
		var cap = this.config.maxBatterySupportPower();
		return cap > 0 ? Math.min(cap, deliverable) : deliverable;
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

	/**
	 * Normal mode: the setpoints are released - a run the heat pump started on its
	 * own (heating in particular) must not be elevated, that would overheat the
	 * house. The soft power limit is still written while such a run is active, so
	 * the heat pump can modulate down onto the power PV and the battery actually
	 * pay for (see the passive support layer), instead of taking the difference
	 * from the grid. The soft limit is a recommendation: the heat pump discards it
	 * once its temperature deviates too far from its setpoint, so a genuine heat
	 * demand is protected by the device itself. The case that must be avoided is
	 * the opposite one - close to the setpoint the heat pump does obey, and a limit
	 * below its minimum sensible power would push it into a compressor stop or
	 * short cycling. The limit is therefore written exclusively while the coverage
	 * carries at least the minimum power, and released entirely below that, where
	 * a "use almost nothing" recommendation would be dishonest anyway.
	 *
	 * @param coveredPower the power covered by PV surplus plus deliverable battery
	 *                     support in W
	 * @param minimumPower the minimum sensible heat-pump power in W
	 * @throws OpenemsNamedException on write error
	 */
	private void applyNormalMode(int coveredPower, int minimumPower) throws OpenemsNamedException {
		if (this.heatPump.getHeatingStatus().orElse(0) > 0) {
			this.heatPump.setHeatingMode(HeatShiHeatPump.MODE_NONE);
		}
		// The hot-water and LPC registers belong to the run extension while it is
		// active
		if (!this.runExtensionActive) {
			if (this.heatPump.getHotWaterStatus().orElse(0) > 0) {
				this.heatPump.setHotWaterMode(HeatShiHeatPump.MODE_NONE);
			}
			var naturalRunActive = this.heatPump.getHeatingStatus().orElse(0) == HeatShiHeatPump.STATUS_ACTIVE
					|| this.heatPump.getHotWaterStatus().orElse(0) == HeatShiHeatPump.STATUS_ACTIVE;
			if (naturalRunActive && coveredPower >= minimumPower) {
				this.heatPump.setLpcMode(HeatShiHeatPump.LPC_MODE_SOFT);
				this.heatPump.setPcLimit(Math.min(MAX_PC_LIMIT, coveredPower));
			} else {
				this.heatPump.setLpcMode(HeatShiHeatPump.LPC_MODE_NONE);
				this.heatPump.setPcLimit(0);
			}
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
	 * @param elapsedMillis   time since the previous evaluation in ms
	 * @return true once the conditions held for the confirmation time in sum
	 */
	private boolean updateBoostConfirmation(boolean entryConditions, long elapsedMillis) {
		if (this.elevatedModeActive) {
			this.confirmationMillis = 0;
			this._setBoostPending(false);
			return false;
		}
		// Build the confirmation while the entry conditions hold and decay it FASTER
		// (a start is more consequential than a continuation) while they do not - but
		// a single bad cycle no longer wipes the whole progress.
		if (entryConditions) {
			this.confirmationMillis += elapsedMillis;
		} else {
			this.confirmationMillis -= elapsedMillis * CONFIRMATION_DECAY_FACTOR;
		}
		var target = this.config.boostConfirmationSeconds() * 1000L;
		this.confirmationMillis = Math.max(0L, Math.min(target, this.confirmationMillis));
		var confirmed = this.confirmationMillis >= target;
		// Pending reflects the documented meaning "conditions fulfilled, waiting" -
		// false during a dip even though the accumulated progress only decays, not
		// resets.
		this._setBoostPending(entryConditions && !confirmed);
		return confirmed;
	}

	/**
	 * Optional forecast veto for elevated-mode entry: the prediction must show
	 * enough PV surplus alone for the duration of a compressor cycle. Lenient by
	 * design - without a prediction or with missing
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
		// Align production and consumption by their Instant keys over the commit
		// window - asArray() would drop interior gaps and shift the two series against
		// each other. A quarter missing in either forecast is skipped; the veto is
		// lenient and only fires on a quarter that is actually forecast too weak.
		var productions = productionPrediction.toMapWithAllQuarters();
		var consumptions = consumptionPrediction.toMapWithAllQuarters();

		// The veto requires the PV surplus ALONE to sustain the commit - the battery
		// is not credited here. Battery support during the run is opportunistic (it
		// rides out clouds while free energy lasts), so enabling the veto means
		// "only boost if the sun itself is forecast to sustain the committed cycle".
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
		var quarter = DateUtils.roundDownToQuarter(Instant.now(this.componentManager.getClock()));
		for (var i = 0; i < quarters; i++) {
			var production = productions.get(quarter);
			var consumption = consumptions.get(quarter);
			if (production != null && consumption != null && production - consumption < minimumPower) {
				return true;
			}
			quarter = quarter.plus(15, ChronoUnit.MINUTES);
		}
		return false;
	}

	/**
	 * Opportunistically extends a natural hot-water run of the heat pump to the
	 * elevated hot-water setpoint - the compressor is already running, so the
	 * storage is topped up without an additional compressor start. A natural
	 * hot-water run must be active and the elevated setpoint must exceed the heat
	 * pump's own setpoint by the configured minimum delta. As soon as the coverage
	 * (PV surplus plus deliverable battery support) drops below the heat-pump power
	 * the extension is released immediately - the SHI soft power limit is only a
	 * recommendation the heat pump may ignore, so it must not be relied upon to
	 * avoid grid draw while coverage is lost. Toggling near the coverage limit is
	 * prevented by an on-margin (entry needs coverage above the heat-pump power by
	 * a margin) plus a re-entry lock (after release, re-entry is blocked for the
	 * minimum switching time) - not by holding the setpoint elevation.
	 *
	 * @param surplusPower        current natural PV surplus in W
	 * @param invitedSupportPower battery power the heat pump may be driven with in W
	 *                            (0 in CLOUD_BUFFER mode, so the extension then only
	 *                            runs when the sun alone covers it)
	 * @param heatPumpPower       current heat pump consumption in W
	 * @throws OpenemsNamedException on error
	 */
	private void handleRunExtension(int surplusPower, int invitedSupportPower, int heatPumpPower)
			throws OpenemsNamedException {
		if (!this.config.runExtensionEnabled()) {
			this.runExtensionActive = false;
			return;
		}
		var now = Instant.now(this.componentManager.getClock());
		var coverage = surplusPower + invitedSupportPower;
		var naturalRunActive = this.heatPump.getOperatingModeStatus()
				.orElse(-1) == HeatShiHeatPump.OPERATING_MODE_HOT_WATER
				&& this.heatPump.getHotWaterStatus().orElse(0) == HeatShiHeatPump.STATUS_ACTIVE;
		var covered = heatPumpPower > 0 && coverage >= heatPumpPower;
		// Start needs a power margin on top of full coverage; stopping only needs
		// coverage to be lost. The margin plus the re-entry lock below prevent
		// toggling without ever holding the elevation while uncovered.
		var coveredWithMargin = heatPumpPower > 0 && coverage >= heatPumpPower + RUN_EXTENSION_START_MARGIN;

		if (this.runExtensionActive) {
			// Release the moment coverage is lost (or the natural run ends) - do not
			// keep the elevated setpoint hoping the soft limit prevents grid draw.
			if (!naturalRunActive || !covered) {
				this.runExtensionActive = false;
				this.runExtensionEndedAt = now;
				return; // applyNormalMode releases the hot-water registers
			}
		} else {
			var reentryLocked = this.runExtensionEndedAt.plusSeconds(this.config.minimumSwitchingTime()).isAfter(now);
			if (!naturalRunActive || !coveredWithMargin || reentryLocked || this.naturalHotWaterSetpoint == null
					|| this.hotWaterSetpointDeciDegree
							- this.naturalHotWaterSetpoint < this.extensionMinDeltaDeciKelvin) {
				return;
			}
			this.runExtensionActive = true;
		}
		this.heatPump.setHotWaterMode(HeatShiHeatPump.MODE_SETPOINT);
		this.heatPump.setHotWaterSetpoint(this.hotWaterSetpointDeciDegree);
		// Soft-limit the heat pump to the covered power as a recommendation; the hard
		// guarantee against grid draw is the immediate release above, not this limit.
		this.heatPump.setLpcMode(HeatShiHeatPump.LPC_MODE_SOFT);
		this.heatPump.setPcLimit(Math.max(0, Math.min(MAX_PC_LIMIT, coverage)));
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
			var essActivePower = this.sum.getEssActivePower().orElse(0);
			var householdDischarge = Math.max(0,
					this.sum.getGridActivePower().orElse(0) + essActivePower - heatPumpPower);
			var limit = ess.getPower().fitValueIntoMinMaxPower(this.id(), ess, ALL, ACTIVE,
					householdDischarge + supportPower);
			ess.setActivePowerLessOrEquals(limit);
			this._setEssDischargeLimit(limit);
			// Here the battery is only given a discharge ALLOWANCE - whether it is
			// actually used for the heat pump depends on the PV. The actually active
			// support is therefore the measured battery discharge that exceeds the
			// household draw, capped at the granted allowance: 0 while the battery is
			// idle or charging (PV covers), rather than the full allowance. This is a
			// best-effort estimate from the measured flows, since with PV present the
			// battery cannot be split exactly between household and heat pump.
			return Math.max(0, Math.min(supportPower, Math.max(0, essActivePower) - householdDischarge));
		}
		case GRID_SIDE_OF_GRID_METER -> {
			this._setEssDischargeLimit(null);
			var forcedExportPower = Math.min(maxSupportPower, Math.max(0, heatPumpPower - surplusPower));
			if (forcedExportPower <= 0) {
				this._setEssForcedExportPower(0);
				return 0;
			}
			ManagedSymmetricEss ess = this.componentManager.getComponent(this.config.ess_id());
			var essAndGrid = this.sum.getEssActivePower().orElse(0) + this.sum.getGridActivePower().orElse(0);
			var requiredPower = ess.getPower().fitValueIntoMinMaxPower(this.id(), ess, ALL, ACTIVE,
					essAndGrid + forcedExportPower);
			ess.setActivePowerGreaterOrEquals(requiredPower);
			// The forced-export target may be clamped down by the ESS; the actually
			// forced battery power towards the heat pump is the increment the ESS is
			// pushed above the present ess+grid flow, not the unclamped request.
			var actualSupport = Math.max(0, requiredPower - essAndGrid);
			this._setEssForcedExportPower(actualSupport);
			return actualSupport;
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
		// Reset so every early return below leaves the night reserve "unavailable"
		// (null) instead of a stale value from a previous successful calculation; the
		// success path overwrites it with the real reserve.
		this._setNightReserveEnergy(null);
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
		if (productionPrediction.isEmpty()) {
			this._setNoPredictionAvailable(true);
			return 0;
		}
		// Build the household consumption forecast quarter by quarter over the night
		// horizon. Prefer the Unmanaged channel (managed consumers are planned by the
		// EMS and do not belong into the household reserve); where a quarter is
		// missing there, fall back to the plain consumption channel. The plain value
		// includes those managed consumers and is therefore >= the unmanaged one, so
		// the substitution is CONSERVATIVE (a slightly too-high household load
		// reserves a little more, never too little). If a quarter is missing in BOTH
		// channels the forecast cannot promise the night gap-free -> fail-safe,
		// release nothing. Real forecast values only, no interpolation. Missing
		// PRODUCTION is filled with 0 W in the flow calc (also conservative). The
		// same quarter rounding as the prediction keys lines the lookups up exactly.
		var unmanagedConsumption = this.predictorManager //
				.getPrediction(SUM_UNMANAGED_CONSUMPTION_ACTIVE_POWER).toMapWithAllQuarters();
		var totalConsumption = this.predictorManager //
				.getPrediction(SUM_CONSUMPTION_ACTIVE_POWER).toMapWithAllQuarters();
		var consumptionByQuarter = new TreeMap<Instant, Integer>();
		var quarter = DateUtils.roundDownToQuarter(Instant.now(this.componentManager.getClock()));
		for (var i = 0; i < REQUIRED_FORECAST_QUARTERS; i++) {
			var value = unmanagedConsumption.get(quarter);
			if (value == null) {
				value = totalConsumption.get(quarter);
			}
			if (value == null) {
				this._setNoPredictionAvailable(true);
				return 0;
			}
			consumptionByQuarter.put(quarter, value);
			quarter = quarter.plus(15, ChronoUnit.MINUTES);
		}
		// Behind the grid meter the consumption prediction includes the heat pump.
		// If a prediction for the heat-pump channel is available it is subtracted;
		// otherwise the night reserve stays conservative (too high).
		var heatPumpPrediction = this.config.heatPumpPosition() == HeatPumpPosition.BEHIND_GRID_METER
				? this.predictorManager.getPrediction(
						new ChannelAddress(this.heatPump.id(), ElectricityMeter.ChannelId.ACTIVE_POWER.id()))
				: Prediction.EMPTY_PREDICTION;

		var capacityAboveMin = Math.round(essCapacity * (100 - this.config.minSoc()) / 100F);
		var flows = this.quarterlyBatteryFlows(productionPrediction, consumptionByQuarter, heatPumpPrediction);
		var deficit = maxCumulativeDeficit(flows);
		var buffer = this.config.nightReserveBuffer();
		// The buffer is applied per mode. MAX_DEFICIT inflates the reserve directly.
		// SOC_TRAJECTORY turns the buffer into a CUSHION (a fraction of the overnight
		// deficit) that the forward SoC trajectory must keep above Min-SoC at all
		// times - applied inside the simulation, not as a multiplier afterwards. This
		// guarantees a real safety margin exactly in the aggressive case where the
		// trajectory would otherwise free everything (reserve 0): if the forecast
		// recharge is too optimistic, the household still has the cushion left.
		var reserveEnergy = switch (this.config.nightReserveMode()) {
		case MAX_DEFICIT -> Math.round(deficit * buffer / 100F);
		case SOC_TRAJECTORY -> {
			var cushion = Math.round(deficit * Math.max(0, buffer - 100) / 100F);
			yield usableEnergy - trajectoryFreeEnergy(usableEnergy, capacityAboveMin, flows, cushion);
		}
		};
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
	 * Builds the battery net charge per quarter (Wh, positive = charging) over the
	 * consumption forecast horizon: {@code production - household load}, where the
	 * household load is the consumption minus the heat-pump prediction (behind the
	 * meter). Aligned by TIME, not by array index - the dense value arrays shift
	 * when a series has an interior gap, so production and consumption would not
	 * line up per index. Production and heat-pump values are looked up by the same
	 * quarter and default to 0 W where missing (conservative). The first quarter
	 * counts only its remaining fraction.
	 *
	 * @param productionPrediction production prediction per quarter-hour
	 * @param consumptions         the validated household consumption per quarter,
	 *                             keyed by time (complete, no null values)
	 * @param heatPumpPrediction   heat-pump consumption prediction per quarter-hour;
	 *                             subtracted from consumption when the heat pump is
	 *                             part of it
	 * @return the per-quarter battery net charge in Wh
	 */
	private float[] quarterlyBatteryFlows(Prediction productionPrediction,
			NavigableMap<Instant, Integer> consumptions, Prediction heatPumpPrediction) {
		var productions = productionPrediction.toMapWithAllQuarters();
		var heatPumps = heatPumpPrediction.toMapWithAllQuarters();

		var now = ZonedDateTime.now(this.componentManager.getClock());
		var remainingHoursCurrentQuarter = Math.max(0F,
				(15F - now.get(ChronoField.MINUTE_OF_HOUR) % 15F - now.getSecond() / 60F) / 60F);

		var flows = new float[consumptions.size()];
		var i = 0;
		var first = true;
		for (var entry : consumptions.entrySet()) {
			var durationHours = first ? remainingHoursCurrentQuarter : 0.25F;
			first = false;
			int consumption = entry.getValue();
			var productionValue = productions.get(entry.getKey());
			var production = productionValue != null ? productionValue : 0;
			var heatPumpValue = heatPumps.get(entry.getKey());
			var heatPump = heatPumpValue != null ? Math.max(0, heatPumpValue) : 0;
			flows[i++] = (production - consumption + heatPump) * durationHours;
		}
		return flows;
	}

	/**
	 * MAX_DEFICIT reserve: the largest cumulative household deficit (interim
	 * charge reduces the running deficit, floored at 0) over the horizon. Does not
	 * credit the daytime recharge.
	 *
	 * @param flows the per-quarter battery net charge in Wh
	 * @return required reserve energy in Wh
	 */
	private static int maxCumulativeDeficit(float[] flows) {
		float running = 0;
		float max = 0;
		for (var flow : flows) {
			running -= flow;
			if (running < 0) {
				running = 0;
			}
			if (running > max) {
				max = running;
			}
		}
		return Math.round(max);
	}

	/**
	 * SOC_TRAJECTORY free energy: the most that can be removed from the battery
	 * now so the forward SoC trajectory (charged by the flows, clamped at the top
	 * to the capacity above Min-SoC, NOT clamped at the bottom) never drops below
	 * the cushion above Min-SoC. Because the top clamp lets midday PV refill the
	 * battery, energy removed in the morning is "given back" if the battery would
	 * fill anyway; the cushion is the safety margin the forecast must leave on top.
	 *
	 * @param usableEnergy     current energy above Min-SoC in Wh
	 * @param capacityAboveMin battery capacity above Min-SoC in Wh (top clamp)
	 * @param flows            the per-quarter battery net charge in Wh
	 * @param cushion          safety margin in Wh the trajectory must stay above
	 * @return removable (free) energy in Wh
	 */
	private static int trajectoryFreeEnergy(int usableEnergy, int capacityAboveMin, float[] flows, int cushion) {
		if (trajectoryMinimum(usableEnergy, capacityAboveMin, flows) < cushion) {
			return 0; // forecast does not keep the cushion even if nothing is removed
		}
		var lo = 0;
		var hi = usableEnergy;
		while (hi - lo > 1) {
			var mid = (lo + hi) / 2;
			if (trajectoryMinimum(usableEnergy - mid, capacityAboveMin, flows) >= cushion) {
				lo = mid;
			} else {
				hi = mid;
			}
		}
		return trajectoryMinimum(usableEnergy - hi, capacityAboveMin, flows) >= cushion ? hi : lo;
	}

	private static float trajectoryMinimum(float startEnergy, int capacityAboveMin, float[] flows) {
		var energy = startEnergy;
		var min = startEnergy;
		for (var flow : flows) {
			energy = Math.min(capacityAboveMin, energy + flow);
			if (energy < min) {
				min = energy;
			}
		}
		return min;
	}

	@Override
	public String debugLog() {
		if (!this.config.debugMode()) {
			return null;
		}
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
