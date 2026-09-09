package io.openems.edge.controller.heatpump.shi;

import static io.openems.edge.controller.heatpump.shi.HeatPumpCommand.MAX_PC_LIMIT;
import static io.openems.edge.heat.shi.HeatShiHeatPump.LPC_MODE_NONE;
import static io.openems.edge.heat.shi.HeatShiHeatPump.LPC_MODE_SOFT;
import static io.openems.edge.heat.shi.HeatShiHeatPump.MODE_NONE;
import static io.openems.edge.heat.shi.HeatShiHeatPump.MODE_SETPOINT;
import static io.openems.edge.heat.shi.HeatShiHeatPump.STATUS_ACTIVE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The command set each operating state asks for. Because the state now produces
 * one COMPLETE command, these can be checked without a device, a clock or a call
 * order - which is the property that was missing while the modes wrote
 * overlapping subsets of the registers.
 */
public class HeatPumpCommandTest {

	private static final int STATUS_OFF = 0;
	private static final int HEATING_SETPOINT = 550;
	private static final int HOT_WATER_SETPOINT = 550;

	@Test
	public void boostRaisesBothSetpointsAndLimitsToTheAvailablePower() {
		var command = HeatPumpCommand.boost(STATUS_ACTIVE, STATUS_ACTIVE, HEATING_SETPOINT, HOT_WATER_SETPOINT, 4000);

		assertEquals(Integer.valueOf(MODE_SETPOINT), command.heatingMode());
		assertEquals(Integer.valueOf(HEATING_SETPOINT), command.heatingSetpoint());
		assertEquals(Integer.valueOf(MODE_SETPOINT), command.hotWaterMode());
		assertEquals(Integer.valueOf(HOT_WATER_SETPOINT), command.hotWaterSetpoint());
		assertEquals(LPC_MODE_SOFT, command.lpcMode());
		assertEquals(4000, command.pcLimit());
	}

	/**
	 * The SHI rejects a command for an operating mode it has switched off, so those
	 * registers must not be written at all - "leave it alone" is a distinct outcome
	 * from "write 0".
	 */
	@Test
	public void aModeSwitchedOffAtTheDeviceIsNotWrittenAtAll() {
		// Heating off (summer), hot water on.
		var boost = HeatPumpCommand.boost(STATUS_OFF, STATUS_ACTIVE, HEATING_SETPOINT, HOT_WATER_SETPOINT, 4000);
		assertNull(boost.heatingMode());
		assertNull(boost.heatingSetpoint());
		assertEquals(Integer.valueOf(MODE_SETPOINT), boost.hotWaterMode());

		// The release path must not write a disabled register either.
		var normal = HeatPumpCommand.normal(STATUS_OFF, STATUS_OFF, false, 0);
		assertNull(normal.heatingMode());
		assertNull(normal.hotWaterMode());
		// The soft limit is not mode-gated, so it is always written.
		assertEquals(LPC_MODE_NONE, normal.lpcMode());
		assertEquals(0, normal.pcLimit());

		var extension = HeatPumpCommand.runExtension(STATUS_OFF, HOT_WATER_SETPOINT, 2000);
		assertNull(extension.heatingMode());
	}

	/**
	 * The extension raises hot water only. Elevating the heating setpoint of a run
	 * the heat pump started itself would overheat the house.
	 */
	@Test
	public void theRunExtensionReleasesHeatingAndRaisesHotWaterOnly() {
		var command = HeatPumpCommand.runExtension(STATUS_ACTIVE, HOT_WATER_SETPOINT, 2600);

		assertEquals(Integer.valueOf(MODE_NONE), command.heatingMode());
		assertNull(command.heatingSetpoint(), "heating must not be given a setpoint");
		assertEquals(Integer.valueOf(MODE_SETPOINT), command.hotWaterMode());
		assertEquals(Integer.valueOf(HOT_WATER_SETPOINT), command.hotWaterSetpoint());
		assertEquals(LPC_MODE_SOFT, command.lpcMode());
		assertEquals(2600, command.pcLimit());
	}

	@Test
	public void normalReleasesBothSetpointsAndOnlyLimitsWhenAsked() {
		var released = HeatPumpCommand.normal(STATUS_ACTIVE, STATUS_ACTIVE, false, 3000);
		assertEquals(Integer.valueOf(MODE_NONE), released.heatingMode());
		assertEquals(Integer.valueOf(MODE_NONE), released.hotWaterMode());
		assertNull(released.heatingSetpoint());
		assertNull(released.hotWaterSetpoint());
		// Below the minimum sensible power the limit is released entirely - a "use
		// almost nothing" recommendation would push the compressor into short cycling.
		assertEquals(LPC_MODE_NONE, released.lpcMode());
		assertEquals(0, released.pcLimit());

		var limited = HeatPumpCommand.normal(STATUS_ACTIVE, STATUS_ACTIVE, true, 3000);
		assertEquals(Integer.valueOf(MODE_NONE), limited.heatingMode(), "the setpoints stay released either way");
		assertEquals(LPC_MODE_SOFT, limited.lpcMode());
		assertEquals(3000, limited.pcLimit());
	}

	/**
	 * HR10041 holds 300 x 0.1 kW, so no state may ask for more - or for a negative
	 * value.
	 *
	 * @param power the requested soft-limit power in W
	 */
	@ParameterizedTest
	@ValueSource(ints = { -5000, -1, 0, 4000, MAX_PC_LIMIT, MAX_PC_LIMIT + 1, 999_999 })
	public void theSoftLimitStaysInsideTheRegisterRange(int power) {
		var expected = Math.max(0, Math.min(MAX_PC_LIMIT, power));

		assertEquals(expected,
				HeatPumpCommand.boost(STATUS_ACTIVE, STATUS_ACTIVE, HEATING_SETPOINT, HOT_WATER_SETPOINT, power)
						.pcLimit());
		assertEquals(expected, HeatPumpCommand.runExtension(STATUS_ACTIVE, HOT_WATER_SETPOINT, power).pcLimit());
		assertEquals(expected, HeatPumpCommand.normal(STATUS_ACTIVE, STATUS_ACTIVE, true, power).pcLimit());
	}
}
