package io.openems.edge.heat.shi;

import io.openems.common.channel.AccessMode;
import io.openems.common.channel.PersistencePriority;
import io.openems.common.channel.Unit;
import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.common.types.OpenemsType;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.channel.IntegerReadChannel;
import io.openems.edge.common.channel.WriteChannel;
import io.openems.edge.common.channel.value.Value;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.meter.api.ElectricityMeter;

public interface HeatShiHeatPump extends ElectricityMeter, OpenemsComponent {

	public enum ChannelId implements io.openems.edge.common.channel.ChannelId {
		HEATING_MODE(Doc.of(OpenemsType.INTEGER) //
				.accessMode(AccessMode.READ_WRITE) //
				.persistencePriority(PersistencePriority.HIGH) //
				.text("Heating influence mode. 0=None, 1=Setpoint, 2=Offset")), //
		HEATING_SETPOINT(Doc.of(OpenemsType.INTEGER) //
				.accessMode(AccessMode.READ_WRITE) //
				.unit(Unit.DEZIDEGREE_CELSIUS) //
				.persistencePriority(PersistencePriority.HIGH) //
				.text("Return temperature setpoint. Requires HEATING_MODE=1")), //
		HEATING_OFFSET(Doc.of(OpenemsType.INTEGER) //
				.accessMode(AccessMode.READ_WRITE) //
				.unit(Unit.DEZIDEGREE_CELSIUS) //
				.persistencePriority(PersistencePriority.HIGH)), //
		HOT_WATER_MODE(Doc.of(OpenemsType.INTEGER) //
				.accessMode(AccessMode.READ_WRITE) //
				.persistencePriority(PersistencePriority.HIGH) //
				.text("Hot-water influence mode. 0=None, 1=Setpoint, 2=Offset")), //
		HOT_WATER_SETPOINT(Doc.of(OpenemsType.INTEGER) //
				.accessMode(AccessMode.READ_WRITE) //
				.unit(Unit.DEZIDEGREE_CELSIUS) //
				.persistencePriority(PersistencePriority.HIGH) //
				.text("Hot-water temperature setpoint. Requires HOT_WATER_MODE=1")), //
		HOT_WATER_OFFSET(Doc.of(OpenemsType.INTEGER) //
				.accessMode(AccessMode.READ_WRITE) //
				.unit(Unit.DEZIDEGREE_CELSIUS) //
				.persistencePriority(PersistencePriority.HIGH)), //
		LPC_MODE(Doc.of(OpenemsType.INTEGER) //
				.accessMode(AccessMode.READ_WRITE) //
				.persistencePriority(PersistencePriority.HIGH) //
				.text("Limitation power consumption mode. 0=None, 1=Soft, 2=Hard")), //
		PC_LIMIT(Doc.of(OpenemsType.INTEGER) //
				.accessMode(AccessMode.READ_WRITE) //
				.unit(Unit.WATT) //
				.persistencePriority(PersistencePriority.HIGH)), //
		MIN_PREDICTED_ACTIVE_POWER(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.WATT) //
				.persistencePriority(PersistencePriority.HIGH) //
				.text("Minimum predicted electrical power consumption of the heat pump")), //
		HEAT_PUMP_STATUS(Doc.of(OpenemsType.INTEGER) //
				.persistencePriority(PersistencePriority.HIGH)), //
		OPERATING_MODE_STATUS(Doc.of(OpenemsType.INTEGER) //
				.persistencePriority(PersistencePriority.HIGH)), //
		HEATING_STATUS(Doc.of(OpenemsType.INTEGER)), //
		HOT_WATER_STATUS(Doc.of(OpenemsType.INTEGER)), //
		HOT_WATER_TEMPERATURE(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.DEZIDEGREE_CELSIUS) //
				.persistencePriority(PersistencePriority.HIGH) //
				.text("Current hot water temperature")), //
		HOT_WATER_ACTIVE_SETPOINT(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.DEZIDEGREE_CELSIUS) //
				.persistencePriority(PersistencePriority.HIGH) //
				.text("Currently active hot-water setpoint of the heat pump; reflects the natural "
						+ "setpoint only while no external influence is active")), //
		CIRCULATION(Doc.of(OpenemsType.INTEGER) //
				.accessMode(AccessMode.READ_WRITE) //
				.persistencePriority(PersistencePriority.HIGH) //
				.text("Starts the hot-water circulation pump (0=no influence, 1=start). The heat pump "
						+ "resets it automatically after its configured circulation time. Requires "
						+ "flexConfig 'out 2' = ZIP at the heat pump")), //
		EXTRA_HOT_WATER(Doc.of(OpenemsType.INTEGER) //
				.accessMode(AccessMode.READ_WRITE) //
				.persistencePriority(PersistencePriority.HIGH) //
				.text("Switches the extra hot-water function (0=off, 1=on)")), //
		READ_ONLY_MODE(Doc.of(OpenemsType.BOOLEAN) //
				.persistencePriority(PersistencePriority.HIGH) //
				.text("Read-only mode is active; no setpoints are written to the heat pump")), //
		MIN_STANDSTILL_TIME(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.MINUTE) //
				.text("Minimum standstill time until the compressor may start again (restart lock)")), //
		MIN_RUNTIME(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.MINUTE) //
				.text("Minimum runtime of the compressor")); //

		private final Doc doc;

		private ChannelId(Doc doc) {
			this.doc = doc;
		}

		@Override
		public Doc doc() {
			return this.doc;
		}
	}

	public static final int MODE_NONE = 0;
	public static final int MODE_SETPOINT = 1;
	public static final int MODE_OFFSET = 2;

	public static final int LPC_MODE_NONE = 0;
	public static final int LPC_MODE_SOFT = 1;
	public static final int LPC_MODE_HARD = 2;

	/** Value of {@link ChannelId#OPERATING_MODE_STATUS} for hot-water production. */
	public static final int OPERATING_MODE_HOT_WATER = 1;
	/** Value of {@link ChannelId#HEATING_STATUS}/{@link ChannelId#HOT_WATER_STATUS} for an active run. */
	public static final int STATUS_ACTIVE = 3;

	public default WriteChannel<Integer> getHeatingModeChannel() {
		return this.channel(ChannelId.HEATING_MODE);
	}

	public default WriteChannel<Integer> getHeatingSetpointChannel() {
		return this.channel(ChannelId.HEATING_SETPOINT);
	}

	public default WriteChannel<Integer> getHeatingOffsetChannel() {
		return this.channel(ChannelId.HEATING_OFFSET);
	}

	public default WriteChannel<Integer> getHotWaterModeChannel() {
		return this.channel(ChannelId.HOT_WATER_MODE);
	}

	public default WriteChannel<Integer> getHotWaterSetpointChannel() {
		return this.channel(ChannelId.HOT_WATER_SETPOINT);
	}

	public default WriteChannel<Integer> getHotWaterOffsetChannel() {
		return this.channel(ChannelId.HOT_WATER_OFFSET);
	}

	public default WriteChannel<Integer> getLpcModeChannel() {
		return this.channel(ChannelId.LPC_MODE);
	}

	public default WriteChannel<Integer> getPcLimitChannel() {
		return this.channel(ChannelId.PC_LIMIT);
	}

	public default IntegerReadChannel getMinPredictedActivePowerChannel() {
		return this.channel(ChannelId.MIN_PREDICTED_ACTIVE_POWER);
	}

	public default Value<Integer> getMinPredictedActivePower() {
		return this.getMinPredictedActivePowerChannel().value();
	}

	public default IntegerReadChannel getOperatingModeStatusChannel() {
		return this.channel(ChannelId.OPERATING_MODE_STATUS);
	}

	public default IntegerReadChannel getHeatPumpStatusChannel() {
		return this.channel(ChannelId.HEAT_PUMP_STATUS);
	}

	public default Value<Integer> getHeatPumpStatus() {
		return this.getHeatPumpStatusChannel().value();
	}

	public default Value<Integer> getOperatingModeStatus() {
		return this.getOperatingModeStatusChannel().value();
	}

	public default IntegerReadChannel getHeatingStatusChannel() {
		return this.channel(ChannelId.HEATING_STATUS);
	}

	public default Value<Integer> getHeatingStatus() {
		return this.getHeatingStatusChannel().value();
	}

	public default WriteChannel<Integer> getCirculationChannel() {
		return this.channel(ChannelId.CIRCULATION);
	}

	public default void setCirculation(int value) throws OpenemsNamedException {
		this.getCirculationChannel().setNextWriteValue(value);
	}

	public default WriteChannel<Integer> getExtraHotWaterChannel() {
		return this.channel(ChannelId.EXTRA_HOT_WATER);
	}

	public default void setExtraHotWater(int value) throws OpenemsNamedException {
		this.getExtraHotWaterChannel().setNextWriteValue(value);
	}

	public default io.openems.edge.common.channel.BooleanReadChannel getReadOnlyModeChannel() {
		return this.channel(ChannelId.READ_ONLY_MODE);
	}

	public default Value<Boolean> getReadOnlyMode() {
		return this.getReadOnlyModeChannel().value();
	}

	/**
	 * Internal method to set the 'nextValue' on {@link ChannelId#READ_ONLY_MODE}
	 * Channel.
	 *
	 * @param value the next value
	 */
	public default void _setReadOnlyMode(boolean value) {
		this.getReadOnlyModeChannel().setNextValue(value);
	}

	public default IntegerReadChannel getHotWaterTemperatureChannel() {
		return this.channel(ChannelId.HOT_WATER_TEMPERATURE);
	}

	public default Value<Integer> getHotWaterTemperature() {
		return this.getHotWaterTemperatureChannel().value();
	}

	public default IntegerReadChannel getHotWaterActiveSetpointChannel() {
		return this.channel(ChannelId.HOT_WATER_ACTIVE_SETPOINT);
	}

	public default Value<Integer> getHotWaterActiveSetpoint() {
		return this.getHotWaterActiveSetpointChannel().value();
	}

	public default IntegerReadChannel getMinStandstillTimeChannel() {
		return this.channel(ChannelId.MIN_STANDSTILL_TIME);
	}

	public default Value<Integer> getMinStandstillTime() {
		return this.getMinStandstillTimeChannel().value();
	}

	public default IntegerReadChannel getMinRuntimeChannel() {
		return this.channel(ChannelId.MIN_RUNTIME);
	}

	public default Value<Integer> getMinRuntime() {
		return this.getMinRuntimeChannel().value();
	}

	public default IntegerReadChannel getHotWaterStatusChannel() {
		return this.channel(ChannelId.HOT_WATER_STATUS);
	}

	public default Value<Integer> getHotWaterStatus() {
		return this.getHotWaterStatusChannel().value();
	}

	public default void setHeatingMode(int value) throws OpenemsNamedException {
		this.getHeatingModeChannel().setNextWriteValue(value);
	}

	public default void setHeatingSetpoint(int value) throws OpenemsNamedException {
		this.getHeatingSetpointChannel().setNextWriteValue(value);
	}

	public default void setHeatingOffset(int value) throws OpenemsNamedException {
		this.getHeatingOffsetChannel().setNextWriteValue(value);
	}

	public default void setHotWaterMode(int value) throws OpenemsNamedException {
		this.getHotWaterModeChannel().setNextWriteValue(value);
	}

	public default void setHotWaterSetpoint(int value) throws OpenemsNamedException {
		this.getHotWaterSetpointChannel().setNextWriteValue(value);
	}

	public default void setHotWaterOffset(int value) throws OpenemsNamedException {
		this.getHotWaterOffsetChannel().setNextWriteValue(value);
	}

	public default void setLpcMode(int value) throws OpenemsNamedException {
		this.getLpcModeChannel().setNextWriteValue(value);
	}

	public default void setPcLimit(int value) throws OpenemsNamedException {
		this.getPcLimitChannel().setNextWriteValue(value);
	}
}
