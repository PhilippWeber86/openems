package io.openems.edge.kostal.plenticore.charger;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

@ObjectClassDefinition(name = "KOSTAL Plenticore Charger", //
		description = "Implements one PV input of the Kostal Plenticore hybrid inverter. "
				+ "Configure one instance per connected string; the DC power of all instances "
				+ "together is what the inverter reports as its total PV production.")
@interface Config {
	@AttributeDefinition(name = "Component-ID", description = "Unique ID of this Component")
	String id() default "charger0";

	@AttributeDefinition(name = "Alias", description = "Human-readable name of this Component; defaults to Component-ID")
	String alias() default "";

	@AttributeDefinition(name = "Is enabled?", description = "Is this Component enabled?")
	boolean enabled() default true;

	@AttributeDefinition(name = "PV input", description = "The PV input (MPP tracker) this charger represents")
	PvPort pvPort() default PvPort.PV_1;

	@AttributeDefinition(name = "Modbus-ID", description = "ID of Modbus bridge.")
	String modbus_id() default "modbus0";

	@AttributeDefinition(name = "Modbus Unit-ID", description = "The Unit-ID of the Modbus device. ")
	int modbusUnitId() default 71;

	String webconsole_configurationFactory_nameHint() default "KOSTAL Plenticore Charger [{id}]";
}
