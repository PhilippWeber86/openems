package io.openems.edge.controller.heatpump.shi;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

@ObjectClassDefinition(//
		name = "Controller SHI Heat Pump", //
		description = "Runs a SHI heat pump on PV surplus. The heat pump is expected to be connected "
				+ "grid-side of the grid meter, so the battery never discharges for it by default; "
				+ "battery support is actively forced only while the forecast guarantees that household "
				+ "consumption stays covered.")
@interface Config {

	@AttributeDefinition(name = "Component-ID", description = "Unique ID of this Component")
	String id() default "ctrlShiHeatPump0";

	@AttributeDefinition(name = "Alias", description = "Human-readable name of this Component; defaults to Component-ID")
	String alias() default "";

	@AttributeDefinition(name = "Is enabled?", description = "Is this Component enabled?")
	boolean enabled() default true;

	@AttributeDefinition(name = "Heat pump ID", description = "ID of SHI heat pump device.")
	String heatPump_id() default "heatPump0";

	@AttributeDefinition(name = "Ess ID", description = "ID of the managed energy storage system.")
	String ess_id() default "ess0";

	@AttributeDefinition(name = "Heat pump position", description = "Where the heat pump is connected relative to the grid meter: BEHIND_GRID_METER (standard, grid meter at the grid connection point; the Controller limits ESS discharge so the battery does not serve the heat pump beyond the allowed support) or GRID_SIDE_OF_GRID_METER (heat pump upstream of the grid meter; the Controller forces battery export towards the heat pump while support is allowed).")
	HeatPumpPosition heatPumpPosition() default HeatPumpPosition.BEHIND_GRID_METER;

	@AttributeDefinition(name = "Minimum surplus power for elevated mode", description = "Power in W that must be covered by PV surplus plus allowed ESS support before elevated mode starts.")
	int minimumSurplusPowerForElevatedMode() default 2500;

	@AttributeDefinition(name = "Heating setpoint [°C]", description = "Return temperature setpoint in °C (e.g. 55.0) while elevated mode is active.")
	double heatingSetpoint() default 55.0;

	@AttributeDefinition(name = "Hot water setpoint [°C]", description = "Hot-water temperature setpoint in °C (e.g. 55.0) while elevated mode is active.")
	double hotWaterSetpoint() default 55.0;

	@AttributeDefinition(name = "ESS support enabled", description = "Allows forced battery export towards the heat pump while the night reserve for the household is guaranteed - both to bridge missing PV surplus in elevated mode and to cover regular heat-pump runs (e.g. hot water in the evening). Requires this Controller to be scheduled before the Balancing Controller.")
	boolean essSupportEnabled() default true;

	@AttributeDefinition(name = "Minimum SoC", description = "Battery SoC in % that is never used for the heat pump.")
	int minSoc() default 15;

	@AttributeDefinition(name = "Night reserve buffer", description = "Percentage applied to the forecasted household deficit until the next PV surplus (>100 adds a safety margin). The resulting energy is reserved in the battery for the household.")
	int nightReserveBuffer() default 120;

	@AttributeDefinition(name = "ESS support duration", description = "Assumed duration in minutes for battery-supported heat-pump operation; converts spare battery energy into allowed support power.")
	int essSupportDurationMinutes() default 60;

	@AttributeDefinition(name = "Minimum switching time", description = "Lower bound in seconds between elevated mode changes. The effective hysteresis is the maximum of this value and the compressor cycle limits reported by the heat pump (minimum runtime while elevated, restart lock after leaving).")
	int minimumSwitchingTime() default 300;

	@AttributeDefinition(name = "Boost confirmation time", description = "Elevated mode starts only after its entry conditions were fulfilled continuously for this many seconds. Filters short surplus spikes that would otherwise trigger a committed compressor cycle.")
	int boostConfirmationSeconds() default 240;

	@AttributeDefinition(name = "Forecast veto enabled", description = "Blocks elevated-mode entry if the prediction does not show enough surplus for the duration of a compressor cycle. Only useful with a weather-based production predictor; with simple persistence predictors this may wrongly block sunny days.")
	boolean forecastVetoEnabled() default false;

	@AttributeDefinition(name = "Run extension enabled", description = "Extends a natural hot-water run of the heat pump to the elevated hot-water setpoint while PV surplus plus allowed battery support fully cover the heat-pump power - saves compressor starts by topping up the storage in an already running cycle.")
	boolean runExtensionEnabled() default true;

	@AttributeDefinition(name = "Run extension minimum temperature delta [K]", description = "A natural run is only extended if the elevated hot-water setpoint exceeds the heat pump's own setpoint by at least this delta in K (e.g. 3.0). Prevents tiny extensions whose benefit is eaten by standing losses.")
	double extensionMinTemperatureDelta() default 3.0;

	@AttributeDefinition(name = "Heat pump target filter", description = "Auto-generated by heat pump ID.")
	String heatPump_target() default "(enabled=true)";

	String webconsole_configurationFactory_nameHint() default "Controller SHI Heat Pump [{id}]";
}
