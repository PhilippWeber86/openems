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
		ESS_SUPPORT_POWER(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.WATT) //
				.text("Battery power above the night reserve that may support the heat pump")), //
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
}
