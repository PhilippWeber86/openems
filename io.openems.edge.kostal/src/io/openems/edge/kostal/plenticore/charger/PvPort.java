package io.openems.edge.kostal.plenticore.charger;

/**
 * One PV input (MPP tracker) of a KOSTAL PLENTICORE.
 *
 * <p>
 * Each input has the same three-register layout, only shifted: current at the
 * base address, power two registers later, voltage eight registers later. The
 * four registers in between are undocumented.
 *
 * <p>
 * Addresses from the MODBUS-TCP documentation, section 3.2. Extract that table
 * with {@code pdftotext -table}; with {@code -layout} the descriptions shift
 * against the addresses by one row and yield plausible-looking nonsense.
 */
public enum PvPort {

	PV_1(258), //
	PV_2(268), //
	PV_3(278); //

	/**
	 * Address of the current register; power sits at +2, voltage at +8.
	 */
	public final int baseAddress;

	private PvPort(int baseAddress) {
		this.baseAddress = baseAddress;
	}
}
