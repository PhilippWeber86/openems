package io.openems.edge.kostal.plenticore.enums;

/**
 * Why SMART mode hands the battery back to the inverter's internal 'AUTO'
 * regulation.
 *
 * <p>
 * The hand-over happens by NOT writing a set-point: the inverter's control
 * timeout then expires on its own. There is no register to write, so the reason
 * exists purely to make the decision reviewable - in the log and in a test.
 */
public enum ReleaseReason {

	/**
	 * Nothing is being asked of the battery at all, and no Controller is holding a
	 * direction shut.
	 */
	IDLE_ZONE("idle zone"),

	/**
	 * The set-point is plain balancing to zero, which is exactly what the
	 * inverter's internal regulation does on its own - and does faster, because it
	 * runs on the device instead of on a Cycle.
	 */
	MATCHES_BALANCING("set-point matches plain balancing to zero"),

	/**
	 * The set-point deviates only because a charge cap binds, and that cap has been
	 * handed to the inverter, so it enforces it while regulating itself.
	 */
	AT_CHARGE_LIMIT("at the charge limit, which the inverter enforces itself");

	private final String description;

	private ReleaseReason(String description) {
		this.description = description;
	}

	/**
	 * Gets a human-readable description for the log.
	 *
	 * @return the description
	 */
	public String getDescription() {
		return this.description;
	}
}
