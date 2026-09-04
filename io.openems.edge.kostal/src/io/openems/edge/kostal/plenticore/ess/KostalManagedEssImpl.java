package io.openems.edge.kostal.plenticore.ess;

import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_3;
import static io.openems.edge.bridge.modbus.api.ModbusUtils.FunctionCode.FC3;
import static io.openems.edge.bridge.modbus.api.ModbusUtils.readElementOnce;
import static io.openems.edge.bridge.modbus.api.element.WordOrder.LSWMSW;
import static io.openems.edge.common.type.Phase.SingleOrAllPhase.ALL;
import static io.openems.edge.ess.power.api.Pwr.ACTIVE;
import static io.openems.edge.common.channel.ChannelUtils.setValue;
import static io.openems.edge.common.event.EdgeEventConstants.TOPIC_CYCLE_BEFORE_CONTROLLERS;
import static io.openems.edge.common.event.EdgeEventConstants.TOPIC_CYCLE_BEFORE_PROCESS_IMAGE;
import static org.osgi.service.component.annotations.ReferenceCardinality.MANDATORY;
import static org.osgi.service.component.annotations.ReferenceCardinality.OPTIONAL;
import static org.osgi.service.component.annotations.ReferencePolicy.DYNAMIC;
import static org.osgi.service.component.annotations.ReferencePolicy.STATIC;
import static org.osgi.service.component.annotations.ReferencePolicyOption.GREEDY;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventHandler;
import org.osgi.service.event.propertytypes.EventTopics;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.common.referencetarget.GenerateTargetsFromReferences;
import io.openems.edge.bridge.modbus.api.AbstractOpenemsModbusComponent;
import io.openems.edge.bridge.modbus.api.BridgeModbus;
import io.openems.edge.bridge.modbus.api.ModbusComponent;
import io.openems.edge.bridge.modbus.api.ModbusProtocol;
import io.openems.edge.bridge.modbus.api.element.DummyRegisterElement;
import io.openems.edge.bridge.modbus.api.element.FloatDoublewordElement;
import io.openems.edge.bridge.modbus.api.element.SignedWordElement;
import io.openems.edge.bridge.modbus.api.element.UnsignedDoublewordElement;
import io.openems.edge.bridge.modbus.api.element.UnsignedWordElement;
import io.openems.edge.bridge.modbus.api.task.FC16WriteRegistersTask;
import io.openems.edge.bridge.modbus.api.task.FC3ReadRegistersTask;
import io.openems.edge.bridge.modbus.api.task.Task.ExecuteState;
import io.openems.edge.common.channel.IntegerWriteChannel;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.sum.GridMode;
import io.openems.edge.common.sum.Sum;
import io.openems.edge.common.taskmanager.Priority;
import io.openems.edge.ess.api.HybridEss;
import io.openems.edge.ess.api.ManagedSymmetricEss;
import io.openems.edge.ess.api.SymmetricEss;
import io.openems.edge.ess.power.api.Power;
import io.openems.edge.kostal.plenticore.enums.ControlMode;
import io.openems.edge.timedata.api.Timedata;
import io.openems.edge.timedata.api.TimedataProvider;
import io.openems.edge.timedata.api.utils.CalculateEnergyFromPower;

@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "Kostal.Plenticore.Ess", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE)
@EventTopics({ //
		TOPIC_CYCLE_BEFORE_PROCESS_IMAGE, //
		TOPIC_CYCLE_BEFORE_CONTROLLERS //
})
@GenerateTargetsFromReferences("Modbus")
public class KostalManagedEssImpl extends AbstractOpenemsModbusComponent implements KostalManagedEss,
		ManagedSymmetricEss, HybridEss, SymmetricEss, ModbusComponent, TimedataProvider, EventHandler,
		OpenemsComponent {

	private static final Logger log = LoggerFactory.getLogger(KostalManagedEssImpl.class);

	@Reference
	private Power power;

	@Reference
	private Sum sum;

	/**
	 * Sets the Modbus bridge service reference. This method is used to reference
	 * the Modbus bridge component.
	 *
	 * @param modbus the Modbus bridge instance
	 */
	@Override
	@Reference(policy = STATIC, policyOption = GREEDY, cardinality = MANDATORY, //
			target = "(&(id=${config.modbus_id})(enabled=true))")
	protected void setModbus(BridgeModbus modbus) {
		super.setModbus(modbus);
	}

	private Config config;

	private Instant lastApplyPower = Instant.MIN;
	private Integer lastSetPower = null;

	@Reference(policy = DYNAMIC, policyOption = GREEDY, cardinality = OPTIONAL)
	private volatile Timedata timeData;

	/**
	 * True once the inverter has answered on the "Battery limitation" registers of
	 * documentation section 3.5. Detected once, see {@link #detectBatteryLimitation}.
	 */
	private volatile boolean batteryLimitation = false;

	private ControlMode controlMode;
	private int minsoc = 5;
	private int tolerance = 20;
	private int watchdog = 30;

	// is DC power for consistency
	private final CalculateEnergyFromPower calculateAcChargeEnergy = new CalculateEnergyFromPower(this,
			SymmetricEss.ChannelId.ACTIVE_CHARGE_ENERGY);
	private final CalculateEnergyFromPower calculateAcDischargeEnergy = new CalculateEnergyFromPower(this,
			SymmetricEss.ChannelId.ACTIVE_DISCHARGE_ENERGY);
	private final CalculateEnergyFromPower calculateDcChargeEnergy = new CalculateEnergyFromPower(this,
			HybridEss.ChannelId.DC_CHARGE_ENERGY);
	private final CalculateEnergyFromPower calculateDcDischargeEnergy = new CalculateEnergyFromPower(this,
			HybridEss.ChannelId.DC_DISCHARGE_ENERGY);

	/**
	 * Constructor for KostalManagedESSImpl. Initializes the component with default
	 * channels.
	 */
	public KostalManagedEssImpl() {
		super(OpenemsComponent.ChannelId.values(), ModbusComponent.ChannelId.values(), SymmetricEss.ChannelId.values(),
				HybridEss.ChannelId.values(), ManagedSymmetricEss.ChannelId.values(),
				KostalManagedEss.ChannelId.values());
	}

	/**
	 * Activates the component and initializes the configuration. This method is
	 * called when the component is activated.
	 *
	 * @param context the component context
	 * @param config  the configuration settings
	 */
	@Activate
	private void activate(ComponentContext context, Config config) {
		this.config = config;

		super.activate(context, config.id(), config.alias(), config.enabled(), config.modbusUnitId());

		setValue(this, SymmetricEss.ChannelId.GRID_MODE, GridMode.ON_GRID);
		this._setCapacity(config.capacity());
		this.controlMode = config.controlMode();
		this.minsoc = config.minsoc();
		this.watchdog = config.watchdog();
		this.tolerance = config.tolerance();
	}

	/**
	 * Deactivates the component. Resets internal states and references.
	 */
	@Override
	@Deactivate
	protected void deactivate() {
		super.deactivate();
	}

	/**
	 * Applies the desired active and reactive power to the system.
	 *
	 * @param activePower   the desired active power
	 * @param reactivePower the desired reactive power
	 * @throws OpenemsNamedException if there are issues applying power
	 */
	@Override
	public void applyPower(int activePower, int reactivePower) throws OpenemsNamedException {
		final var pvProduction = this.acPvProduction();

		// Independent of the control mode: hand the effective power limits to the
		// inverter, so its internal regulation stays inside them as well.
		this.applyBatteryLimitation(pvProduction);

		var diffBalancing = this.calculateDiffBalancing(activePower);
		this.channel(KostalManagedEss.ChannelId.SMART_MODE_NOT_WORKING_WITH_FILTER)
				.setNextValue(this.controlMode == ControlMode.SMART && this.power.isFilterEnabled());

		// managed or internal mode -> switch to max. self consumption automatic
		// (no writes to channel)
		if (this.isManaged() && this.controlMode != ControlMode.INTERNAL) {
			if (pvProduction == null) {
				// Without the PV share the AC set-point cannot be turned into a battery
				// set-point, and guessing would command the battery to the whole AC target.
				// Happens for the first Cycles after a start; writing nothing is what the
				// component did back then anyway.
				this.lastSetPower = null;
				this.lastApplyPower = Instant.MIN;
				return;
			}

			// Register 1034 takes a BATTERY set-point while activePower is the inverter's
			// AC power, so what is left for the battery is the difference. Same conversion
			// as GoodWe's ApplyPowerHandler.handleRemoteMode.
			final var batterySetPoint = activePower - pvProduction;
			this._setChargePowerWanted(batterySetPoint);

			// SMART mode: within the idle band there is no active set-point to apply.
			// Stop writing (and do not refresh at the watchdog) so the inverter's
			// control timeout expires and it returns to its internal self-consumption
			// regulation - instead of pinning the battery at 0 W. The
			// battery-management-mode register is read-only, so this fallback timeout
			// is the only way to hand control back to the inverter (see the KOSTAL
			// MODBUS-TCP documentation, section "External battery management").
			var releaseReason = this.controlMode == ControlMode.SMART
					? this.releaseReason(activePower, batterySetPoint, diffBalancing)
					: null;
			if (releaseReason != null) {
				if (this.lastSetPower != null) {
					this.logInfo(log, "Releasing battery to internal 'AUTO' mode: " + releaseReason);
				}
				// reset both, so re-engaging writes immediately instead of being skipped
				this.lastSetPower = null;
				this.lastApplyPower = Instant.MIN;
				return;
			}

			Instant now = Instant.now();
			int powerToWrite = batterySetPoint;

			// Apply idle zone: values within +/- tolerance around zero are set to 0W.
			// This prevents constant charge/discharge switching on small grid
			// fluctuations. REMOTE keeps full control and does not release to AUTO.
			if (Math.abs(batterySetPoint) < this.tolerance) {
				powerToWrite = 0;
			}

			// Refresh well before the inverter's control timeout elapses; refreshing only
			// at the boundary lets the inverter drop back into its internal mode for a
			// moment before every refresh.
			var refreshInterval = Math.max(1, this.watchdog / 2);
			var refreshDue = Duration.between(this.lastApplyPower, now).getSeconds() >= refreshInterval;

			// Skip only an unchanged set-point that is still fresh. Small changes are NOT
			// suppressed: with the Ess.Power PID filter active that would be a dead-band
			// between controller and actuator, and a dead-band in front of an integral
			// term winds up until it breaks through - a limit cycle. Damping is the job of
			// the filter, or of the inverter's power gradient.
			if (this.lastSetPower != null && powerToWrite == this.lastSetPower && !refreshDue) {
				log.debug("skipped - power unchanged at " + powerToWrite + "W");
				return;
			}

			// Kostal is fine by writing one register with signed value
			IntegerWriteChannel setActivePowerChannel = this.channel(KostalManagedEss.ChannelId.SET_ACTIVE_POWER);
			setActivePowerChannel.setNextWriteValue(powerToWrite);

			this.lastSetPower = powerToWrite;
			this.lastApplyPower = now;

			log.debug("--> batteryPowerWanted: " + powerToWrite + "W (AC set-point " + activePower + "W minus PV "
					+ pvProduction + "W)");
		} else {
			this.lastSetPower = null;
		}
	}

	/**
	 * Whether OpenEMS currently forbids charging or discharging outright.
	 *
	 * <p>
	 * A solved set-point of zero is ambiguous: it means either "nobody wants anything"
	 * or "somebody wants something and is not allowed to". Only the first may release
	 * the battery. A SoC floor - from the `minsoc` configuration or from
	 * Controller.Ess.LimitTotalDischarge - produces the second, and the inverter would
	 * not honour it, because it only bounds the solver.
	 *
	 * @return true while a hard zero is in place on either side
	 */
	private boolean hasHardLimit() {
		if (Integer.valueOf(0).equals(this.getAllowedChargePower().get())) {
			return true;
		}
		// The discharge bound is an AC bound and carries the PV, so it reaches zero only
		// at night. What matters is whether anything is left for the BATTERY.
		var pv = this.acPvProduction();
		var allowedDischarge = this.getAllowedDischargePower().get();
		return pv != null && allowedDischarge != null && allowedDischarge - pv <= 0;
	}

	/**
	 * Difference between the solved set-point and what plain balancing to zero would
	 * ask for. Published on {@link KostalManagedEss.ChannelId#DIFF_BALANCING}.
	 *
	 * <p>
	 * Assumes a target grid set-point of 0 W, as the GoodWe and SMA drivers do.
	 *
	 * @param activePower the solved Active-Power set-point
	 * @return the difference in [W], or null if a value was missing
	 */
	private Integer calculateDiffBalancing(int activePower) {
		var grid = this.sum.getGridActivePower().get();
		var ess = this.getActivePower().get();
		if (grid == null || ess == null) {
			this.channel(KostalManagedEss.ChannelId.DIFF_BALANCING).setNextValue(null);
			return null;
		}
		var diff = activePower - (grid + ess);
		this.channel(KostalManagedEss.ChannelId.DIFF_BALANCING).setNextValue(diff);
		return diff;
	}

	/**
	 * Decides whether SMART may hand the battery back to the inverter.
	 *
	 * <p>
	 * Unlike the GoodWe driver, a missing value does NOT release: there the fall-back
	 * is an explicit AUTO command that is undone immediately, while here it means
	 * waiting out the inverter's control timeout.
	 *
	 * @param activePower     the solved Active-Power set-point
	 * @param batterySetPoint the same after subtracting the PV, i.e. what the battery
	 *                        is asked to do
	 * @param diffBalancing   the value from {@link #calculateDiffBalancing}, may be
	 *                        null
	 * @return the reason to release, or null to keep control
	 */
	private String releaseReason(int activePower, int batterySetPoint, Integer diffBalancing) {
		if (Math.abs(batterySetPoint) < this.tolerance && !this.hasHardLimit()) {
			// nothing is being asked of the battery at all. Measured on the BATTERY
			// set-point, not on ActivePower - the latter carries the PV and is far from
			// zero on a sunny day while the battery does nothing.
			return "idle zone, |" + batterySetPoint + "W| < " + this.tolerance + "W";
		}
		if (diffBalancing == null) {
			return null;
		}
		if (Math.abs(diffBalancing) <= 1) {
			// the set-point is plain balancing to zero - exactly what the inverter's
			// internal regulation does on its own. Tolerance is 1 W for rounding only:
			// with no Ess.Power filter the set-point is computed from the same process
			// image, so the equality is exact by construction, and a wider band would
			// swallow a genuine small request.
			return "set-point matches plain balancing to zero";
		}
		if (this.batteryLimitation && activePower == this.power.getMinPower(this, ALL, ACTIVE)) {
			// the set-point deviates only because a charge cap binds - and that cap has
			// been handed to the inverter, so it will respect it while regulating itself
			return "at the charge limit, which the inverter enforces itself";
		}
		return null;
	}

	/**
	 * Debug-Log segment for the battery limitation, empty while it is not in use.
	 *
	 * @return the segment
	 */
	private String debugLogBatteryLimitation() {
		if (!this.batteryLimitation) {
			return "|Limitation:n/a";
		}
		return "|Limitation:" + this.channel(KostalManagedEss.ChannelId.BATTERY_CHARGE_POWER_LIMIT).value().asStringWithoutUnit()
				+ ".."
				+ this.channel(KostalManagedEss.ChannelId.BATTERY_DISCHARGE_POWER_LIMIT).value().asStringWithoutUnit()
				+ "|Fallback:"
				+ this.channel(KostalManagedEss.ChannelId.BATTERY_FALLBACK_CHARGE_POWER).value().asStringWithoutUnit()
				+ ".."
				+ this.channel(KostalManagedEss.ChannelId.BATTERY_FALLBACK_DISCHARGE_POWER).value()
						.asStringWithoutUnit()
				+ " after "
				+ this.channel(KostalManagedEss.ChannelId.BATTERY_FALLBACK_TIME).value().asStringWithoutUnit() + "s";
	}

	/**
	 * Writes the effective charge/discharge limits to the inverter (documentation
	 * section 3.5, registers 1280/1282).
	 *
	 * <p>
	 * In INTERNAL mode the inverter's own limits are written, so the mode keeps its
	 * promise not to influence the battery. In SMART and REMOTE the values are taken
	 * from the Power solver, not from a single controller: the
	 * same constraint channel is written by several controllers per Cycle, so only
	 * the solved extrema describe what OpenEMS actually permits. Section 3.5
	 * requires cyclic writes; if they stop, the inverter falls back to the values in
	 * registers 1284/1286 after the time in 1288.
	 *
	 * @param pvProduction the PV share of the AC power, may be null
	 */
	private void applyBatteryLimitation(Integer pvProduction) {
		if (!this.batteryLimitation || !this.isManaged()) {
			return;
		}
		// The discharge side gets the device limit, never a solved extremum: getMaxPower
		// is driven by "less or equals" constraints, and the dominant one -
		// GridOptimizedCharge's SellToGridLimit - is derived from the present grid flow
		// ("do not discharge more right now"). That says what OpenEMS should command, not
		// what the battery may do. A SoC floor is not pushed into the inverter either:
		// no other driver does that, and OpenEMS expresses it as a solver constraint
		// (Controller.Ess.LimitTotalDischarge). It is honoured by not releasing instead,
		// see releaseReason().
		final var dischargeLimit = magnitude(this.getMaxDischargePower().get());
		final Integer chargeLimit;
		if (this.controlMode == ControlMode.INTERNAL) {
			// INTERNAL means the inverter regulates on its own, so no OpenEMS constraint
			// is imposed. The device limit is written anyway, to clear a narrower limit
			// that another mode may have left behind - same as the SolarEdge driver does
			// in its internal mode.
			chargeLimit = magnitude(this.getMaxChargePower().get());
		} else {
			// getMinPower is the most negative allowed Active-Power, i.e. the maximum
			// charging OpenEMS permits. It is driven by "greater or equals" constraints,
			// e.g. GridOptimizedCharge's DelayCharge - a genuine "do not charge more than
			// this" that belongs in a limit register. It bounds the inverter's AC power,
			// so the PV has to come off it to get the battery's own limit.
			chargeLimit = pvProduction == null //
					? null //
					: Math.max(0, pvProduction - this.power.getMinPower(this, ALL, ACTIVE));
		}
		if (chargeLimit == null || dischargeLimit == null) {
			// device limits not read yet - write nothing rather than something wrong
			return;
		}
		try {
			this._setMaxChargePower(chargeLimit);
			this._setMaxDischargePower(dischargeLimit);
		} catch (OpenemsNamedException e) {
			this.logWarn(log, "Unable to apply battery limitation: " + e.getMessage());
		}
	}

	/**
	 * Absolute value of a nullable limit.
	 *
	 * @param value the value, may be null
	 * @return the absolute value, or null
	 */
	private static Integer magnitude(Integer value) {
		return value == null ? null : Math.abs(value);
	}

	/**
	 * Detects once whether the inverter supports the "Battery limitation" registers
	 * of documentation section 3.5 and, if so, extends the protocol by them.
	 *
	 * <p>
	 * The registers exist on PLENTICORE G3 from SW 03.05 only. Probing the register
	 * is a direct capability test and more robust than parsing the version string.
	 *
	 * @param protocol the {@link ModbusProtocol} to extend
	 */
	private void detectBatteryLimitation(ModbusProtocol protocol) {
		final var errors = new AtomicInteger(0);
		readElementOnce(FC3, protocol, (state, value) -> {
			if (state instanceof ExecuteState.Error) {
				// give up after a few attempts - the register is simply not there
				return errors.incrementAndGet() < 3;
			}
			return value == null;
		}, new FloatDoublewordElement(1280).wordOrder(LSWMSW)) //
				.thenAccept(value -> {
					this.batteryLimitation = value != null;
					this._setBatteryLimitationAvailable(this.batteryLimitation);
					if (!this.batteryLimitation) {
						this.logInfo(log, "Battery limitation (registers 1280..1288) is not supported");
						return;
					}
					this.logInfo(log, "Battery limitation (registers 1280..1288) is supported");
					protocol.addTask(new FC3ReadRegistersTask(1280, Priority.LOW, //
							m(KostalManagedEss.ChannelId.BATTERY_CHARGE_POWER_LIMIT,
									new FloatDoublewordElement(1280).wordOrder(LSWMSW)),
							m(KostalManagedEss.ChannelId.BATTERY_DISCHARGE_POWER_LIMIT,
									new FloatDoublewordElement(1282).wordOrder(LSWMSW)),
							m(KostalManagedEss.ChannelId.BATTERY_FALLBACK_CHARGE_POWER,
									new FloatDoublewordElement(1284).wordOrder(LSWMSW)),
							m(KostalManagedEss.ChannelId.BATTERY_FALLBACK_DISCHARGE_POWER,
									new FloatDoublewordElement(1286).wordOrder(LSWMSW)),
							// U32, not word-swapped: the byte order setting of the inverter
							// applies to float-formatted registers only (documentation Note 7)
							m(KostalManagedEss.ChannelId.BATTERY_FALLBACK_TIME,
									new UnsignedDoublewordElement(1288))));
					protocol.addTask(new FC16WriteRegistersTask(1280, //
							m(KostalManagedEss.ChannelId.SET_MAX_CHARGE_POWER,
									new FloatDoublewordElement(1280).wordOrder(LSWMSW)),
							m(KostalManagedEss.ChannelId.SET_MAX_DISCHARGE_POWER,
									new FloatDoublewordElement(1282).wordOrder(LSWMSW))));
				});
	}

	/**
	 * Defines the Modbus protocol for this component.
	 *
	 * @return the ModbusProtocol instance
	 */
	@Override
	protected ModbusProtocol defineModbusProtocol() {
		var protocol = new ModbusProtocol(this,
				new FC3ReadRegistersTask(56, Priority.LOW,
						m(KostalManagedEss.ChannelId.INVERTER_STATE,
								new UnsignedDoublewordElement(56).wordOrder(LSWMSW))),
				new FC3ReadRegistersTask(104, Priority.LOW,
						m(KostalManagedEss.ChannelId.ENERGY_MANAGER_MODE,
								new UnsignedDoublewordElement(104).wordOrder(LSWMSW))),
				new FC3ReadRegistersTask(152, Priority.HIGH,
						m(KostalManagedEss.ChannelId.FREQUENCY, new FloatDoublewordElement(152).wordOrder(LSWMSW)),
						new DummyRegisterElement(154, 157), //
						m(KostalManagedEss.ChannelId.GRID_VOLTAGE_L1,
								new FloatDoublewordElement(158).wordOrder(LSWMSW)),
						new DummyRegisterElement(160, 163), //
						m(KostalManagedEss.ChannelId.GRID_VOLTAGE_L2,
								new FloatDoublewordElement(164).wordOrder(LSWMSW)),
						new DummyRegisterElement(166, 169), //
						m(KostalManagedEss.ChannelId.GRID_VOLTAGE_L3,
								new FloatDoublewordElement(170).wordOrder(LSWMSW)),
						// As a HybridEss the ActivePower is the AC power of the WHOLE inverter -
						// PV and battery together - not the battery alone. Measured here rather
						// than derived as PV plus battery, see the note on acPvProduction().
						m(SymmetricEss.ChannelId.ACTIVE_POWER, new FloatDoublewordElement(172).wordOrder(LSWMSW)),
						m(SymmetricEss.ChannelId.REACTIVE_POWER, new FloatDoublewordElement(174).wordOrder(LSWMSW)),
						new DummyRegisterElement(176, 189), //
						m(KostalManagedEss.ChannelId.BATTERY_CURRENT,
								new FloatDoublewordElement(190).wordOrder(LSWMSW)),
						new DummyRegisterElement(192, 209), //
						m(SymmetricEss.ChannelId.SOC, new FloatDoublewordElement(210).wordOrder(LSWMSW)),
						new DummyRegisterElement(212, 213), //
						m(KostalManagedEss.ChannelId.BATTERY_TEMPERATURE,
								new FloatDoublewordElement(214).wordOrder(LSWMSW)),
						m(KostalManagedEss.ChannelId.BATTERY_VOLTAGE, new FloatDoublewordElement(216).wordOrder(LSWMSW),
								SCALE_FACTOR_3)),
				new FC3ReadRegistersTask(531, Priority.LOW,
						m(SymmetricEss.ChannelId.MAX_APPARENT_POWER, new UnsignedWordElement(531))),
				// Actual battery power on its own HIGH priority task. It feeds the Ess.Power
				// PID filter, the derived household consumption in _sum, and every controller
				// that computes PV surplus. On LOW priority it is refreshed only every n-th
				// Cycle (n = number of LOW tasks on the bridge), which puts dead time into the
				// PID's feedback path and makes the surplus calculation lag its own effect.
				new FC3ReadRegistersTask(582, Priority.HIGH,
						m(HybridEss.ChannelId.DC_DISCHARGE_POWER, new SignedWordElement(582))),
				new FC3ReadRegistersTask(1034, Priority.LOW,
						m(KostalManagedEss.ChannelId.CHARGE_POWER, new FloatDoublewordElement(1034).wordOrder(LSWMSW)),
						new DummyRegisterElement(1036, 1037), //
						m(KostalManagedEss.ChannelId.MAX_CHARGE_POWER,
								new FloatDoublewordElement(1038).wordOrder(LSWMSW)),
						m(KostalManagedEss.ChannelId.MAX_DISCHARGE_POWER,
								new FloatDoublewordElement(1040).wordOrder(LSWMSW))),

				new FC16WriteRegistersTask(1034, m(KostalManagedEss.ChannelId.SET_ACTIVE_POWER,
						new FloatDoublewordElement(1034).wordOrder(LSWMSW))));

		this.detectBatteryLimitation(protocol);
		return protocol;
	}

	/**
	 * Provides a debug log message summarizing the current state.
	 *
	 * @return the debug log message
	 */
	@Override
	public String debugLog() {
		return "SoC:" + this.getSoc().asString() //
				+ "|L:" + this.getActivePower().asString() //
				+ "|Allowed Charge Power:"
				+ this.channel(ManagedSymmetricEss.ChannelId.ALLOWED_CHARGE_POWER).value().asStringWithoutUnit()
				+ "|Allowed Discharge Power:"
				+ this.channel(ManagedSymmetricEss.ChannelId.ALLOWED_DISCHARGE_POWER).value().asStringWithoutUnit()
				+ "|MaxChargePower:"
				+ this.channel(KostalManagedEss.ChannelId.MAX_CHARGE_POWER).value().asStringWithoutUnit()
				+ "|MaxDischargePower:"
				+ this.channel(KostalManagedEss.ChannelId.MAX_DISCHARGE_POWER).value().asStringWithoutUnit()
				+ "|Battery:" + this.getDcDischargePower().asStringWithoutUnit() //
				+ "|Pv:" + this.acPvProduction() //
				+ "|ChargePower:" + this.channel(KostalManagedEss.ChannelId.CHARGE_POWER).value().asString() //
				+ "|Diff:" + this.channel(KostalManagedEss.ChannelId.DIFF_BALANCING).value().asStringWithoutUnit() //
				+ this.debugLogBatteryLimitation();
	}

	/**
	 * Gets the current power instance.
	 *
	 * @return the current Power instance
	 */
	@Override
	public Power getPower() {
		return this.power;
	}

	/**
	 * Gets the power precision for this component.
	 *
	 * @return the power precision
	 */
	@Override
	public int getPowerPrecision() {
		return 1;
	}

	/**
	 * Gets the timedata provider.
	 *
	 * @return the Timedata instance
	 */
	@Override
	public Timedata getTimedata() {
		return this.timeData;
	}

	/**
	 * Handles system events and reacts to specific topics.
	 *
	 * @param event the event to handle
	 */
	@Override
	public void handleEvent(Event event) {
		if (!this.isEnabled()) {
			return;
		}
		switch (event.getTopic()) {
		case TOPIC_CYCLE_BEFORE_CONTROLLERS:
			log.debug("== update values topic cycle before controllers ==");
			this.setLimits();
			break;
		case TOPIC_CYCLE_BEFORE_PROCESS_IMAGE:
			log.debug("== update values topic cycle before process image ==");
			this.calculateEnergy();
			break;
		}
	}

	/**
	 * Checks if this component is in managed mode.
	 *
	 * @return true if in managed mode; false otherwise
	 */
	@Override
	public boolean isManaged() {
		return (this.config.enabled() && !this.config.readOnlyMode());
	}

	/**
	 * Sets power limits based on system state and configuration.
	 */
	private void setLimits() {
		// Both bounds constrain Active-Power, which as a HybridEss is the inverter's AC
		// power. The battery's discharge capability therefore comes ON TOP of the PV
		// that is already flowing. The charge side is deliberately NOT shifted, exactly
		// as in GoodWe's AllowedChargeDischargeHandler: getSurplusPower() reads the
		// battery's own charge limit back out of this channel, and shifting it here
		// would count the PV twice.
		var pv = this.acPvProduction();
		int pvOrZero = pv == null ? 0 : pv;
		int maxDischargePower = getMaxDischargePower().orElse(0);
		int maxChargePower = getMaxChargePower().orElse(0) * -1;

		setValue(this, ManagedSymmetricEss.ChannelId.ALLOWED_DISCHARGE_POWER, maxDischargePower + pvOrZero);
		setValue(this, ManagedSymmetricEss.ChannelId.ALLOWED_CHARGE_POWER, maxChargePower);

		var soc = getSoc().orElse(null);
		if (soc != null) {
			if (soc == 100) {
				setValue(this, ManagedSymmetricEss.ChannelId.ALLOWED_CHARGE_POWER, 0);
			}
			if (soc <= this.minsoc) {
				// The BATTERY must not discharge - the PV may still be exported, so the AC
				// bound is the PV and not zero.
				setValue(this, ManagedSymmetricEss.ChannelId.ALLOWED_DISCHARGE_POWER, pvOrZero);
			}
		}
		log.debug("--> set limits: " + maxDischargePower + " / " + maxChargePower);
	}

	private void calculateEnergy() {
		// Calculate AC Energy
		var activePower = this.getActivePowerChannel().getNextValue().get();
		if (activePower == null) {
			// Not available
			this.calculateAcChargeEnergy.update(null);
			this.calculateAcDischargeEnergy.update(null);
		} else {
			log.debug("valid active power for calculation of energy");
			if (activePower > 0) {
				// Discharge
				this.calculateAcChargeEnergy.update(0);
				this.calculateAcDischargeEnergy.update(activePower);
			} else {
				// Charge
				this.calculateAcChargeEnergy.update(activePower * -1);
				this.calculateAcDischargeEnergy.update(0);
			}
		}

		// Calculate DC Energy. The AC pair above follows the inverter's total AC power -
		// that is what makes the _sum consumption energy balance once the PV production
		// has moved to the DC leg - while the battery's own throughput lives here.
		var dcDischargePower = this.getDcDischargePowerChannel().getNextValue().get();
		if (dcDischargePower == null) {
			this.calculateDcChargeEnergy.update(null);
			this.calculateDcDischargeEnergy.update(null);
		} else if (dcDischargePower > 0) {
			this.calculateDcChargeEnergy.update(0);
			this.calculateDcDischargeEnergy.update(dcDischargePower);
		} else {
			this.calculateDcChargeEnergy.update(dcDischargePower * -1);
			this.calculateDcDischargeEnergy.update(0);
		}
	}

	/**
	 * The PV share of the inverter's AC power, i.e. what the AC side would deliver
	 * with the battery idle.
	 *
	 * <p>
	 * Taken as Active-Power minus DC-Discharge-Power rather than from the DC
	 * registers or from the chargers: it needs no further register, it works whether
	 * or not chargers are configured, and it is already an AC figure - so subtracting
	 * it from an AC set-point lands exactly on the battery instead of missing by the
	 * conversion loss. Same expression GoodWe uses in its
	 * AllowedChargeDischargeHandler.
	 *
	 * @return the PV share in [W], never negative, or null while a value is missing
	 */
	private Integer acPvProduction() {
		var activePower = this.getActivePower().get();
		var dcDischargePower = this.getDcDischargePower().get();
		if (activePower == null || dcDischargePower == null) {
			return null;
		}
		return Math.max(0, activePower - dcDischargePower);
	}

	@Override
	public Integer getSurplusPower() {
		var pv = this.acPvProduction();
		if (pv == null || pv < 100) {
			return null;
		}
		// AllowedChargePower is negative by convention and, see setLimits(), is the
		// battery's own limit - so this sum is the PV the battery cannot absorb.
		var surplus = pv + this.getAllowedChargePower().orElse(0);
		if (surplus < 0) {
			return null;
		}
		return surplus;
	}
}
