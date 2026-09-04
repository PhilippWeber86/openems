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

	PV_1(258, 1058), //
	PV_2(268, 1060), //
	PV_3(278, 1062); //

	/**
	 * Address of the current register; power sits at +2, voltage at +8.
	 */
	public final int baseAddress;

	/**
	 * Address of the lifetime DC energy counter of this input.
	 */
	public final int energyAddress;

	private PvPort(int baseAddress, int energyAddress) {
		this.baseAddress = baseAddress;
		this.energyAddress = energyAddress;
	}
}
