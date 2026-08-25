package io.openems.edge.controller.heatpump.shi;

import io.openems.common.channel.Level;
import io.openems.common.channel.Unit;
import io.openems.common.types.OpenemsType;
import io.openems.edge.common.channel.BooleanReadChannel;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.channel.IntegerReadChannel;
import io.openems.edge.common.channel.StateChannel;
import io.openems.edge.common.channel.value.Value;
import io.openems.edge.common.component.OpenemsComponent;

public interface ControllerShiHeatPump extends OpenemsComponent {

	public enum ChannelId implements io.openems.edge.common.channel.ChannelId {
		ELEVATED_MODE_ACTIVE(Doc.of(OpenemsType.BOOLEAN) //
				.text("Heat pump runs with elevated setpoints on PV surplus")), //
		BOOST_PENDING(Doc.of(OpenemsType.BOOLEAN) //
				.text("Elevated-mode entry conditions are fulfilled, waiting for the confirmation time")), //
		BOOST_FORECAST_VETO(Doc.of(OpenemsType.BOOLEAN) //
				.text("Elevated-mode entry is blocked by the forecast veto")), //
		RUN_EXTENSION_ACTIVE(Doc.of(OpenemsType.BOOLEAN) //
				.text("A natural hot-water run is extended to the elevated setpoint")), //
		NATURAL_HOT_WATER_SETPOINT(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.DEZIDEGREE_CELSIUS) //
				.text("Hot-water setpoint of the heat pump itself, latched while no external influence is active")), //
		FREE_BATTERY_ENERGY(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.WATT_HOURS) //
				.text("Battery energy released to the heat pump (spare energy above the night reserve); "
						+ "while positive the battery may support the heat pump, 0 when battery support is disabled")), //
		ESS_SUPPORT_POWER(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.WATT) //
				.text("Battery power actually supporting the heat pump (forced export after ESS clamping grid-side; "
						+ "best-effort estimate of the battery discharge attributable to the heat pump behind the meter)")), //
		ESS_FORCED_EXPORT_POWER(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.WATT) //
				.text("Battery export power forced through the grid meter towards the heat pump "
						+ "(heat pump grid-side of the grid meter)")), //
		ESS_DISCHARGE_LIMIT(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.WATT) //
				.text("Applied upper limit for ESS discharge power "
						+ "(heat pump behind the grid meter)")), //
		NIGHT_RESERVE_ENERGY(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.WATT_HOURS) //
				.text("Battery energy reserved for the household until the next forecasted PV surplus")), //
		NO_PREDICTION_AVAILABLE(Doc.of(Level.WARNING) //
				.text("No valid production/consumption prediction is available.")), //
		POWER_MEASUREMENT_UNAVAILABLE(Doc.of(Level.WARNING) //
				.text("Grid, ESS or heat-pump power measurement is missing - the PV surplus cannot be verified, "
						+ "so no elevated mode and no battery support are granted (fail-safe).")), //
		CONTROL_NOT_ALLOWED(Doc.of(Level.WARNING) //
				.text("The heat pump device is in read-only mode - the Controller cannot write any setpoints.")), //
		METER_TYPE_MISMATCH(Doc.of(Level.WARNING) //
				.text("Heat pump Meter-Type does not match the configured heat pump position: "
						+ "BEHIND_GRID_METER expects CONSUMPTION_METERED, "
						+ "GRID_SIDE_OF_GRID_METER expects CONSUMPTION_NOT_METERED.")); //

		private final Doc doc;

		private ChannelId(Doc doc) {
			this.doc = doc;
		}

		@Override
		public Doc doc() {
			return this.doc;
		}
	}

	public default BooleanReadChannel getBoostPendingChannel() {
		return this.channel(ChannelId.BOOST_PENDING);
	}

	public default Value<Boolean> getBoostPending() {
		return this.getBoostPendingChannel().value();
	}

	/**
	 * Internal method to set the 'nextValue' on {@link ChannelId#BOOST_PENDING}
	 * Channel.
	 *
	 * @param value the next value
	 */
	public default void _setBoostPending(boolean value) {
		this.getBoostPendingChannel().setNextValue(value);
	}

	public default BooleanReadChannel getBoostForecastVetoChannel() {
		return this.channel(ChannelId.BOOST_FORECAST_VETO);
	}

	public default Value<Boolean> getBoostForecastVeto() {
		return this.getBoostForecastVetoChannel().value();
	}

	/**
	 * Internal method to set the 'nextValue' on
	 * {@link ChannelId#BOOST_FORECAST_VETO} Channel.
	 *
	 * @param value the next value
	 */
	public default void _setBoostForecastVeto(boolean value) {
		this.getBoostForecastVetoChannel().setNextValue(value);
	}

	public default BooleanReadChannel getRunExtensionActiveChannel() {
		return this.channel(ChannelId.RUN_EXTENSION_ACTIVE);
	}

	public default Value<Boolean> getRunExtensionActive() {
		return this.getRunExtensionActiveChannel().value();
	}

	/**
	 * Internal method to set the 'nextValue' on
	 * {@link ChannelId#RUN_EXTENSION_ACTIVE} Channel.
	 *
	 * @param value the next value
	 */
	public default void _setRunExtensionActive(boolean value) {
		this.getRunExtensionActiveChannel().setNextValue(value);
	}

	public default IntegerReadChannel getNaturalHotWaterSetpointChannel() {
		return this.channel(ChannelId.NATURAL_HOT_WATER_SETPOINT);
	}

	public default Value<Integer> getNaturalHotWaterSetpoint() {
		return this.getNaturalHotWaterSetpointChannel().value();
	}

	/**
	 * Internal method to set the 'nextValue' on
	 * {@link ChannelId#NATURAL_HOT_WATER_SETPOINT} Channel.
	 *
	 * @param value the next value
	 */
	public default void _setNaturalHotWaterSetpoint(Integer value) {
		this.getNaturalHotWaterSetpointChannel().setNextValue(value);
	}

	public default BooleanReadChannel getElevatedModeActiveChannel() {
		return this.channel(ChannelId.ELEVATED_MODE_ACTIVE);
	}

	public default Value<Boolean> getElevatedModeActive() {
		return this.getElevatedModeActiveChannel().value();
	}

	/**
	 * Internal method to set the 'nextValue' on
	 * {@link ChannelId#ELEVATED_MODE_ACTIVE} Channel.
	 *
	 * @param value the next value
	 */
	public default void _setElevatedModeActive(boolean value) {
		this.getElevatedModeActiveChannel().setNextValue(value);
	}

	public default IntegerReadChannel getFreeBatteryEnergyChannel() {
		return this.channel(ChannelId.FREE_BATTERY_ENERGY);
	}

	public default Value<Integer> getFreeBatteryEnergy() {
		return this.getFreeBatteryEnergyChannel().value();
	}

	/**
	 * Internal method to set the 'nextValue' on
	 * {@link ChannelId#FREE_BATTERY_ENERGY} Channel.
	 *
	 * @param value the next value
	 */
	public default void _setFreeBatteryEnergy(Integer value) {
		this.getFreeBatteryEnergyChannel().setNextValue(value);
	}

	public default IntegerReadChannel getEssSupportPowerChannel() {
		return this.channel(ChannelId.ESS_SUPPORT_POWER);
	}

	public default Value<Integer> getEssSupportPower() {
		return this.getEssSupportPowerChannel().value();
	}

	/**
	 * Internal method to set the 'nextValue' on
	 * {@link ChannelId#ESS_SUPPORT_POWER} Channel.
	 *
	 * @param value the next value
	 */
	public default void _setEssSupportPower(Integer value) {
		this.getEssSupportPowerChannel().setNextValue(value);
	}

	public default IntegerReadChannel getEssForcedExportPowerChannel() {
		return this.channel(ChannelId.ESS_FORCED_EXPORT_POWER);
	}

	public default Value<Integer> getEssForcedExportPower() {
		return this.getEssForcedExportPowerChannel().value();
	}

	/**
	 * Internal method to set the 'nextValue' on
	 * {@link ChannelId#ESS_FORCED_EXPORT_POWER} Channel.
	 *
	 * @param value the next value
	 */
	public default void _setEssForcedExportPower(Integer value) {
		this.getEssForcedExportPowerChannel().setNextValue(value);
	}

	public default IntegerReadChannel getEssDischargeLimitChannel() {
		return this.channel(ChannelId.ESS_DISCHARGE_LIMIT);
	}

	public default Value<Integer> getEssDischargeLimit() {
		return this.getEssDischargeLimitChannel().value();
	}

	/**
	 * Internal method to set the 'nextValue' on
	 * {@link ChannelId#ESS_DISCHARGE_LIMIT} Channel.
	 *
	 * @param value the next value
	 */
	public default void _setEssDischargeLimit(Integer value) {
		this.getEssDischargeLimitChannel().setNextValue(value);
	}

	public default IntegerReadChannel getNightReserveEnergyChannel() {
		return this.channel(ChannelId.NIGHT_RESERVE_ENERGY);
	}

	public default Value<Integer> getNightReserveEnergy() {
		return this.getNightReserveEnergyChannel().value();
	}

	/**
	 * Internal method to set the 'nextValue' on
	 * {@link ChannelId#NIGHT_RESERVE_ENERGY} Channel.
	 *
	 * @param value the next value
	 */
	public default void _setNightReserveEnergy(Integer value) {
		this.getNightReserveEnergyChannel().setNextValue(value);
	}

	public default StateChannel getControlNotAllowedChannel() {
		return this.channel(ChannelId.CONTROL_NOT_ALLOWED);
	}

	public default Value<Boolean> getControlNotAllowed() {
		return this.getControlNotAllowedChannel().value();
	}

	/**
	 * Internal method to set the 'nextValue' on
	 * {@link ChannelId#CONTROL_NOT_ALLOWED} Channel.
	 *
	 * @param value the next value
	 */
	public default void _setControlNotAllowed(boolean value) {
		this.getControlNotAllowedChannel().setNextValue(value);
	}

	public default StateChannel getMeterTypeMismatchChannel() {
		return this.channel(ChannelId.METER_TYPE_MISMATCH);
	}

	public default Value<Boolean> getMeterTypeMismatch() {
		return this.getMeterTypeMismatchChannel().value();
	}

	/**
	 * Internal method to set the 'nextValue' on
	 * {@link ChannelId#METER_TYPE_MISMATCH} Channel.
	 *
	 * @param value the next value
	 */
	public default void _setMeterTypeMismatch(boolean value) {
		this.getMeterTypeMismatchChannel().setNextValue(value);
	}

	public default StateChannel getNoPredictionAvailableChannel() {
		return this.channel(ChannelId.NO_PREDICTION_AVAILABLE);
	}

	public default Value<Boolean> getNoPredictionAvailable() {
		return this.getNoPredictionAvailableChannel().value();
	}

	/**
	 * Internal method to set the 'nextValue' on
	 * {@link ChannelId#NO_PREDICTION_AVAILABLE} Channel.
	 *
	 * @param value the next value
	 */
	public default void _setNoPredictionAvailable(boolean value) {
		this.getNoPredictionAvailableChannel().setNextValue(value);
	}

	public default StateChannel getPowerMeasurementUnavailableChannel() {
		return this.channel(ChannelId.POWER_MEASUREMENT_UNAVAILABLE);
	}

	public default Value<Boolean> getPowerMeasurementUnavailable() {
		return this.getPowerMeasurementUnavailableChannel().value();
	}

	/**
	 * Internal method to set the 'nextValue' on
	 * {@link ChannelId#POWER_MEASUREMENT_UNAVAILABLE} Channel.
	 *
	 * @param value the next value
	 */
	public default void _setPowerMeasurementUnavailable(boolean value) {
		this.getPowerMeasurementUnavailableChannel().setNextValue(value);
	}
}
