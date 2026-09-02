package io.openems.edge.kostal.plenticore.ess;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

import io.openems.edge.kostal.plenticore.enums.ControlMode;

@ObjectClassDefinition(//
		name = "KOSTAL Plenticore ESS", //
		description = "Implements the Kostal Plenticore hybrid energy storage system (battery).")
@interface Config {
	@AttributeDefinition(name = "Component-ID", description = "Unique ID of this Component")
	String id() default "ess0";

	@AttributeDefinition(name = "Alias", description = "Human-readable name of this Component; defaults to Component-ID")
	String alias() default "";

	@AttributeDefinition(name = "Is enabled?", description = "Is this Component enabled?")
	boolean enabled() default true;

	@AttributeDefinition(name = "Read-Only mode", description = "In Read-Only mode no set-power-commands are sent to the inverter")
	boolean readOnlyMode() default true;

	@AttributeDefinition(name = "Control mode", description = "Sets the Control mode")
	ControlMode controlMode() default ControlMode.INTERNAL;

	@AttributeDefinition(name = "Minimum Battery-Soc", description = "The minimum battery state of charge.")
	int minsoc() default 5;

	@AttributeDefinition(name = "Watchdog", description = "The control timeout [s] configured at the inverter to return into internal operation mode. Set-points are refreshed at half this interval.")
	int watchdog() default 30;

	@AttributeDefinition(name = "Tolerance", description = "Idle zone in watts around zero. In SMART mode a request inside this zone releases the battery to the internal operation mode of the inverter; in REMOTE mode it is written as 0 W.")
	int tolerance() default 50;

	@AttributeDefinition(name = "Use battery limitation", description = "Write the effective charge/discharge limits to the inverter (MODBUS-TCP documentation section 3.5, registers 1280/1282). Requires PLENTICORE G3 from SW 03.05; availability is detected automatically. While disabled the registers are only read.")
	boolean useBatteryLimitation() default false;

	@AttributeDefinition(name = "Modbus-ID", description = "ID of Modbus bridge.")
	String modbus_id() default "modbus0";

	@AttributeDefinition(name = "Debug Mode", description = "Activates the debug mode")
	boolean debugMode() default false;

	@AttributeDefinition(name = "Modbus Unit-ID", description = "The Unit-ID of the Modbus device.")
	int modbusUnitId() default 71;

	@AttributeDefinition(name = "Capacity", description = "Capacity of the battery in [Wh]")
	int capacity() default 10_000;

	String webconsole_configurationFactory_nameHint() default "KOSTAL Plenticore ESS [{id}]";
}
