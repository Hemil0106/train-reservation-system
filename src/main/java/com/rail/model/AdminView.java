package com.rail.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

/**
 * Every record the administration area exchanges, in one place.
 *
 * <p>Grouped deliberately rather than spread over a dozen files: the admin
 * panel, the service and the self-check must agree on these shapes, and a
 * single file makes that agreement visible. Each concept appears once, as
 * either an <em>input</em> (only the values an administrator types) or a
 * <em>row</em> (a row as the database stores it, plus the counts the
 * overview needs).
 *
 * <p>Inputs are kept separate from rows on purpose. A row carries derived
 * figures - a berth's sold count, a train's booking count - that are read
 * from the database and must never be written back. Mixing the two would
 * mean an UPDATE could carry a count nobody typed.
 *
 * <p>Stops are the one exception: they are read with the existing
 * {@link StopInfo}, which the search path already uses, so the timetable
 * an administrator edits and the timetable a traveller searches are the
 * same type.
 */
public final class AdminView {

    private AdminView() {
        // Namespace for the nested records only.
    }

    /**
     * {@code trains.train_type}, mirrored from schema.sql.
     *
     * <p>Duplicated in Java so the form can refuse an unknown value with a
     * sentence, and the column's own ENUM remains the second layer that
     * actually refuses it. The two are asserted to agree in
     * {@code AdminSelfCheck}.
     */
    public static final List<String> TRAIN_TYPES =
            List.of("SUPERFAST", "EXPRESS", "PASSENGER", "SUBURBAN");

    /** {@code berths.berth_type}, mirrored from schema.sql for the same reason. */
    public static final List<String> BERTH_TYPES =
            List.of("SEAT", "SLEEPER", "AC_BERTH");

    // ---------------------------------------------------------------- overview

    /**
     * The headline figures on the administration overview.
     *
     * @param bookingsByStatus every status present in {@code bookings}, not
     *     just the non-zero ones, so the panel shows what the schema can hold
     */
    public record Summary(int trains,
                          int activeTrains,
                          int stations,
                          int activeStations,
                          int bookings,
                          Map<String, Integer> bookingsByStatus) {
    }

    // ---------------------------------------------------------------- trains

    /** A train as the database stores it, with what is configured for it. */
    public record TrainRow(String trainNo,
                           String trainName,
                           String trainType,
                           boolean runsDaily,
                           Integer distanceKm,
                           boolean active,
                           int stopCount,
                           String classes,
                           int bookings) {
    }

    /**
     * The fields an administrator may set on a train.
     *
     * @param trainNo immutable once created - it is a foreign-key target for
     *     journeys, bookings and reservations, so changing it would orphan
     *     every ticket that names it. New trains supply it; edits must
     *     repeat it unchanged.
     */
    public record TrainInput(String trainNo,
                             String trainName,
                             String trainType,
                             boolean runsDaily,
                             Integer distanceKm) {
    }

    // ---------------------------------------------------------------- stations

    /**
     * A station with what depends on it.
     *
     * @param stopUses how many trains call here
     * @param bookingUses how many bookings name it as boarding or destination.
     *     This is the figure that decides whether a station could ever be
     *     removed; see {@link StationInput} and the README on why removal
     *     is not offered at all.
     */
    public record StationRow(String code,
                             String name,
                             String city,
                             String zone,
                             boolean active,
                             int stopUses,
                             int bookingUses) {
    }

    /**
     * The fields an administrator may set on a station.
     *
     * @param code 2-4 uppercase letters, immutable once created, for the
     *     same reason {@link TrainInput#trainNo()} is
     */
    public record StationInput(String code, String name, String city, String zone) {
    }

    // ---------------------------------------------------------------- stops

    /**
     * One row of a route.
     *
     * @param arrivalTime {@code null} only at the originating stop, per
     *     {@code ck_stops_arrival}
     * @param dayOffset days after the departure date, so an overnight leg is
     *     expressible
     */
    public record StopInput(int stopSeq,
                            String stationCode,
                            LocalTime arrivalTime,
                            LocalTime departureTime,
                            int dayOffset,
                            int distanceKm) {
    }

    // ---------------------------------------------------------------- the rake

    /** A coach, how many berths it has, and how many of them are sold. */
    public record CoachRow(long coachId,
                           String trainNo,
                           String coachNumber,
                           String classCode,
                           String coachType,
                           int position,
                           boolean hasToilet,
                           int berthCount,
                           int soldBerths) {
    }

    /** The fields an administrator may set on a coach. */
    public record CoachInput(String trainNo,
                             String coachNumber,
                             String classCode,
                             String coachType,
                             int position,
                             boolean hasToilet) {
    }

    /** One berth in a coach's layout, and whether anything is sold on it. */
    public record BerthRow(long berthId,
                           long coachId,
                           String coachNumber,
                           int berthNumber,
                           String berthType,
                           String position,
                           int soldCount) {
    }

    /** The fields an administrator may set on a berth. */
    public record BerthInput(long coachId,
                             int berthNumber,
                             String berthType,
                             String position) {
    }

    // ---------------------------------------------------------------- fares

    /**
     * One {@code fare_rules} row.
     *
     * <p>Used for reading and for writing because every component is a real
     * column: there is nothing derived here to write back by mistake.
     *
     * @param ruleId 0 for a rule that does not exist yet
     * @param origin {@code null} = any origin; {@code destination} likewise.
     *     A destination without an origin is refused, per
     *     {@code ck_fare_specificity}.
     */
    public record FareRule(long ruleId,
                           String classCode,
                           String origin,
                           String destination,
                           Integer distanceKm,
                           BigDecimal baseFare,
                           BigDecimal perKmFare,
                           int quotaSeats,
                           int tatkalSeats,
                           LocalDate validFrom,
                           LocalDate validTo) {
    }
}