package io.openems.edge.kostal.plenticore.ess;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.openems.edge.bridge.modbus.test.DummyModbusBridge;
import io.openems.edge.common.channel.IntegerWriteChannel;
import io.openems.edge.common.test.AbstractComponentTest.TestCase;
import io.openems.edge.common.test.ComponentTest;
import io.openems.edge.kostal.plenticore.enums.ControlMode;

public class KostalManagedEssImplTest {

	@Test
	public void test() throws Exception {
		new ComponentTest(new KostalManagedEssImpl()) //
				.addReference("setModbus", new DummyModbusBridge("modbus0")) //
				.activate(MyConfig.create() //
						.setId("ess0") //
						.setReadOnlyMode(true) //
						.setModbusId("modbus0") //
						.setCapacity(10000) //
						.setWatchdog(20) //
						.setTolerance(30) //
						.setControlMode(ControlMode.INTERNAL) //
						.setModbusUnitId(71) //
						.setDebugMode(true) //
						.build()) //
				.next(new TestCase()) //
				.deactivate();
	}

	@Test
	public void testSmartModeReleasesToInternalWhenIdle() throws Exception {
		var ess = new KostalManagedEssImpl();
		new ComponentTest(ess) //
				.addReference("setModbus", new DummyModbusBridge("modbus0")) //
				.activate(MyConfig.create() //
						.setId("ess0") //
						.setReadOnlyMode(false) //
						.setModbusId("modbus0") //
						.setCapacity(10000) //
						.setWatchdog(20) //
						.setTolerance(50) //
						.setControlMode(ControlMode.SMART) //
						.setModbusUnitId(71) //
						.setDebugMode(false) //
						.build());

		IntegerWriteChannel setActivePower = ess.channel(KostalManagedEss.ChannelId.SET_ACTIVE_POWER);

		// Within the idle band SMART must NOT write a set-point, so the inverter's
		// control timeout expires and it returns to internal self-consumption -
		// instead of pinning the battery at 0 W.
		ess.applyPower(0, 0);
		assertTrue(setActivePower.getNextWriteValue().isEmpty());

		// An active set-point beyond the idle band: SMART takes over and writes it.
		ess.applyPower(-3000, 0);
		assertEquals(Integer.valueOf(-3000), setActivePower.getNextWriteValue().get());
	}
}
