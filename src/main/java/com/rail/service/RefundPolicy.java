package com.rail.service;

import com.rail.model.FareEstimate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * The cancellation and refund rules this project simulates.
 *
 * <p><strong>These are not IRCTC rules.</strong> Nothing here is an official
 * refund policy, a real fare rule, or anything a passenger could rely on at a
 * counter. They are this project's own rules, invented so that cancellation has
 * a defined, testable, honest outcome, and they are documented in README section
 * 14 so nobody has to read the code to know what they will get.
 *
 * <p>The policy in one line: cancel at least {@link #CUTOFF_DAYS} days before
 * departure and the fare comes back less a flat cancellation charge per
 * passenger; cancel later than that and half the fare comes back; cancel after
 * departure and nothing is refundable at all.
 *
 * <p>Every figure is {@link BigDecimal} at {@link FareEstimate#SCALE}, rounded
 * half-up once at the end, so a refund is either exactly a whole number of
 * rupees or exactly half of one - never a third of a paisa, and never a figure
 * that does not reconcile against the fare printed on the ticket.
 *
 * <p>The thresholds are readable from system properties or the environment, so
 * the policy can be retuned without a rebuild. An unusable value falls back to
 * the default rather than being silently accepted.
 */
public final class RefundPolicy {

    /** System property that overrides {@link #CUTOFF_DAYS}. */
    public static final String PROP_CUTOFF_DAYS = "cancellation.cutoff.days";

    /** Environment variable that overrides {@link #CUTOFF_DAYS}. */
    public static final String ENV_CUTOFF_DAYS = "RAIL_CANCELLATION_CUTOFF_DAYS";

    /** System property that overrides {@link #LATE_REFUND_PERCENT}. */
    public static final String PROP_LATE_PERCENT = "cancellation.late.refund.percent";

    /** Environment variable that overrides {@link #LATE_REFUND_PERCENT}. */
    public static final String ENV_LATE_PERCENT = "RAIL_CANCELLATION_LATE_PERCENT";

    /**
     * How many days before departure the cheap rate ends.
     *
     * <p>{@code 7}: cancel a week ahead or more and only the charge is kept.
     */
    public static final int CUTOFF_DAYS = 7;

    /** Flat charge kept per passenger when cancelling inside the cutoff. */
    public static final BigDecimal CHARGE_PER_PASSENGER = new BigDecimal("50.00");

    /** Share of the fare refunded when cancelling inside the cutoff. */
    public static final BigDecimal LATE_REFUND_PERCENT = new BigDecimal("50");

    private RefundPolicy() {
    }

    /**
     * What cancelling this booking would return, and what it would cost.
     *
     * <p>Pure: it reads no clock of its own and touches no database, so the
     * policy can be checked on its own and the service stays a transaction.
     *
     * @param totalFare the fare actually charged, not the current estimate
     * @param passengers the party size, which is what the charge is per
     * @param journeyDate the date of departure
     * @param today the date the cancellation is being made on
     * @return the refund, the charges kept, and whether anything is refundable
     */
    public static Outcome refundFor(BigDecimal totalFare, int passengers,
                                    LocalDate journeyDate, LocalDate today) {
        BigDecimal fare = money(totalFare);
        int cutoff = readInt(PROP_CUTOFF_DAYS, ENV_CUTOFF_DAYS, CUTOFF_DAYS, 0, 365);
        long days = ChronoUnit.DAYS.between(today, journeyDate);

        if (days < 0) {
            // Already departed. Nothing is refundable and the booking's history
            // still records that a cancellation was asked for and why it paid out
            // nothing.
            return new Outcome(false, money(BigDecimal.ZERO), money(fare),
                    REFUND_STATUS_NOT_APPLICABLE,
                    "The journey has already departed, so no refund is due.");
        }

        if (days >= cutoff) {
            BigDecimal charges = money(CHARGE_PER_PASSENGER.multiply(
                    BigDecimal.valueOf(Math.max(1, passengers)))
                    // Never more than was paid: a cancellation charge that exceeds
                    // the fare would be a refund of nothing and a charge for
                    // something that was never bought.
                    .min(fare));
            BigDecimal refund = money(fare.subtract(charges));
return new Outcome(true, refund, charges, REFUND_STATUS_PROCESSED,
                    refund.compareTo(BigDecimal.ZERO) == 0
                            ? "The cancellation charge covers the whole fare, so there is "
                                    + "nothing left to refund."
                            : "Cancelled " + days + " days before departure. The cancellation "
                                    + "charge is kept and the rest is refunded.");
        }

        BigDecimal percent = BigDecimal.valueOf(readInt(PROP_LATE_PERCENT, ENV_LATE_PERCENT,
                LATE_REFUND_PERCENT.intValueExact(), 0, 100));
        BigDecimal refund = money(fare.multiply(percent)
                .divide(BigDecimal.valueOf(100), FareEstimate.SCALE, RoundingMode.HALF_UP));
        BigDecimal charges = money(fare.subtract(refund));
        return new Outcome(true, refund, charges, REFUND_STATUS_PROCESSED,
                "Cancelled " + days + " days before departure, inside the " + cutoff
                        + "-day cutoff, so only " + percent + "% of the fare is refunded.");
    }

    /**
     * One line a traveller can read before confirming.
     *
     * @param days until departure, negative if it has passed
     */
    public static String policySentence(long days) {
        int cutoff = readInt(PROP_CUTOFF_DAYS, ENV_CUTOFF_DAYS, CUTOFF_DAYS, 0, 365);
        if (days < 0) {
            return "Your journey has departed, so cancelling now is recorded but refunds "
                    + "nothing.";
        }
        if (days >= cutoff) {
            return days + " days until departure. Cancel now and "
                    + CHARGE_PER_PASSENGER + " per passenger is kept as a cancellation "
                    + "charge; the rest of the fare is refunded.";
        }
        return days + " days until departure, inside the " + cutoff + "-day cutoff. "
                + "Only " + LATE_REFUND_PERCENT + "% of the fare is refunded. This is a "
                + "simulated policy set by this project, not an official IRCTC refund rule.";
    }

    /** The refund status stored for a cancellation that paid nothing. */
    public static final String REFUND_STATUS_NOT_APPLICABLE = "NOT_APPLICABLE";

    /** The refund status stored for a cancellation that paid out. */
    public static final String REFUND_STATUS_PROCESSED = "PROCESSED";

    private static BigDecimal money(BigDecimal amount) {
        return amount.setScale(FareEstimate.SCALE, RoundingMode.HALF_UP);
    }

    /**
     * Reads one threshold, falling back when it is missing or unusable.
     *
     * <p>Failing closed here would mean refunding nothing ever, which is just as
     * wrong as refunding everything, so an unparseable value takes the default.
     */
    private static int readInt(String property, String environment, int fallback,
                               int low, int high) {
        String configured = firstNonBlank(System.getProperty(property),
                System.getenv(environment));
        if (configured == null) {
            return fallback;
        }
        try {
            int value = Integer.parseInt(configured.trim());
            return value >= low && value <= high ? value : fallback;
        } catch (NumberFormatException notANumber) {
            return fallback;
        }
    }

    private static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * The money a cancellation produces.
     *
     * @param refundable false for a journey that has already departed, which
     *     still gets a cancellation row, just with no money in it
     * @param refundAmount what comes back to the traveller
     * @param charges what is kept: the cancellation charge, or the part of the
     *     fare that is not refunded
     * @param refundStatus the value stored in {@code cancellations.refund_status}
     * @param explanation one line for the confirmation dialog
     */
    public record Outcome(boolean refundable,
                          BigDecimal refundAmount,
                          BigDecimal charges,
                          String refundStatus,
                          String explanation) {
    }
}