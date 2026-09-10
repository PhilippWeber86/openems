package io.openems.edge.kostal.plenticore.ess;

import static io.openems.common.test.TestUtils.createDummyClock;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Clock;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.Test;

import io.openems.common.test.TimeLeapClock;
import io.openems.edge.bridge.modbus.test.DummyModbusBridge;
import io.openems.edge.common.channel.IntegerWriteChannel;
import io.openems.edge.common.sum.DummySum;
import io.openems.edge.common.test.AbstractComponentTest.TestCase;
import io.openems.edge.common.test.ComponentTest;
import io.openems.edge.common.test.DummyComponentManager;
import io.openems.edge.common.type.Phase.SingleOrAllPhase;
import io.openems.edge.ess.api.ManagedSymmetricEss;
import io.openems.edge.ess.power.api.Power;
import io.openems.edge.ess.power.api.Pwr;
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
				.addReference("componentManager", new DummyComponentManager()) //
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
		return activate(controlMode, readOnly, acPower, batteryPower, new DummyPower(), createDummyClock());
	}

	/**
	 * Activates a {@link KostalManagedEssImpl} with a given solver stub.
	 *
	 * @param controlMode  the {@link ControlMode}
	 * @param readOnly     the Read-Only mode
	 * @param acPower      register 172, the AC power of the whole inverter
	 * @param batteryPower register 582, positive for discharge
	 * @param power        the {@link Power}
	 * @return the activated component
	 * @throws Exception on error
	 */
	private static KostalManagedEssImpl activate(ControlMode controlMode, boolean readOnly, int acPower,
			int batteryPower, Power power) throws Exception {
		return activate(controlMode, readOnly, acPower, batteryPower, power, createDummyClock());
	}

	/**
	 * Activates a {@link KostalManagedEssImpl} on a clock the test controls, so the
	 * watchdog refresh can be driven without waiting.
	 *
	 * @param controlMode  the {@link ControlMode}
	 * @param readOnly     the Read-Only mode
	 * @param acPower      register 172, the AC power of the whole inverter
	 * @param batteryPower register 582, positive for discharge
	 * @param power        the {@link Power}
	 * @param clock        the {@link Clock}
	 * @return the activated component
	 * @throws Exception on error
	 */
	private static KostalManagedEssImpl activate(ControlMode controlMode, boolean readOnly, int acPower,
			int batteryPower, Power power, Clock clock) throws Exception {
		var ess = new KostalManagedEssImpl();
		new ComponentTest(ess) //
				.addReference("setModbus", bridge(acPower, batteryPower)) //
				.addReference("sum", new DummySum()) //
				.addReference("power", power) //
				.addReference("componentManager", new DummyComponentManager(clock)) //
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

	/**
	 * A solver stub with a fixed range, to express what Controllers have made of the
	 * battery - which the device allowances do not show.
	 *
	 * @param minPower the solved minimum Active-Power
	 * @param maxPower the solved maximum Active-Power
	 * @return the {@link DummyPower}
	 */
	private static DummyPower powerWithRange(int minPower, int maxPower) {
		return new DummyPower() {

			@Override
			public int getMinPower(ManagedSymmetricEss ess, SingleOrAllPhase phase, Pwr pwr) {
				return minPower;
			}

			@Override
			public int getMaxPower(ManagedSymmetricEss ess, SingleOrAllPhase phase, Pwr pwr) {
				return maxPower;
			}
		};
	}


	@Test
	public void testSmartStillReleasesWhenPvExceedsTheChargeAllowance() throws Exception {
		// 8000 W of PV while the battery may charge at most 5000 W: the AC minimum is
		// 8000 - 5000 = 3000 W, i.e. POSITIVE, although charging is perfectly possible.
		// The solver bounds are AC bounds and have to be judged against the PV share -
		// testing them against zero would read this as "charging blocked" and suppress
		// every release for as long as the sun is out.
		var ess = activate(ControlMode.SMART, false, 6000, -2000, powerWithRange(3000, 13000));

		// An AC set-point of 8000 W asks the battery for nothing, and nothing forbids it
		// anything either - so this must release to internal 'AUTO'.
		ess.applyPower(8000, 0);

		assertNull(writtenSetPoint(ess));
	}

	@Test
	public void testSmartKeepsZeroWhenAControllerBlocksDischarge() throws Exception {
		// 4000 W of PV, battery idle, so the PV share is 4000 W. A Controller has closed
		// the discharge side - Controller.Ess.LimitTotalDischarge expresses its SoC floor
		// as SetActivePowerLessOrEquals - which in AC terms caps the maximum at the PV
		// share: nothing is left for the battery above it. The DEVICE limits stay wide
		// open (12950 W from the bridge), so they cannot reveal this.
		var ess = activate(ControlMode.SMART, false, 4000, 0, powerWithRange(-1000, 4000));

		// An AC set-point of 4000 W asks the battery for 0 W, which lands in the idle
		// band - but that zero is ENFORCED, not free. Releasing to internal 'AUTO' would
		// let the inverter's own self-consumption regulation discharge past the floor,
		// so the 0 W has to be written.
		ess.applyPower(4000, 0);

		assertEquals(Integer.valueOf(0), writtenSetPoint(ess));
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
	public void testUnchangedSetPointIsRefreshedAtHalfTheWatchdog() throws Exception {
		var clock = new TimeLeapClock(java.time.Instant.ofEpochSecond(1577836800));
		// watchdog 20 s, so the refresh is due after 10 s
		var ess = activate(ControlMode.REMOTE, false, 0, 0, new DummyPower(), clock);

		ess.applyPower(-3000, 0);
		assertEquals(Integer.valueOf(-3000), writtenSetPoint(ess));

		// unchanged and still fresh: nothing goes onto the bus
		clock.leap(9, ChronoUnit.SECONDS);
		ess.applyPower(-3000, 0);
		assertNull(writtenSetPoint(ess));

		// half the watchdog gone: refreshed well before the inverter's control timeout,
		// which would otherwise drop it into internal mode for a moment every time
		clock.leap(1, ChronoUnit.SECONDS);
		ess.applyPower(-3000, 0);
		assertEquals(Integer.valueOf(-3000), writtenSetPoint(ess));

		// and the clock starts over from that write
		clock.leap(9, ChronoUnit.SECONDS);
		ess.applyPower(-3000, 0);
		assertNull(writtenSetPoint(ess));
	}

	@Test
	public void testAReleasedBatteryIsNeverRefreshed() throws Exception {
		var clock = new TimeLeapClock(java.time.Instant.ofEpochSecond(1577836800));
		var ess = activate(ControlMode.SMART, false, 0, 0, new DummyPower(), clock);

		ess.applyPower(-3000, 0);
		assertEquals(Integer.valueOf(-3000), writtenSetPoint(ess));

		// into the idle zone: released
		ess.applyPower(0, 0);
		assertNull(writtenSetPoint(ess));

		// Long past the watchdog nothing may be written. A refresh here would keep the
		// inverter's control timeout alive forever, and the battery would stay pinned
		// instead of returning to internal self-consumption - the whole point of the
		// release.
		clock.leap(5 * 20, ChronoUnit.SECONDS);
		ess.applyPower(0, 0);
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
				.addReference("componentManager", new DummyComponentManager()) //
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
