package io.openems.edge.kostal.plenticore.ess;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import io.openems.edge.bridge.modbus.test.DummyModbusBridge;
import io.openems.edge.common.channel.IntegerWriteChannel;
import io.openems.edge.common.sum.DummySum;
import io.openems.edge.common.test.AbstractComponentTest.TestCase;
import io.openems.edge.common.test.ComponentTest;
import io.openems.edge.ess.test.DummyPower;
import io.openems.edge.kostal.plenticore.enums.ControlMode;

public class KostalManagedEssImplTest {

	private static final int TOLERANCE = 50;

	/**
	 * Encodes floats the way the inverter delivers them: CDAB, low word first.
	 *
	 * @param values the values
	 * @return the register words
	 */
	private static int[] cdab(float... values) {
		var words = new int[values.length * 2];
		for (var i = 0; i < values.length; i++) {
			var bits = Float.floatToIntBits(values[i]);
			words[2 * i] = bits & 0xFFFF;
			words[2 * i + 1] = bits >>> 16 & 0xFFFF;
		}
		return words;
	}

	/**
	 * A bridge that reports a given operating point of the inverter.
	 *
	 * @param acPower      register 172, the AC power of the whole inverter
	 * @param batteryPower register 582, positive for discharge
	 * @return the bridge
	 */
	private static DummyModbusBridge bridge(int acPower, int batteryPower) {
		var block152 = new int[66]; // 152..217
		System.arraycopy(cdab(acPower), 0, block152, 172 - 152, 2);
		// A realistic state of charge matters: at 0 % setLimits() forbids discharging,
		// hasHardLimit() sees that and suppresses every release - correctly so.
		System.arraycopy(cdab(50f), 0, block152, 210 - 152, 2);
		return new DummyModbusBridge("modbus0") //
				.withRegisters(56, new int[2]) //
				.withRegisters(104, new int[2]) //
				.withRegisters(152, block152) //
				.withRegisters(531, 17500) //
				.withRegisters(582, batteryPower & 0xFFFF) //
				// set-point read-back, gap, then the device charge and discharge limits.
				// Real limits matter: with zeros here hasHardLimit() would see a battery
				// that may neither charge nor discharge and would suppress every release.
				.withRegisters(1034, cdab(0f, 0f, 12950f, 12950f));
	}

	@Test
	public void test() throws Exception {
		new ComponentTest(new KostalManagedEssImpl()) //
				.addReference("setModbus", new DummyModbusBridge("modbus0")) //
				.addReference("sum", new DummySum()) //
				.addReference("power", new DummyPower()) //
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
	 * Activates a {@link KostalManagedEssImpl} for the given {@link ControlMode} at
	 * a given operating point.
	 *
	 * @param controlMode  the {@link ControlMode}
	 * @param readOnly     the Read-Only mode
	 * @param acPower      register 172, the AC power of the whole inverter
	 * @param batteryPower register 582, positive for discharge
	 * @return the activated component
	 * @throws Exception on error
	 */
	private static KostalManagedEssImpl activate(ControlMode controlMode, boolean readOnly, int acPower,
			int batteryPower) throws Exception {
		var ess = new KostalManagedEssImpl();
		new ComponentTest(ess) //
				.addReference("setModbus", bridge(acPower, batteryPower)) //
				.addReference("sum", new DummySum()) //
				.addReference("power", new DummyPower()) //
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
						.build()) //
				// enough Cycles for the register values to reach the process image
				.next(new TestCase(), 20);
		return ess;
	}

	/**
	 * Activates at night: no PV, battery idle. The AC set-point and the battery
	 * set-point are then the same number.
	 *
	 * @param controlMode the {@link ControlMode}
	 * @param readOnly    the Read-Only mode
	 * @return the activated component
	 * @throws Exception on error
	 */
	private static KostalManagedEssImpl activate(ControlMode controlMode, boolean readOnly) throws Exception {
		return activate(controlMode, readOnly, 0, 0);
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

	@Test
	public void testAcSetPointIsConvertedToBatterySetPoint() throws Exception {
		// 8000 W out on AC with the battery idle, i.e. 8000 W of PV.
		var ess = activate(ControlMode.REMOTE, false, 8000, 0);

		// Asking for exactly what the PV delivers leaves nothing for the battery.
		ess.applyPower(8000, 0);
		assertEquals(Integer.valueOf(0), writtenSetPoint(ess));

		// Asking for less means the difference has to go into the battery.
		ess.applyPower(5000, 0);
		assertEquals(Integer.valueOf(-3000), writtenSetPoint(ess));

		// Asking for more means the battery has to make up the difference.
		ess.applyPower(9500, 0);
		assertEquals(Integer.valueOf(1500), writtenSetPoint(ess));
	}

	@Test
	public void testSmartIdleZoneFollowsTheBatteryNotTheAcPower() throws Exception {
		// The battery is charging 2000 W out of 8000 W of PV, so the inverter puts
		// 6000 W on AC.
		var ess = activate(ControlMode.SMART, false, 6000, -2000);

		// A set-point of 8000 W AC asks the battery for nothing - even though the AC
		// figure is nowhere near zero. That has to release, not write 8000 W.
		ess.applyPower(8000, 0);
		assertNull(writtenSetPoint(ess));

		// While a set-point of 0 W AC is a real request: hold back the whole PV.
		ess.applyPower(0, 0);
		assertEquals(Integer.valueOf(-8000), writtenSetPoint(ess));
	}

	@Test
	public void testNothingIsWrittenWhileThePvShareIsUnknown() throws Exception {
		// No registers at all: without Active-Power and DC-Discharge-Power the AC
		// set-point cannot be converted, and guessing would command the battery to the
		// whole AC target.
		var ess = new KostalManagedEssImpl();
		new ComponentTest(ess) //
				.addReference("setModbus", new DummyModbusBridge("modbus0")) //
				.addReference("sum", new DummySum()) //
				.addReference("power", new DummyPower()) //
				.activate(MyConfig.create() //
						.setId("ess0") //
						.setReadOnlyMode(false) //
						.setModbusId("modbus0") //
						.setCapacity(10000) //
						.setWatchdog(20) //
						.setTolerance(TOLERANCE) //
						.setControlMode(ControlMode.REMOTE) //
						.setModbusUnitId(71) //
						.setDebugMode(false) //
						.build());

		ess.applyPower(-3000, 0);

		assertNull(writtenSetPoint(ess));
	}
}
