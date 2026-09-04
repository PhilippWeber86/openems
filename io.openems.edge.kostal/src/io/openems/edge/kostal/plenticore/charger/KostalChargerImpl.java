package io.openems.edge.kostal.plenticore.charger;

import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_3;
import static io.openems.edge.bridge.modbus.api.element.WordOrder.LSWMSW;
import static org.osgi.service.component.annotations.ReferenceCardinality.MANDATORY;
import static org.osgi.service.component.annotations.ReferencePolicy.STATIC;
import static org.osgi.service.component.annotations.ReferencePolicyOption.GREEDY;

import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.Designate;

import io.openems.common.channel.AccessMode;
import io.openems.common.referencetarget.GenerateTargetsFromReferences;
import io.openems.edge.bridge.modbus.api.AbstractOpenemsModbusComponent;
import io.openems.edge.bridge.modbus.api.BridgeModbus;
import io.openems.edge.bridge.modbus.api.ModbusComponent;
import io.openems.edge.bridge.modbus.api.ModbusProtocol;
import io.openems.edge.bridge.modbus.api.element.DummyRegisterElement;
import io.openems.edge.bridge.modbus.api.element.FloatDoublewordElement;
import io.openems.edge.bridge.modbus.api.task.FC3ReadRegistersTask;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.modbusslave.ModbusSlave;
import io.openems.edge.common.modbusslave.ModbusSlaveNatureTable;
import io.openems.edge.common.modbusslave.ModbusSlaveTable;
import io.openems.edge.common.taskmanager.Priority;
import io.openems.edge.ess.dccharger.api.EssDcCharger;

/**
 * One PV input of a KOSTAL PLENTICORE, as an {@link EssDcCharger}.
 *
 * <p>
 * The battery of a PLENTICORE sits on the DC side, so its PV production never
 * appears on the AC meter: it is DC production, and OpenEMS models exactly that
 * as an {@link EssDcCharger} attached to a
 * {@link io.openems.edge.ess.api.HybridEss}. Configure one instance per string;
 * their sum is what register 1066 reports as total DC power.
 *
 * <p>
 * Unlike the GoodWe chargers this component reads the inverter itself instead of
 * listening to channels of the ESS, because the PLENTICORE keeps the per-string
 * registers in a separate address range that the ESS has no reason to poll.
 */
@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "Kostal.Plenticore.Charger", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE //
)
@GenerateTargetsFromReferences("Modbus")
public class KostalChargerImpl extends AbstractOpenemsModbusComponent
		implements KostalCharger, EssDcCharger, ModbusComponent, OpenemsComponent, ModbusSlave {

	private PvPort pvPort;

	@Override
	@Reference(policy = STATIC, policyOption = GREEDY, cardinality = MANDATORY, //
			target = "(&(id=${config.modbus_id})(enabled=true))")
	protected void setModbus(BridgeModbus modbus) {
		super.setModbus(modbus);
	}

	public KostalChargerImpl() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				ModbusComponent.ChannelId.values(), //
				EssDcCharger.ChannelId.values(), //
				KostalCharger.ChannelId.values() //
		);
	}

	@Activate
	private void activate(ComponentContext context, Config config) {
		this.pvPort = config.pvPort();
		super.activate(context, config.id(), config.alias(), config.enabled(), config.modbusUnitId());
	}

	@Override
	@Deactivate
	protected void deactivate() {
		super.deactivate();
	}

	@Override
	protected ModbusProtocol defineModbusProtocol() {
		final var base = this.pvPort.baseAddress;
		return new ModbusProtocol(this, //
				// Current, power and voltage of this input. Ten registers with an
				// undocumented gap in the middle; the layout is identical for all inputs.
				new FC3ReadRegistersTask(base, Priority.HIGH, //
						m(EssDcCharger.ChannelId.CURRENT, new FloatDoublewordElement(base).wordOrder(LSWMSW),
								SCALE_FACTOR_3), //
						m(EssDcCharger.ChannelId.ACTUAL_POWER,
								new FloatDoublewordElement(base + 2).wordOrder(LSWMSW)), //
						new DummyRegisterElement(base + 4, base + 7), //
						m(EssDcCharger.ChannelId.VOLTAGE, new FloatDoublewordElement(base + 8).wordOrder(LSWMSW),
								SCALE_FACTOR_3)), //

				// The inverter keeps a lifetime counter per input, so the energy is taken
				// from the device instead of being integrated from the power. That also
				// keeps whatever was produced while OpenEMS was not running.
				new FC3ReadRegistersTask(this.pvPort.energyAddress, Priority.LOW, //
						m(EssDcCharger.ChannelId.ACTUAL_ENERGY,
								new FloatDoublewordElement(this.pvPort.energyAddress).wordOrder(LSWMSW))));
	}

	@Override
	public String debugLog() {
		return "L:" + this.getActualPower().asString();
	}

	@Override
	public ModbusSlaveTable getModbusSlaveTable(AccessMode accessMode) {
		return new ModbusSlaveTable(//
				OpenemsComponent.getModbusSlaveNatureTable(accessMode), //
				EssDcCharger.getModbusSlaveNatureTable(accessMode), //
				ModbusSlaveNatureTable.of(KostalCharger.class, accessMode, 100) //
						.build());
	}
}
