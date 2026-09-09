package io.openems.edge.controller.heatpump.shi;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.TreeMap;

/**
 * Calculates the household night reserve and the battery energy that may
 * therefore be spent on the heat pump.
 *
 * <p>
 * Deliberately free of any OSGi, channel or clock access: it takes the already
 * time-aligned forecasts plus the battery state and returns the reserve, the
 * spare energy and the REASON the calculation ended where it did. The Controller
 * fetches the forecasts and is the only place that writes channels, which keeps
 * this policy - the part that is easy to get subtly wrong - directly
 * unit-testable and reusable for a future heating-buffer planner.
 *
 * @param mode                   the {@link NightReserveMode}
 * @param minSoc                 battery SoC in % that is never used
 * @param nightReserveBuffer     buffer in % applied per mode
 * @param maxForecastChargePower charge power in W credited to the forecast at
 *                               most; 0 credits no future recharge at all
 * @param chargeEfficiency       battery charge efficiency as a ratio in (0,1]
 * @param dischargeEfficiency    battery discharge efficiency as a ratio in (0,1]
 */
record BatteryReserveCalculator(//
		NightReserveMode mode, //
		int minSoc, //
		int nightReserveBuffer, //
		int maxForecastChargePower, //
		float chargeEfficiency, //
		float dischargeEfficiency) {

	/**
	 * Minimum forecast horizon (quarters) that must be present, gap-free and
	 * starting in the current quarter before any battery energy is released. 96 =
	 * 24 h - enough to cover the night deficit the reserve promises to bridge. A
	 * shorter or later-starting forecast is treated as unavailable (conservative).
	 */
	public static final int REQUIRED_FORECAST_QUARTERS = 96;

	/**
	 * Why a calculation ended where it did. Only {@link #OK} carries a reserve;
	 * every other reason releases nothing, which is the conservative direction.
	 */
	enum Reason {
		/** The reserve was calculated from a complete forecast. */
		OK,
		/** No battery, or nothing usable above Min-SoC - nothing to release. */
		NO_USABLE_ENERGY,
		/** No predictor, or no production forecast at all. */
		FORECAST_UNAVAILABLE,
		/** The consumption forecast has a gap or is too short for the horizon. */
		FORECAST_INCOMPLETE;

		/**
		 * Whether this reason is a forecast problem worth warning about, as opposed to
		 * a battery that simply has nothing to give.
		 *
		 * @return true if the forecast is at fault
		 */
		public boolean isForecastProblem() {
			return this == FORECAST_UNAVAILABLE || this == FORECAST_INCOMPLETE;
		}
	}

	/**
	 * The outcome of one reserve calculation.
	 *
	 * @param reason        why the calculation ended where it did
	 * @param reserveEnergy the household night reserve in Wh, or null if it could
	 *                      not be determined
	 * @param spareEnergy   battery energy in Wh that may be spent on the heat pump
	 */
	record Result(Reason reason, Integer reserveEnergy, int spareEnergy) {

		/**
		 * A calculation that produced no reserve, so nothing is released.
		 *
		 * @param reason why
		 * @return the {@link Result}
		 */
		public static Result of(Reason reason) {
			return new Result(reason, null, 0);
		}
	}

	/**
	 * Battery energy above Min-SoC in Wh; 0 if there is no battery or nothing
	 * usable.
	 *
	 * @param soc      the battery SoC in %
	 * @param capacity the battery capacity in Wh
	 * @return usable energy in Wh (&gt;= 0)
	 */
	public int usableEnergy(int soc, int capacity) {
		if (capacity <= 0) {
			return 0;
		}
		return Math.max(0, Math.round(capacity * (soc - this.minSoc) / 100F));
	}

	/**
	 * Calculates the night reserve and the resulting spare energy.
	 *
	 * <p>
	 * The household consumption forecast is built quarter by quarter over the
	 * horizon. The Unmanaged channel is preferred - managed consumers are planned
	 * by the EMS and do not belong into the household reserve - and where a quarter
	 * is missing there, the plain consumption channel fills in. The plain value
	 * includes those managed consumers and is therefore &gt;= the unmanaged one, so
	 * the substitution is CONSERVATIVE (a slightly too-high household load reserves
	 * a little more, never too little). A quarter missing in BOTH channels means the
	 * forecast cannot promise the night gap-free, so nothing is released. Real
	 * forecast values only, no interpolation; missing PRODUCTION is filled with 0 W,
	 * which is conservative as well.
	 *
	 * <p>
	 * This strictness is intentional and differs from the optional boost forecast
	 * veto, which skips missing quarters and only fires on a quarter that is
	 * actually forecast too weak. The two rules protect opposite things: releasing
	 * battery energy on an incomplete forecast can leave the household short
	 * overnight, while a veto firing on a gap would block a boost the sun could well
	 * have carried.
	 *
	 * @param usableEnergy               battery energy above Min-SoC in Wh, from
	 *                                   {@link #usableEnergy(int, int)}
	 * @param capacity                   battery capacity in Wh
	 * @param firstQuarter               start of the horizon, rounded down to a
	 *                                   quarter
	 * @param remainingHoursFirstQuarter the still-unelapsed fraction of the first
	 *                                   quarter, in hours
	 * @param productions                production forecast per quarter in W
	 * @param unmanagedConsumptions      Unmanaged household forecast per quarter
	 *                                   in W
	 * @param totalConsumptions          plain consumption forecast per quarter in W
	 * @param heatPumps                  heat-pump forecast per quarter in W; empty
	 *                                   unless the heat pump is part of the
	 *                                   consumption measurement
	 * @return the {@link Result}
	 */
	public Result calculate(int usableEnergy, int capacity, Instant firstQuarter, float remainingHoursFirstQuarter,
			Map<Instant, Integer> productions, Map<Instant, Integer> unmanagedConsumptions,
			Map<Instant, Integer> totalConsumptions, Map<Instant, Integer> heatPumps) {
		if (usableEnergy <= 0) {
			return Result.of(Reason.NO_USABLE_ENERGY);
		}
		var consumptions = new TreeMap<Instant, Integer>();
		var quarter = firstQuarter;
		for (var i = 0; i < REQUIRED_FORECAST_QUARTERS; i++) {
			var value = unmanagedConsumptions.get(quarter);
			if (value == null) {
				value = totalConsumptions.get(quarter);
			}
			if (value == null) {
				return Result.of(Reason.FORECAST_INCOMPLETE);
			}
			consumptions.put(quarter, value);
			quarter = quarter.plus(15, ChronoUnit.MINUTES);
		}

		var flows = this.quarterlyBatteryFlows(consumptions, productions, heatPumps, remainingHoursFirstQuarter);
		var deficit = maxCumulativeDeficit(flows);
		// The buffer is applied per mode. MAX_DEFICIT inflates the reserve directly.
		// SOC_TRAJECTORY turns the buffer into a CUSHION (a fraction of the overnight
		// deficit) that the forward SoC trajectory must keep above Min-SoC at all
		// times - applied inside the simulation, not as a multiplier afterwards. This
		// guarantees a real safety margin exactly in the aggressive case where the
		// trajectory would otherwise free everything (reserve 0): if the forecast
		// recharge is too optimistic, the household still has the cushion left.
		var reserveEnergy = switch (this.mode) {
		case MAX_DEFICIT -> Math.round(deficit * this.nightReserveBuffer / 100F);
		case SOC_TRAJECTORY -> {
			var capacityAboveMin = Math.round(capacity * (100 - this.minSoc) / 100F);
			var cushion = Math.round(deficit * Math.max(0, this.nightReserveBuffer - 100) / 100F);
			yield usableEnergy - trajectoryFreeEnergy(usableEnergy, capacityAboveMin, flows, cushion);
		}
		};
		return new Result(Reason.OK, reserveEnergy, Math.max(0, usableEnergy - reserveEnergy));
	}

	/**
	 * Builds the battery net charge per quarter (Wh, positive = charging) over the
	 * consumption forecast horizon: {@code production - household load}, where the
	 * household load is the consumption minus the heat-pump prediction (behind the
	 * meter). Aligned by TIME, not by array index - the dense value arrays shift
	 * when a series has an interior gap, so production and consumption would not
	 * line up per index. Production and heat-pump values are looked up by the same
	 * quarter and default to 0 W where missing (conservative). The first quarter
	 * counts only its remaining fraction.
	 *
	 * <p>
	 * The flows are BATTERY-side energy, so the charge path is limited to the
	 * configured forecast charge power and reduced by the charge efficiency, while
	 * the discharge path is inflated by the discharge efficiency. Both night reserve
	 * modes consume these flows, so both see the same physics: a surplus the battery
	 * cannot absorb is not credited as recharge, and a deficit costs more battery
	 * energy than it delivers to the household. With the charge power at 0 (no
	 * dependable plant limit known) no future recharge is credited at all.
	 * Time-dependent charge restrictions are not modelled - the only one known here
	 * is the battery capacity, which SOC_TRAJECTORY applies on top.
	 *
	 * @param consumptions               the validated household consumption per
	 *                                   quarter, keyed by time (complete, no nulls)
	 * @param productions                production forecast per quarter in W
	 * @param heatPumps                  heat-pump forecast per quarter in W
	 * @param remainingHoursFirstQuarter the still-unelapsed fraction of the first
	 *                                   quarter, in hours
	 * @return the per-quarter battery net charge in Wh
	 */
	private float[] quarterlyBatteryFlows(TreeMap<Instant, Integer> consumptions, Map<Instant, Integer> productions,
			Map<Instant, Integer> heatPumps, float remainingHoursFirstQuarter) {
		var flows = new float[consumptions.size()];
		var i = 0;
		var first = true;
		for (var entry : consumptions.entrySet()) {
			var durationHours = first ? remainingHoursFirstQuarter : 0.25F;
			first = false;
			int consumption = entry.getValue();
			var productionValue = productions.get(entry.getKey());
			var production = productionValue != null ? productionValue : 0;
			var heatPumpValue = heatPumps.get(entry.getKey());
			var heatPump = heatPumpValue != null ? Math.max(0, heatPumpValue) : 0;
			// The heat pump is removed from the consumption forecast, but never beyond
			// zero: the two come from different predictors, so a heat-pump prediction
			// above the total consumption must not turn into extra surplus.
			var householdPower = Math.max(0, consumption - heatPump);
			var netPower = production - householdPower;
			flows[i++] = netPower >= 0 //
					// Charging: only what the plant can actually absorb, times the charge
					// efficiency. Without the power limit an hour of 10 kW surplus is
					// credited as 10 kWh of recharge even on a battery that takes 1 kW.
					? Math.min(netPower, this.maxForecastChargePower) * this.chargeEfficiency * durationHours
					// Discharging: serving the deficit at the AC side costs MORE than the
					// deficit itself, so the reserve has to hold the losses as well.
					: netPower / this.dischargeEfficiency * durationHours;
		}
		return flows;
	}

	/**
	 * MAX_DEFICIT reserve: the largest cumulative household deficit (interim charge
	 * reduces the running deficit, floored at 0) over the horizon. Does not credit
	 * the daytime recharge.
	 *
	 * @param flows the per-quarter battery net charge in Wh
	 * @return required reserve energy in Wh
	 */
	private static int maxCumulativeDeficit(float[] flows) {
		float running = 0;
		float max = 0;
		for (var flow : flows) {
			running -= flow;
			if (running < 0) {
				running = 0;
			}
			if (running > max) {
				max = running;
			}
		}
		return Math.round(max);
	}

	/**
	 * SOC_TRAJECTORY free energy: the most that can be removed from the battery now
	 * so the forward SoC trajectory (charged by the flows, clamped at the top to
	 * the capacity above Min-SoC, NOT clamped at the bottom) never drops below the
	 * cushion above Min-SoC. Because the top clamp lets midday PV refill the
	 * battery, energy removed in the morning is "given back" if the battery would
	 * fill anyway; the cushion is the safety margin the forecast must leave on top.
	 *
	 * @param usableEnergy     current energy above Min-SoC in Wh
	 * @param capacityAboveMin battery capacity above Min-SoC in Wh (top clamp)
	 * @param flows            the per-quarter battery net charge in Wh
	 * @param cushion          safety margin in Wh the trajectory must stay above
	 * @return removable (free) energy in Wh
	 */
	private static int trajectoryFreeEnergy(int usableEnergy, int capacityAboveMin, float[] flows, int cushion) {
		if (trajectoryMinimum(usableEnergy, capacityAboveMin, flows) < cushion) {
			return 0; // forecast does not keep the cushion even if nothing is removed
		}
		var lo = 0;
		var hi = usableEnergy;
		while (hi - lo > 1) {
			var mid = (lo + hi) / 2;
			if (trajectoryMinimum(usableEnergy - mid, capacityAboveMin, flows) >= cushion) {
				lo = mid;
			} else {
				hi = mid;
			}
		}
		return trajectoryMinimum(usableEnergy - hi, capacityAboveMin, flows) >= cushion ? hi : lo;
	}

	private static float trajectoryMinimum(float startEnergy, int capacityAboveMin, float[] flows) {
		var energy = startEnergy;
		var min = startEnergy;
		for (var flow : flows) {
			energy = Math.min(capacityAboveMin, energy + flow);
			if (energy < min) {
				min = energy;
			}
		}
		return min;
	}
}
