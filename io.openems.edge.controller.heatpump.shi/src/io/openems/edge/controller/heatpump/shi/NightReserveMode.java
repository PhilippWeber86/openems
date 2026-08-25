package io.openems.edge.controller.heatpump.shi;

/**
 * How the night reserve - the battery energy kept back for the household - is
 * calculated from the forecast.
 */
public enum NightReserveMode {
	/**
	 * Reserve = largest cumulative household deficit (consumption minus heat-pump
	 * prediction minus production, floored at 0) over the horizon. Simple and
	 * safe, but does NOT credit that PV will recharge the battery during the day,
	 * so it is overly conservative at low morning SoC (it reserves the evening
	 * hole now, even though the day would refill the battery first).
	 */
	MAX_DEFICIT,
	/**
	 * Reserve is derived from a forward SoC trajectory: the battery energy is
	 * simulated over the horizon (charged by forecast PV surplus up to the
	 * capacity, discharged by the household deficit) and the free energy is the
	 * most that can be removed now while the trajectory never drops below Min-SoC.
	 * Credits the daytime recharge, so it unlocks morning use on a sunny forecast.
	 * More aggressive - only reliable with a weather-based production predictor.
	 */
	SOC_TRAJECTORY;
}
