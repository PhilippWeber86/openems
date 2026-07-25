package io.openems.edge.controller.heatpump.shi;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

@ObjectClassDefinition(//
		name = "Controller SHI Heat Pump", //
		description = "Runs a SHI heat pump preferably on PV surplus while protecting the battery for the "
				+ "household: battery energy may only serve the heat pump as long as the forecast guarantees "
				+ "that household consumption stays covered (night reserve). Supports both wiring topologies "
				+ "via the 'Heat pump position' setting, and extends natural hot-water runs to save "
				+ "compressor starts.")
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

	@AttributeDefinition(name = "Minimum surplus power for elevated mode", description = "Power in W that the PV surplus ALONE must reach before elevated mode starts. The battery does not bridge a weak surplus over this threshold; it only supports the heat pump during the run.")
	int minimumSurplusPowerForElevatedMode() default 2500;

	@AttributeDefinition(name = "Heating setpoint [°C]", description = "Return temperature setpoint in °C (e.g. 55.0) while elevated mode is active.")
	double heatingSetpoint() default 55.0;

	@AttributeDefinition(name = "Hot water setpoint [°C]", description = "Hot-water temperature setpoint in °C (e.g. 55.0) applied both in elevated mode and when a natural hot-water run is extended.")
	double hotWaterSetpoint() default 55.0;

	@AttributeDefinition(name = "ESS support enabled", description = "Allows the battery to support the heat pump while the night reserve for the household is guaranteed - during a PV-surplus boost (riding out clouds) and for regular heat-pump runs (e.g. hot water in the evening). This only controls the battery support: with it disabled the heat pump still runs on PV surplus (elevated mode) on its own. Requires this Controller to be scheduled before the Balancing Controller.")
	boolean essSupportEnabled() default true;

	@AttributeDefinition(name = "Battery support mode", description = "How aggressively free battery energy drives the heat pump. OFFENSIVE: the boost and run extension are allowed up to PV surplus plus deliverable battery power, so spare battery energy is actively turned into heat. CLOUD_BUFFER: the boost and run extension follow the PV surplus alone, and the battery only cushions short surplus dips reactively. In both modes a heat pump running on its own is still paid from the battery (passive support) as long as the night reserve holds.")
	BatterySupportMode batterySupportMode() default BatterySupportMode.OFFENSIVE;

	@AttributeDefinition(name = "Night reserve mode", description = "How the household night reserve is calculated. MAX_DEFICIT: the largest cumulative deficit over the horizon (safe, but conservative at low morning SoC because it ignores the daytime PV recharge). SOC_TRAJECTORY: a forward battery-SoC simulation that credits the daytime recharge, freeing more energy on a sunny forecast - recommended only with a weather-based production predictor.")
	NightReserveMode nightReserveMode() default NightReserveMode.MAX_DEFICIT;

	@AttributeDefinition(name = "Minimum SoC", description = "Battery SoC in % that is never used for the heat pump.")
	int minSoc() default 15;

	@AttributeDefinition(name = "Night reserve buffer", description = "Percentage applied to the forecasted household deficit until the next PV surplus (>100 adds a safety margin). The resulting energy is reserved in the battery for the household.")
	int nightReserveBuffer() default 120;

	@AttributeDefinition(name = "Maximum battery support power", description = "Optional upper limit in W for the battery power used to support the heat pump. 0 = no limit (use the full deliverable ESS power). Only needed if the heat pump should deliberately get less than the technically possible ESS power.")
	int maxBatterySupportPower() default 0;

	@AttributeDefinition(name = "Minimum switching time", description = "Lower bound in seconds between elevated mode changes. The effective hysteresis is the maximum of this value and the compressor cycle limits reported by the heat pump (minimum runtime while elevated, restart lock after leaving).")
	int minimumSwitchingTime() default 300;

	@AttributeDefinition(name = "Boost confirmation time", description = "Elevated mode starts only after its entry conditions have held for this many seconds in sum. The confirmation accumulates while the conditions hold and decays faster while they do not, so brief surplus dips slow the start without resetting it. Filters short surplus spikes that would otherwise trigger a committed compressor cycle.")
	int boostConfirmationSeconds() default 240;

	@AttributeDefinition(name = "Switch-off delay", description = "Elevated mode is only left after its coverage (PV surplus plus the invited battery support) has stayed below the heat-pump power for this many seconds in sum. The uncovered time accumulates and decays faster while clearly covered again, so flickering clouds bridge short dips without keeping the boost alive indefinitely. The compressor cycle limits and the minimum switching time still apply on top.")
	int switchOffDelay() default 120;

	@AttributeDefinition(name = "Forecast veto enabled", description = "Blocks elevated-mode entry if the prediction does not show enough surplus for the duration of a compressor cycle. Only useful with a weather-based production predictor; with simple persistence predictors this may wrongly block sunny days.")
	boolean forecastVetoEnabled() default false;

	@AttributeDefinition(name = "Run extension enabled", description = "Extends a natural hot-water run of the heat pump to the elevated hot-water setpoint while PV surplus plus allowed battery support fully cover the heat-pump power - saves compressor starts by topping up the storage in an already running cycle.")
	boolean runExtensionEnabled() default true;

	@AttributeDefinition(name = "Run extension minimum temperature delta [K]", description = "A natural run is only extended if the elevated hot-water setpoint exceeds the heat pump's own setpoint by at least this delta in K (e.g. 3.0). Prevents tiny extensions whose benefit is eaten by standing losses.")
	double extensionMinTemperatureDelta() default 3.0;

	@AttributeDefinition(name = "Debug Mode", description = "Activates the debug mode")
	boolean debugMode() default false;

	@AttributeDefinition(name = "Heat pump target filter", description = "Auto-generated by heat pump ID.")
	String heatPump_target() default "(enabled=true)";

	String webconsole_configurationFactory_nameHint() default "Controller SHI Heat Pump [{id}]";
}
