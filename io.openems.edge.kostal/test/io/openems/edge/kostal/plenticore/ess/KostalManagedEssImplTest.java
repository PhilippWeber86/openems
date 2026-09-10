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
import io.openems.edge.common.test.AbstractComponentTest.TestCase;
import io.openems.edge.common.sum.DummySum;
import io.openems.edge.common.type.Phase.SingleOrAllPhase;
import io.openems.edge.ess.api.ManagedSymmetricEss;
import io.openems.edge.ess.power.api.Pwr;
import io.openems.edge.ess.test.DummyPower;
import io.openems.edge.common.test.ComponentTest;
import io.openems.edge.common.test.DummyComponentManager;
import io.openems.edge.kostal.plenticore.enums.ControlMode;

public class KostalManagedEssImplTest {

	private static final int TOLERANCE = 50;

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
	 * Activates a {@link KostalManagedEssImpl} for the given {@link ControlMode}.
	 *
	 * @param controlMode the {@link ControlMode}
	 * @param readOnly    the Read-Only mode
	 * @return the activated component
	 * @throws Exception on error
	 */
	private static KostalManagedEssImpl activate(ControlMode controlMode, boolean readOnly) throws Exception {
		return activate(controlMode, readOnly, createDummyClock());
	}

	/**
	 * Activates a {@link KostalManagedEssImpl} on a clock the test controls, so the
	 * watchdog refresh can be driven without waiting.
	 *
	 * @param controlMode the {@link ControlMode}
	 * @param readOnly    the Read-Only mode
	 * @param clock       the {@link Clock}
	 * @return the activated component
	 * @throws Exception on error
	 */
	private static KostalManagedEssImpl activate(ControlMode controlMode, boolean readOnly, Clock clock)
			throws Exception {
		var ess = new KostalManagedEssImpl();
		new ComponentTest(ess) //
				.addReference("setModbus", new DummyModbusBridge("modbus0")) //
				.addReference("sum", new DummySum()) //
				.addReference("power", new DummyPower()) //
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
	public void testSmartKeepsZeroWhenAControllerBlocksDischarge() throws Exception {
		// Controller.Ess.LimitTotalDischarge expresses its SoC floor as
		// SetActivePowerLessOrEquals, so the solved maximum is 0 W while the DEVICE
		// still reports 5000 W of discharge freedom. The 0 W set-point is therefore
		// enforced, not idle: releasing to internal 'AUTO' would let the inverter's
		// own self-consumption regulation discharge past the floor.
		var ess = new KostalManagedEssImpl();
		new ComponentTest(ess) //
				.addReference("setModbus", new DummyModbusBridge("modbus0")) //
				.addReference("sum", new DummySum()) //
				.addReference("power", powerWithRange(-5000, 0)) //
				.addReference("componentManager", new DummyComponentManager()) //
				.activate(MyConfig.create() //
						.setId("ess0") //
						.setReadOnlyMode(false) //
						.setModbusId("modbus0") //
						.setCapacity(10000) //
						.setWatchdog(20) //
						.setTolerance(TOLERANCE) //
						.setControlMode(ControlMode.SMART) //
						.setModbusUnitId(71) //
						.setDebugMode(false) //
						.build());
		ess.getAllowedChargePowerChannel().setNextValue(-5000);
		ess.getAllowedChargePowerChannel().nextProcessImage();
		ess.getAllowedDischargePowerChannel().setNextValue(5000);
		ess.getAllowedDischargePowerChannel().nextProcessImage();

		ess.applyPower(0, 0);

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
		var ess = activate(ControlMode.REMOTE, false, clock);

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
		var ess = activate(ControlMode.SMART, false, clock);

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
}
