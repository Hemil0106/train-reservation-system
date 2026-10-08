package com.rail.model;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * What the party will cost, kept as a breakdown rather than one number.
 *
 * <p>Every figure is an <em>estimate</em> of a <em>simulated</em> fare. It
 * excludes what this project cannot honestly price: concession fares, catering
 * charges, quota or tatkal premiums, GST and refunds. The components stay
 * separate so the summary can show how the total was reached instead of asking
 * a passenger to trust it.
 *
 * <p>Money is {@link BigDecimal} with a scale of two, matching the
 * {@code DECIMAL(12,2)} columns the total and each reservation amount are
 * stored in. {@code double} is deliberately not used anywhere in this path: it
 * cannot represent 0.1 exactly, so a total built from it can end up a paisa
 * away from the sum of its parts, which is exactly the sort of thing a
 * passenger notices when it disagrees with a printed ticket.
 *
 * <p>Each component is rounded once, at the point it is computed, so
 * {@link #perPassenger()} is exactly {@code baseFare + distanceFare} and
 * {@link #total()} is exactly {@code perPassenger * passengers}. Rounding only
 * at the end would let the summary display figures that do not add up.
 *
 * @param baseFare the flat part of the fare rule
 * @param distanceFare the distance part, already multiplied out
 * @param distanceKm the distance charged, shown so the rate can be checked
 * @param passengers the party size this estimate covers
 */
public record FareEstimate(BigDecimal baseFare,
                           BigDecimal distanceFare,
                           int distanceKm,
                           int passengers) {

    /** Two decimal places, the scale every money figure in this project uses. */
    public static final int SCALE = 2;

    /** Base plus distance, for one traveller. */
    public BigDecimal perPassenger() {
        return money(baseFare.add(distanceFare));
    }

    /** The whole party, at the per-passenger rate. */
    public BigDecimal total() {
        return money(perPassenger().multiply(BigDecimal.valueOf(passengers)));
    }

    /** Rounds to the storage scale. Half-up, matching MySQL's DECIMAL rounding. */
    private static BigDecimal money(BigDecimal amount) {
        return amount.setScale(SCALE, RoundingMode.HALF_UP);
    }
}