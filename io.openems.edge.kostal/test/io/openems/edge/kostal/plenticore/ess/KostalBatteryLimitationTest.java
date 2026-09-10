package io.openems.edge.kostal.plenticore.ess;

import static io.openems.edge.kostal.plenticore.ess.KostalManagedEss.ChannelId.BATTERY_LIMITATION_AVAILABLE;
import static io.openems.edge.kostal.plenticore.ess.KostalManagedEss.ChannelId.SET_MAX_CHARGE_POWER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.openems.edge.bridge.modbus.test.DummyModbusBridge;
import io.openems.edge.common.channel.BooleanReadChannel;
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

/**
 * Tests the detection of the "Battery limitation" registers of MODBUS-TCP
 * documentation section 3.5 and the limit writing that depends on it.
 *
 * <p>
 * The DummyModbusBridge serves only the registers it was given; reading an
 * address it does not know produces a real ILLEGAL_DATA_ADDRESS response. That
 * is exactly how an inverter without section 3.5 behaves, so both outcomes of
 * the capability detection can be reproduced.
 */
public class KostalBatteryLimitationTest {

	/**
	 * Encodes floats the way the inverter delivers them: CDAB, i.e. low word first.
	 * {@link DummyModbusBridge#withRegistersFloat32} writes big-endian instead, which
	 * the component - reading with WordOrder.LSWMSW - would decode as garbage.
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
	 * A bridge serving every register the component reads unconditionally.
	 *
	 * @return the bridge
	 */
	private static DummyModbusBridge baseBridge() {
		return new DummyModbusBridge("modbus0") //
				.withRegisters(56, new int[2]) // inverter state
				.withRegisters(104, new int[2]) // energy manager mode
				.withRegisters(152, new int[66]) // frequency .. battery voltage
				.withRegisters(531, 17500) // inverter max power
				.withRegisters(582, 0) // actual battery power
				.withRegisters(1034, cdab(0f, 0f, 12950f, 12950f)); // set-point, dummy, limits
	}

	private static MyConfig config() {
		return config(ControlMode.REMOTE);
	}

	private static MyConfig config(ControlMode controlMode) {
		return MyConfig.create() //
				.setId("ess0") //
				.setReadOnlyMode(false) //
				.setModbusId("modbus0") //
				.setCapacity(10000) //
				.setWatchdog(20) //
				.setTolerance(50) //
				.setControlMode(controlMode) //
				.setModbusUnitId(71) //
				.setDebugMode(false) //
				.build();
	}

	private static KostalManagedEssImpl activate(DummyModbusBridge modbus) throws Exception {
		return activate(modbus, ControlMode.REMOTE, new DummyPower(), new DummySum());
	}

	private static KostalManagedEssImpl activate(DummyModbusBridge modbus, ControlMode controlMode, Power power,
			DummySum sum) throws Exception {
		var ess = new KostalManagedEssImpl();
		new ComponentTest(ess) //
				.addReference("setModbus", modbus) //
				.addReference("sum", sum) //
				.addReference("power", power) //
				.addReference("componentManager", new DummyComponentManager()) //
				.activate(config(controlMode)) //
				// enough Cycles for the LOW priority tasks to have come round at least once
				.next(new TestCase(), 20);
		return ess;
	}

	@Test
	public void testDetectedWhenRegistersAnswer() throws Exception {
		// 1280..1289: limits, fall-back limits, fall-back time
		var modbus = baseBridge() //
				.withRegisters(1280, cdab(12950f, 12950f, 21000f, 21000f)) //
				.withRegisters(1288, 0, 60); // U32, not word-swapped

		var ess = activate(modbus);

		BooleanReadChannel available = ess.channel(BATTERY_LIMITATION_AVAILABLE);
		assertTrue(available.getNextValue().orElse(false), "section 3.5 must be detected");

		// with the capability present the limits are handed to the inverter
		ess.applyPower(-3000, 0);
		IntegerWriteChannel chargeLimit = ess.channel(SET_MAX_CHARGE_POWER);
		assertNotNull(chargeLimit.getNextWriteValueAndReset().orElse(null),
				"a charge limit must be written once the registers are available");
	}

	@Test
	public void testNotDetectedWhenRegistersAreMissing() throws Exception {
		// 1280 is not served, so the read answers ILLEGAL_DATA_ADDRESS
		var ess = activate(baseBridge());

		BooleanReadChannel available = ess.channel(BATTERY_LIMITATION_AVAILABLE);
		assertFalse(available.getNextValue().orElse(true), "section 3.5 must not be reported");

		// and nothing is written to the limit registers
		ess.applyPower(-3000, 0);
		IntegerWriteChannel chargeLimit = ess.channel(SET_MAX_CHARGE_POWER);
		assertNull(chargeLimit.getNextWriteValueAndReset().orElse(null),
				"no limit may be written without the capability");
	}

	@Test
	public void testSmartKeepsASetPointThatIsPinnedByAnEqualityConstraint() throws Exception {
		// Section 3.5 present, so the charge cap IS handed to the inverter and the
		// "at the charge limit" release becomes reachable at all.
		var modbus = baseBridge() //
				.withRegisters(1280, cdab(12950f, 12950f, 21000f, 21000f)) //
				.withRegisters(1288, 0, 60);

		// An equality constraint - e.g. the export the heat-pump Controller forces out
		// of the battery - pins the solver: minimum AND maximum are the demanded 3000 W.
		// That must NOT be mistaken for a binding charge cap, which the inverter could
		// enforce on its own: a cap leaves the discharge side open, a demand does not.
		var sum = new DummySum();
		var ess = activate(modbus, ControlMode.SMART, new DummyPower() {

			@Override
			public int getMinPower(ManagedSymmetricEss e, SingleOrAllPhase phase, Pwr pwr) {
				return 3000;
			}

			@Override
			public int getMaxPower(ManagedSymmetricEss e, SingleOrAllPhase phase, Pwr pwr) {
				return 3000;
			}
		}, sum);

		// Grid and own Active-Power are needed for the balancing comparison; with both
		// at 0 W the 3000 W set-point is far from plain balancing, so the release
		// decision comes down to the charge-limit test.
		sum.getGridActivePowerChannel().setNextValue(0);
		sum.getGridActivePowerChannel().nextProcessImage();
		ess.getActivePowerChannel().setNextValue(0);
		ess.getActivePowerChannel().nextProcessImage();

		ess.applyPower(3000, 0);

		IntegerWriteChannel setActivePower = ess.channel(KostalManagedEss.ChannelId.SET_ACTIVE_POWER);
		assertEquals(Integer.valueOf(3000), setActivePower.getNextWriteValueAndReset().orElse(null),
				"a demanded discharge must be written, not released to 'AUTO'");
	}
}
