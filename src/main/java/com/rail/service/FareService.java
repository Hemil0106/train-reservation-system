package com.rail.service;

import com.rail.db.TrainDAO;
import com.rail.db.TrainDAO.FareRule;
import com.rail.model.FareEstimate;
import com.rail.model.TrainSelection;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.Optional;

/**
 * Prices a booking, and is the only place a fare is turned into money.
 *
 * <p>Fares are not hardcoded per train or route. They come from the
 * {@code fare_rules} table, resolved most-specific-wins: an exact
 * origin-destination pair beats a route, which beats an origin, which beats the
 * class default, with the most recent {@code valid_from} breaking ties. So
 * CSTM to PUNE in 2A is priced by its own row (₹85 base + ₹3.90/km) while the
 * same class elsewhere falls back to the class default - which is what
 * {@link TrainDAO#fareRule} has always done for search results, so a quoted fare
 * and a charged fare come from one rule rather than two implementations that
 * could disagree.
 *
 * <p><b>These fares are simulated.</b> They are plausible figures derived from
 * configurable rules, not official railway pricing, and they exclude concession
 * fares, catering, quota and tatkal premiums, GST, and refunds. The application
 * says so wherever a fare is shown; see {@link #SIMULATED_NOTICE}.
 *
 * <h2>Why the fare is re-read at booking time</h2>
 *
 * <p>The passenger screen priced the party while the traveller typed, and that
 * figure is shown on the confirmation. Between then and the commit, a fare rule
 * could have been edited, so the engine re-reads the rule and re-prices inside
 * the transaction. What is charged is always the rule as it stands at the moment
 * of booking. The search screen's figure is the passenger's expectation; this
 * one is the charge.
 *
 * <p>{@link BigDecimal} throughout, at the scale of the {@code DECIMAL(12,2)}
 * columns the money lands in. Never {@code double}: 0.1 is not representable in
 * binary floating point, so a total built from doubles can end up a paisa out of
 * step with the sum of its parts - the sort of discrepancy a passenger notices
 * on a printed ticket.
 */
public final class FareService {

    /**
     * Shown wherever a fare appears. Not decoration: a passenger must not
     * mistake a configured rule for an official tariff.
     */
    public static final String SIMULATED_NOTICE =
            "Simulated fare from configurable rules - not official railway pricing. "
                    + "Excludes concession fares, catering, quota and tatkal premiums, GST and refunds.";

    private final TrainDAO trains;

    public FareService() {
        this(new TrainDAO(com.rail.db.DatabaseConnection::getConnection));
    }

    /** See {@link AuthService#AuthService(com.rail.db.ConnectionSource)}. */
    public FareService(TrainDAO trains) {
        this.trains = trains;
    }

    /**
     * The fare rule for a selection, or empty when none applies.
     *
     * <p>Empty is a real outcome, not an error to swallow: a class with no
     * configured fare must not be bookable, and the caller decides how to say so.
     */
    public Optional<FareRule> ruleFor(TrainSelection selection) throws SQLException {
        return trains.fareRule(
                selection.classCode(),
                selection.fromStation().code(),
                selection.toStation().code(),
                selection.segmentKm(),
                selection.journeyDate());
    }

    /**
     * The rule for a selection, or a refusal naming the class and route.
     *
     * <p>A {@link ValidationException} rather than a {@code null}, so a missing
     * fare cannot be mistaken for a zero fare. A zero would look free and be
     * worse than a failure.
     */
    public FareRule requireRuleFor(TrainSelection selection)
            throws ValidationException, SQLException {
        return ruleFor(selection).orElseThrow(() -> new ValidationException(
                "class",
                "No fare is configured for " + selection.className() + " on this route."));
    }

    /**
     * Prices a party from an already-loaded rule.
     *
     * <p>Pure, so it can run on the event dispatch thread and be asserted
     * directly in a self-check without a database.
     *
     * <p>Each component is rounded once at scale two, so the base and distance
     * figures add up to the per-passenger figure, and that multiplies up to the
     * total. Rounding only the total would let the confirmation show a
     * breakdown that does not add up to its own answer.
     */
    public FareEstimate quote(FareRule rule, int passengers) {
        if (passengers < 1) {
            throw new IllegalArgumentException("a fare quote needs at least one passenger");
        }
        BigDecimal base = rule.baseFare().setScale(FareEstimate.SCALE, java.math.RoundingMode.HALF_UP);
        BigDecimal distance = rule.perKmFare()
                .multiply(BigDecimal.valueOf(rule.chargedDistanceKm()))
                .setScale(FareEstimate.SCALE, java.math.RoundingMode.HALF_UP);
        return new FareEstimate(base, distance, rule.chargedDistanceKm(), passengers);
    }

    /**
     * Loads the rule and prices the party in one step.
     *
     * <p>This is the call the booking engine makes, so that re-reading and
     * re-pricing cannot be forgotten by a future caller.
     */
    public FareEstimate quoteFor(TrainSelection selection)
            throws ValidationException, SQLException {
        return quote(requireRuleFor(selection), selection.passengers());
    }

    /**
     * One passenger's share of the party total.
     *
     * <p>Each reservation row stores the amount charged for the passenger in it,
     * so the sum of the {@code seat_reservations} amounts equals
     * {@code bookings.total_fare}. That equality is what makes a refund or a
     * cancellation arithmetic rather than a guess.
     */
    public BigDecimal perPassengerAmount(FareEstimate fare) {
        return fare.perPassenger();
    }
}