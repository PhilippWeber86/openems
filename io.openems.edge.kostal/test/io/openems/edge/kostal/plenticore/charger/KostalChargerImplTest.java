package io.openems.edge.kostal.plenticore.charger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import io.openems.edge.bridge.modbus.test.DummyModbusBridge;
import io.openems.edge.common.test.AbstractComponentTest.TestCase;
import io.openems.edge.common.test.ComponentTest;
import io.openems.edge.ess.dccharger.api.EssDcCharger;

/**
 * Tests the per-input register arithmetic of {@link PvPort}, the scale factors
 * and the word order.
 *
 * <p>
 * Both PV inputs are preloaded with different values, so configuring one of
 * them and reading back its values proves that the address offset actually
 * shifts the whole block - the mistake that a hard-coded start address would
 * hide.
 */
public class KostalChargerImplTest {

	/**
	 * Encodes floats the way the inverter delivers them: CDAB, i.e. low word first.
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
	 * The ten registers of one PV input: current, power, an undocumented gap of
	 * four, then voltage.
	 *
	 * @param current the current in [A]
	 * @param power   the power in [W]
	 * @param voltage the voltage in [V]
	 * @return the register words
	 */
	private static int[] block(float current, float power, float voltage) {
		var words = new int[10];
		System.arraycopy(cdab(current, power), 0, words, 0, 4);
		System.arraycopy(cdab(voltage), 0, words, 8, 2);
		return words;
	}

	// Values close to a real reading on 2026-09-04, rounded to figures that are
	// exact in Float32 so the assertions do not depend on rounding behaviour.
	private static DummyModbusBridge bridge() {
		return new DummyModbusBridge("modbus0") //
				.withRegisters(258, block(10.625f, 6170.0f, 580.5f)) // PV 1
				.withRegisters(268, block(5.25f, 2784.0f, 530.25f)) // PV 2
				// deliberately served, to prove they are not read
				.withRegisters(1058, cdab(12345000.0f)) // lifetime energy PV 1
				.withRegisters(1060, cdab(6789000.0f)); // lifetime energy PV 2
	}

	private static KostalChargerImpl activate(PvPort pvPort) throws Exception {
		var charger = new KostalChargerImpl();
		new ComponentTest(charger) //
				.addReference("setModbus", bridge()) //
				.activate(MyConfig.create() //
						.setId("charger0") //
						.setPvPort(pvPort) //
						.setModbusId("modbus0") //
						.setModbusUnitId(71) //
						.build()) //
				// enough Cycles for the LOW priority energy task to have come round
				.next(new TestCase(), 20);
		return charger;
	}

	@Test
	public void testPv1() throws Exception {
		var charger = activate(PvPort.PV_1);

		assertEquals(Integer.valueOf(6170), charger.getActualPower().get());
		assertEquals(Integer.valueOf(10625), charger.getCurrent().get(), "A must be scaled to mA");
		assertEquals(Integer.valueOf(580500), charger.getVoltage().get(), "V must be scaled to mV");
		// The lifetime counter IS preloaded on the bridge, and must NOT be picked up:
		// the energy is integrated from the power instead, so that switching an existing
		// system over to DC chargers does not add the inverter's whole history to
		// _sum/ProductionActiveEnergy in a single Cycle.
		assertNull(charger.getActualEnergy().get());
	}

	@Test
	public void testPv2ReadsItsOwnBlock() throws Exception {
		var charger = activate(PvPort.PV_2);

		assertEquals(Integer.valueOf(2784), charger.getActualPower().get());
		assertEquals(Integer.valueOf(5250), charger.getCurrent().get());
		assertEquals(Integer.valueOf(530250), charger.getVoltage().get());
		assertNull(charger.getActualEnergy().get());
	}

	@Test
	public void testMaxActualPowerIsDerived() throws Exception {
		var charger = activate(PvPort.PV_1);

		// EssDcCharger derives this from ACTUAL_POWER on its own: a running maximum,
		// rounded away from zero to the next 100 W so the channel is not rewritten on
		// every small increase. 6170 W therefore becomes 6200 W. The assertion is here
		// to prove the derivation runs at all, i.e. that the constructor registered the
		// EssDcCharger channels.
		assertEquals(Integer.valueOf(6200),
				charger.channel(EssDcCharger.ChannelId.MAX_ACTUAL_POWER).value().get());
	}
}
