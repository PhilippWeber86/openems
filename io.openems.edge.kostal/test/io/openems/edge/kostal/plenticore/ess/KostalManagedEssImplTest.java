package io.openems.edge.kostal.plenticore.ess;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import io.openems.edge.bridge.modbus.test.DummyModbusBridge;
import io.openems.edge.common.channel.IntegerWriteChannel;
import io.openems.edge.common.test.AbstractComponentTest.TestCase;
import io.openems.edge.common.test.ComponentTest;
import io.openems.edge.kostal.plenticore.enums.ControlMode;

public class KostalManagedEssImplTest {

	private static final int TOLERANCE = 50;

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

	/**
	 * Activates a {@link KostalManagedEssImpl} for the given {@link ControlMode}.
	 *
	 * @param controlMode the {@link ControlMode}
	 * @param readOnly    the Read-Only mode
	 * @return the activated component
	 * @throws Exception on error
	 */
	private static KostalManagedEssImpl activate(ControlMode controlMode, boolean readOnly) throws Exception {
		var ess = new KostalManagedEssImpl();
		new ComponentTest(ess) //
				.addReference("setModbus", new DummyModbusBridge("modbus0")) //
				.activate(MyConfig.create() //
						.setId("ess0") //
						.setReadOnlyMode(readOnly) //
						.setModbusId("modbus0") //
						.setCapacity(10000) //
						.setWatchdog(20) //
						.setTolerance(TOLERANCE) //
						.setControlMode(controlMode) //
						.setModbusUnitId(71) //
						.setDebugMode(false) //
						.build());
		return ess;
	}

	/**
	 * Takes the set-point that would be written to Modbus register 1034 and resets
	 * the channel, so the next call reports only what the next cycle writes.
	 *
	 * @param ess the component
	 * @return the set-point in [W], or null if nothing is written
	 * @throws Exception on error
	 */
	private static Integer writtenSetPoint(KostalManagedEssImpl ess) throws Exception {
		IntegerWriteChannel channel = ess.channel(KostalManagedEss.ChannelId.SET_ACTIVE_POWER);
		return channel.getNextWriteValueAndReset().orElse(null);
	}

	@Test
	public void testSmartModeReleasesToInternalWhenIdle() throws Exception {
		var ess = activate(ControlMode.SMART, false);

		// Within the idle band SMART must NOT write a set-point, so the inverter's
		// control timeout expires and it returns to internal self-consumption -
		// instead of pinning the battery at 0 W.
		ess.applyPower(0, 0);
		assertNull(writtenSetPoint(ess));

		// An active set-point beyond the idle band: SMART takes over and writes it.
		ess.applyPower(-3000, 0);
		assertEquals(Integer.valueOf(-3000), writtenSetPoint(ess));

		// Back into the idle band: release again.
		ess.applyPower(10, 0);
		assertNull(writtenSetPoint(ess));

		// Re-engaging after a release writes immediately, without waiting for a refresh.
		ess.applyPower(-3000, 0);
		assertEquals(Integer.valueOf(-3000), writtenSetPoint(ess));

		// Small changes are written too - suppressing them would be a dead-band in front
		// of the Ess.Power PID filter.
		ess.applyPower(-3040, 0);
		assertEquals(Integer.valueOf(-3040), writtenSetPoint(ess));

		// An unchanged set-point is not re-written until the refresh is due.
		ess.applyPower(-3040, 0);
		assertNull(writtenSetPoint(ess));
	}

	@Test
	public void testRemoteModeWritesZeroInsideIdleZone() throws Exception {
		var ess = activate(ControlMode.REMOTE, false);

		// REMOTE keeps full control, so 0 W is a legitimate set-point and must be
		// written instead of releasing the battery.
		ess.applyPower(10, 0);
		assertEquals(Integer.valueOf(0), writtenSetPoint(ess));

		ess.applyPower(300, 0);
		assertEquals(Integer.valueOf(300), writtenSetPoint(ess));
		ess.applyPower(320, 0);
		assertEquals(Integer.valueOf(320), writtenSetPoint(ess));
	}

	@Test
	public void testInternalModeNeverWrites() throws Exception {
		var ess = activate(ControlMode.INTERNAL, false);

		ess.applyPower(-3000, 0);

		assertNull(writtenSetPoint(ess));
	}

	@Test
	public void testReadOnlyModeNeverWrites() throws Exception {
		var ess = activate(ControlMode.SMART, true);

		ess.applyPower(-3000, 0);

		assertNull(writtenSetPoint(ess));
	}
}
