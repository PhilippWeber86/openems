package io.openems.edge.heat.shi;

import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.MULTIPLY;

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
import io.openems.common.types.MeterType;
import io.openems.edge.bridge.modbus.api.AbstractOpenemsModbusComponent;
import io.openems.edge.bridge.modbus.api.BridgeModbus;
import io.openems.edge.bridge.modbus.api.ChannelMetaInfoReadAndWrite;
import io.openems.edge.bridge.modbus.api.ElementToChannelConverter;
import io.openems.edge.bridge.modbus.api.ModbusComponent;
import io.openems.edge.bridge.modbus.api.ModbusProtocol;
import io.openems.edge.bridge.modbus.api.element.SignedWordElement;
import io.openems.edge.bridge.modbus.api.element.UnsignedWordElement;
import io.openems.edge.bridge.modbus.api.task.FC3ReadRegistersTask;
import io.openems.edge.bridge.modbus.api.task.FC4ReadInputRegistersTask;
import io.openems.edge.bridge.modbus.api.task.FC6WriteRegisterTask;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.taskmanager.Priority;
import io.openems.edge.meter.api.ElectricityMeter;

@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "Heat.Shi.HeatPump", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE)
public class HeatShiHeatPumpImpl extends AbstractOpenemsModbusComponent
		implements HeatShiHeatPump, ElectricityMeter, ModbusComponent, OpenemsComponent {

	/**
	 * Raw value 32767 marks a data point that is not configured in the heat pump
	 * (see SHI documentation); other values are 0.1 kW steps.
	 */
	private static final ElementToChannelConverter NOT_CONFIGURED_ELSE_MULTIPLY_100 = new ElementToChannelConverter(
			value -> {
				if (value instanceof Integer i) {
					return i == Short.MAX_VALUE ? null : i * 100;
				}
				return null;
			});

	/**
	 * Raw value 32767 marks a data point that is not configured in the heat pump
	 * (see SHI documentation); other values are passed through unchanged.
	 */
	private static final ElementToChannelConverter NOT_CONFIGURED_ELSE_DIRECT = new ElementToChannelConverter(
			value -> {
				if (value instanceof Integer i) {
					return i == Short.MAX_VALUE ? null : i;
				}
				return null;
			});

	private Config config;

	@Reference
	private ConfigurationAdmin cm;

	@Override
	@Reference(policy = ReferencePolicy.STATIC, policyOption = ReferencePolicyOption.GREEDY, cardinality = ReferenceCardinality.MANDATORY)
	protected void setModbus(BridgeModbus modbus) {
		super.setModbus(modbus);
	}

	public HeatShiHeatPumpImpl() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				ModbusComponent.ChannelId.values(), //
				ElectricityMeter.ChannelId.values(), //
				HeatShiHeatPump.ChannelId.values() //
		);
	}

	@Activate
	private void activate(ComponentContext context, Config config) throws OpenemsNamedException {
		this.config = config;
		if (super.activate(context, config.id(), config.alias(), config.enabled(), config.modbusUnitId(), this.cm,
				"Modbus", config.modbus_id())) {
			return;
		}
		this._setReadOnlyMode(config.readOnly());
	}

	@Modified
	private void modified(ComponentContext context, Config config) throws OpenemsNamedException {
		this.config = config;
		if (super.modified(context, config.id(), config.alias(), config.enabled(), config.modbusUnitId(), this.cm,
				"Modbus", config.modbus_id())) {
			return;
		}
		this._setReadOnlyMode(config.readOnly());
	}

	@Override
	@Deactivate
	protected void deactivate() {
		super.deactivate();
	}

	@Override
	protected ModbusProtocol defineModbusProtocol() {
		// Note: the SHI Modbus slave rejects block reads that span undocumented
		// addresses (e.g. HR10004, IR10001) with "Illegal Data Address", so every
		// task must cover documented registers only
		var protocol = new ModbusProtocol(this, //
				new FC3ReadRegistersTask(10000, Priority.HIGH, //
						m(HeatShiHeatPump.ChannelId.HEATING_MODE, new UnsignedWordElement(10000),
								new ChannelMetaInfoReadAndWrite(10000, 10000)), //
						m(HeatShiHeatPump.ChannelId.HEATING_SETPOINT, new UnsignedWordElement(10001),
								new ChannelMetaInfoReadAndWrite(10001, 10001)), //
						m(HeatShiHeatPump.ChannelId.HEATING_OFFSET, new SignedWordElement(10002),
								new ChannelMetaInfoReadAndWrite(10002, 10002))), //
				new FC3ReadRegistersTask(10005, Priority.HIGH, //
						m(HeatShiHeatPump.ChannelId.HOT_WATER_MODE, new UnsignedWordElement(10005),
								new ChannelMetaInfoReadAndWrite(10005, 10005)), //
						m(HeatShiHeatPump.ChannelId.HOT_WATER_SETPOINT, new UnsignedWordElement(10006),
								new ChannelMetaInfoReadAndWrite(10006, 10006)), //
						m(HeatShiHeatPump.ChannelId.HOT_WATER_OFFSET, new SignedWordElement(10007),
								new ChannelMetaInfoReadAndWrite(10007, 10007))), //
				new FC3ReadRegistersTask(10040, Priority.HIGH, //
						m(HeatShiHeatPump.ChannelId.LPC_MODE, new UnsignedWordElement(10040),
								new ChannelMetaInfoReadAndWrite(10040, 10040)), //
						m(HeatShiHeatPump.ChannelId.PC_LIMIT, new UnsignedWordElement(10041), MULTIPLY(100.0),
								new ChannelMetaInfoReadAndWrite(10041, 10041))), //
				new FC3ReadRegistersTask(10070, Priority.LOW, //
						m(HeatShiHeatPump.ChannelId.CIRCULATION, new UnsignedWordElement(10070),
								NOT_CONFIGURED_ELSE_DIRECT, new ChannelMetaInfoReadAndWrite(10070, 10070)), //
						m(HeatShiHeatPump.ChannelId.EXTRA_HOT_WATER, new UnsignedWordElement(10071),
								NOT_CONFIGURED_ELSE_DIRECT, new ChannelMetaInfoReadAndWrite(10071, 10071))), //
				new FC4ReadInputRegistersTask(10000, Priority.HIGH, //
						m(HeatShiHeatPump.ChannelId.HEAT_PUMP_STATUS, new UnsignedWordElement(10000))), //
				new FC4ReadInputRegistersTask(10002, Priority.HIGH, //
						m(HeatShiHeatPump.ChannelId.OPERATING_MODE_STATUS, new UnsignedWordElement(10002)), //
						m(HeatShiHeatPump.ChannelId.HEATING_STATUS, new UnsignedWordElement(10003),
								NOT_CONFIGURED_ELSE_DIRECT), //
						m(HeatShiHeatPump.ChannelId.HOT_WATER_STATUS, new UnsignedWordElement(10004),
								NOT_CONFIGURED_ELSE_DIRECT)), //
				new FC4ReadInputRegistersTask(10120, Priority.HIGH, //
						m(HeatShiHeatPump.ChannelId.HOT_WATER_TEMPERATURE, new SignedWordElement(10120),
								NOT_CONFIGURED_ELSE_DIRECT), //
						m(HeatShiHeatPump.ChannelId.HOT_WATER_ACTIVE_SETPOINT, new UnsignedWordElement(10121),
								NOT_CONFIGURED_ELSE_DIRECT)), //
				new FC4ReadInputRegistersTask(10203, Priority.LOW, //
						m(HeatShiHeatPump.ChannelId.MIN_STANDSTILL_TIME, new UnsignedWordElement(10203),
								NOT_CONFIGURED_ELSE_DIRECT), //
						m(HeatShiHeatPump.ChannelId.MIN_RUNTIME, new UnsignedWordElement(10204),
								NOT_CONFIGURED_ELSE_DIRECT)), //
				new FC4ReadInputRegistersTask(10301, Priority.HIGH, //
						m(ElectricityMeter.ChannelId.ACTIVE_POWER, new UnsignedWordElement(10301), MULTIPLY(100.0)), //
						m(HeatShiHeatPump.ChannelId.MIN_PREDICTED_ACTIVE_POWER, new UnsignedWordElement(10302),
								NOT_CONFIGURED_ELSE_MULTIPLY_100)) //
		);
		if (!this.config.readOnly()) {
			protocol.addTask(new FC6WriteRegisterTask(10000, //
					m(HeatShiHeatPump.ChannelId.HEATING_MODE, new UnsignedWordElement(10000),
							new ChannelMetaInfoReadAndWrite(10000, 10000))));
			protocol.addTask(new FC6WriteRegisterTask(10001, //
					m(HeatShiHeatPump.ChannelId.HEATING_SETPOINT, new UnsignedWordElement(10001),
							new ChannelMetaInfoReadAndWrite(10001, 10001))));
			protocol.addTask(new FC6WriteRegisterTask(10002, //
					m(HeatShiHeatPump.ChannelId.HEATING_OFFSET, new SignedWordElement(10002),
							new ChannelMetaInfoReadAndWrite(10002, 10002))));
			protocol.addTask(new FC6WriteRegisterTask(10005, //
					m(HeatShiHeatPump.ChannelId.HOT_WATER_MODE, new UnsignedWordElement(10005),
							new ChannelMetaInfoReadAndWrite(10005, 10005))));
			protocol.addTask(new FC6WriteRegisterTask(10006, //
					m(HeatShiHeatPump.ChannelId.HOT_WATER_SETPOINT, new UnsignedWordElement(10006),
							new ChannelMetaInfoReadAndWrite(10006, 10006))));
			protocol.addTask(new FC6WriteRegisterTask(10007, //
					m(HeatShiHeatPump.ChannelId.HOT_WATER_OFFSET, new SignedWordElement(10007),
							new ChannelMetaInfoReadAndWrite(10007, 10007))));
			protocol.addTask(new FC6WriteRegisterTask(10070, //
					m(HeatShiHeatPump.ChannelId.CIRCULATION, new UnsignedWordElement(10070),
							new ChannelMetaInfoReadAndWrite(10070, 10070))));
			protocol.addTask(new FC6WriteRegisterTask(10071, //
					m(HeatShiHeatPump.ChannelId.EXTRA_HOT_WATER, new UnsignedWordElement(10071),
							new ChannelMetaInfoReadAndWrite(10071, 10071))));
			protocol.addTask(new FC6WriteRegisterTask(10040, //
					m(HeatShiHeatPump.ChannelId.LPC_MODE, new UnsignedWordElement(10040),
							new ChannelMetaInfoReadAndWrite(10040, 10040))));
			protocol.addTask(new FC6WriteRegisterTask(10041, //
					m(HeatShiHeatPump.ChannelId.PC_LIMIT, new UnsignedWordElement(10041), MULTIPLY(100.0),
							new ChannelMetaInfoReadAndWrite(10041, 10041))));
		}
		return protocol;
	}

	@Override
	public String debugLog() {
		return "P=" + this.getActivePower().asOptional().orElse(null) //
				+ "W|H=" + this.getHeatingStatus().asOptional().orElse(null) //
				+ "|WW=" + this.getHotWaterStatus().asOptional().orElse(null) //
				+ "|RO=" + this.config.readOnly();
	}

	@Override
	public MeterType getMeterType() {
		return this.config.type();
	}
}
