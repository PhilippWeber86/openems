package io.openems.edge.controller.heatpump.shi;

import io.openems.common.test.AbstractComponentConfig;
import io.openems.common.utils.ConfigUtils;

@SuppressWarnings("all")
public class MyConfig extends AbstractComponentConfig implements Config {

	protected static class Builder {
		private String id;
		private String heatPumpId = "heatPump0";
		private String essId = "ess0";
		private HeatPumpPosition heatPumpPosition = HeatPumpPosition.BEHIND_GRID_METER;
		private int minimumSurplusPowerForElevatedMode = 2500;
		private double heatingSetpoint = 55.0;
		private double hotWaterSetpoint = 55.0;
		private boolean essSupportEnabled = true;
		private int minSoc = 15;
		private int nightReserveBuffer = 120;
		private int essSupportDurationMinutes = 60;
		private int minCloudBufferPower = 0;
		private int minimumSwitchingTime = 300;
		private int boostConfirmationSeconds = 0;
		private boolean forecastVetoEnabled = false;
		private boolean runExtensionEnabled = true;
		private double extensionMinTemperatureDelta = 3.0;

		private Builder() {
		}

		public Builder setId(String id) {
			this.id = id;
			return this;
		}

		public Builder setHeatPumpId(String heatPumpId) {
			this.heatPumpId = heatPumpId;
			return this;
		}

		public Builder setEssId(String essId) {
			this.essId = essId;
			return this;
		}

		public Builder setHeatPumpPosition(HeatPumpPosition heatPumpPosition) {
			this.heatPumpPosition = heatPumpPosition;
			return this;
		}

		public Builder setMinimumSurplusPowerForElevatedMode(int value) {
			this.minimumSurplusPowerForElevatedMode = value;
			return this;
		}

		public Builder setHeatingSetpoint(double value) {
			this.heatingSetpoint = value;
			return this;
		}

		public Builder setHotWaterSetpoint(double value) {
			this.hotWaterSetpoint = value;
			return this;
		}

		public Builder setEssSupportEnabled(boolean value) {
			this.essSupportEnabled = value;
			return this;
		}

		public Builder setMinSoc(int value) {
			this.minSoc = value;
			return this;
		}

		public Builder setNightReserveBuffer(int value) {
			this.nightReserveBuffer = value;
			return this;
		}

		public Builder setEssSupportDurationMinutes(int value) {
			this.essSupportDurationMinutes = value;
			return this;
		}

		public Builder setMinCloudBufferPower(int value) {
			this.minCloudBufferPower = value;
			return this;
		}

		public Builder setMinimumSwitchingTime(int value) {
			this.minimumSwitchingTime = value;
			return this;
		}

		public Builder setBoostConfirmationSeconds(int value) {
			this.boostConfirmationSeconds = value;
			return this;
		}

		public Builder setForecastVetoEnabled(boolean value) {
			this.forecastVetoEnabled = value;
			return this;
		}

		public Builder setRunExtensionEnabled(boolean value) {
			this.runExtensionEnabled = value;
			return this;
		}

		public Builder setExtensionMinTemperatureDelta(double value) {
			this.extensionMinTemperatureDelta = value;
			return this;
		}

		public MyConfig build() {
			return new MyConfig(this);
		}
	}

	/**
	 * Create a Config builder.
	 *
	 * @return a {@link Builder}
	 */
	public static Builder create() {
		return new Builder();
	}

	private final Builder builder;

	private MyConfig(Builder builder) {
		super(Config.class, builder.id);
		this.builder = builder;
	}

	@Override
	public String heatPump_id() {
		return this.builder.heatPumpId;
	}

	@Override
	public String ess_id() {
		return this.builder.essId;
	}

	@Override
	public HeatPumpPosition heatPumpPosition() {
		return this.builder.heatPumpPosition;
	}

	@Override
	public int minimumSurplusPowerForElevatedMode() {
		return this.builder.minimumSurplusPowerForElevatedMode;
	}

	@Override
	public double heatingSetpoint() {
		return this.builder.heatingSetpoint;
	}

	@Override
	public double hotWaterSetpoint() {
		return this.builder.hotWaterSetpoint;
	}

	@Override
	public boolean essSupportEnabled() {
		return this.builder.essSupportEnabled;
	}

	@Override
	public int minSoc() {
		return this.builder.minSoc;
	}

	@Override
	public int nightReserveBuffer() {
		return this.builder.nightReserveBuffer;
	}

	@Override
	public int essSupportDurationMinutes() {
		return this.builder.essSupportDurationMinutes;
	}

	@Override
	public int minCloudBufferPower() {
		return this.builder.minCloudBufferPower;
	}

	@Override
	public int minimumSwitchingTime() {
		return this.builder.minimumSwitchingTime;
	}

	@Override
	public int boostConfirmationSeconds() {
		return this.builder.boostConfirmationSeconds;
	}

	@Override
	public boolean forecastVetoEnabled() {
		return this.builder.forecastVetoEnabled;
	}

	@Override
	public boolean runExtensionEnabled() {
		return this.builder.runExtensionEnabled;
	}

	@Override
	public double extensionMinTemperatureDelta() {
		return this.builder.extensionMinTemperatureDelta;
	}

	@Override
	public String heatPump_target() {
		return ConfigUtils.generateReferenceTargetFilter(this.id(), this.heatPump_id());
	}

}
