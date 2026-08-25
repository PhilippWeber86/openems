package io.openems.edge.heat.shi.test;

import io.openems.common.types.MeterType;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.test.TestUtils;
import io.openems.edge.heat.shi.HeatShiHeatPump;
import io.openems.edge.meter.test.AbstractDummyElectricityMeter;

/**
 * Provides a simple, simulated {@link HeatShiHeatPump} component that can be
 * used together with the OpenEMS Component test framework.
 */
public class DummyHeatShiHeatPump extends AbstractDummyElectricityMeter<DummyHeatShiHeatPump>
		implements HeatShiHeatPump {

	public DummyHeatShiHeatPump(String id) {
		super(id, //
				OpenemsComponent.ChannelId.values(), //
				io.openems.edge.meter.api.ElectricityMeter.ChannelId.values(), //
				HeatShiHeatPump.ChannelId.values() //
		);
		this.withMeterType(MeterType.CONSUMPTION_NOT_METERED);
		// Heating and hot-water operating modes are enabled by default
		TestUtils.withValue(this, HeatShiHeatPump.ChannelId.HEATING_STATUS, 1);
		TestUtils.withValue(this, HeatShiHeatPump.ChannelId.HOT_WATER_STATUS, 1);
	}

	@Override
	protected DummyHeatShiHeatPump self() {
		return this;
	}
}
