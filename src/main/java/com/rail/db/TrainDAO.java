package com.rail.db;

import com.rail.model.BerthPreference;
import com.rail.model.ClassProfile;
import com.rail.model.Station;
import com.rail.model.StopInfo;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Read-mostly data access for the train search, plus the one write the search
 * needs: materialising a {@code journeys} row so availability has something to
 * count.
 *
 * <p>Every statement binds its parameters. Nothing here composes SQL from
 * user input.
 */
public class TrainDAO {

    private final ConnectionSource connections;

    public TrainDAO(ConnectionSource connections) {
        this.connections = connections;
    }

    // ---------------------------------------------------------------- stations

    /** Every station, alphabetically by name, for the search form's dropdowns. */
    public List<Station> listStations() throws SQLException {
        String sql = "SELECT station_code, station_name FROM stations ORDER BY station_name";
        List<Station> stations = new ArrayList<>();
        try (Connection c = connections.open();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                stations.add(new Station(rs.getString("station_code"),
                                         rs.getString("station_name")));
            }
        }
        return stations;
    }

    // ---------------------------------------------------------------- search

    /**
     * The geometry of one train over the requested segment: which two stops,
     * what times, and how far.
     *
     * <p>{@code d.stop_seq > o.stop_seq} is the whole ordering rule. A train
     * visits each station at most once ({@code uq_stop_station}), so the join
     * cannot fan out into duplicate rows.
     *
     * <p>Only trains that are active, run daily, and actually have a coach of
     * the requested class are returned. A train with {@code runs_daily = FALSE}
     * is deliberately excluded: the schema records no weekly running pattern,
     * so there is no honest way to claim such a train runs on a given date.
     */
    public List<RouteMatch> findRouteMatches(String fromCode, String toCode,
                                             String classCode) throws SQLException {
        String sql =
                "SELECT t.train_no, t.train_name, "
                + "       o.stop_seq AS from_seq, d.stop_seq AS to_seq, "
                + "       o.departure_time, d.arrival_time, "
                + "       d.day_offset AS arrival_day_offset, "
                + "       (d.distance_km - o.distance_km) AS segment_km "
                + "FROM trains t "
                + "JOIN train_stops o ON o.train_no = t.train_no AND o.station_code = ? "
                + "JOIN train_stops d ON d.train_no = t.train_no AND d.station_code = ? "
                + "                  AND d.stop_seq > o.stop_seq "
                + "WHERE t.is_active = TRUE AND t.runs_daily = TRUE "
                + "  AND EXISTS (SELECT 1 FROM coaches c "
                + "              WHERE c.train_no = t.train_no AND c.class_code = ?) "
                + "ORDER BY o.departure_time, t.train_no";
        List<RouteMatch> matches = new ArrayList<>();
        try (Connection c = connections.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, fromCode);
            ps.setString(2, toCode);
            ps.setString(3, classCode);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    LocalTime arrival = rs.getTime("arrival_time") == null
                            ? null : rs.getTime("arrival_time").toLocalTime();
                    matches.add(new RouteMatch(
                            rs.getString("train_no"),
                            rs.getString("train_name"),
                            rs.getInt("from_seq"),
                            rs.getInt("to_seq"),
                            rs.getTime("departure_time").toLocalTime(),
                            arrival,
                            rs.getInt("arrival_day_offset"),
                            rs.getInt("segment_km")));
                }
            }
        }
        return matches;
    }

    /**
     * The whole timetable of a train, in route order.
     *
     * <p>{@code day_offset} comes straight from the row, so the caller can show
     * an overnight arrival as the following day without computing anything.
     */
    public List<StopInfo> listStops(String trainNo) throws SQLException {
        String sql =
                "SELECT s.stop_seq, s.station_code, st.station_name, "
                + "       s.arrival_time, s.departure_time, s.day_offset, s.distance_km "
                + "FROM train_stops s "
                + "JOIN stations st ON st.station_code = s.station_code "
                + "WHERE s.train_no = ? ORDER BY s.stop_seq";
        List<StopInfo> stops = new ArrayList<>();
        try (Connection c = connections.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, trainNo);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    stops.add(new StopInfo(
                            rs.getInt("stop_seq"),
                            rs.getString("station_code"),
                            rs.getString("station_name"),
                            toLocalTime(rs.getTime("arrival_time")),
                            rs.getTime("departure_time").toLocalTime(),
                            rs.getInt("day_offset"),
                            rs.getInt("distance_km")));
                }
            }
        }
        return stops;
    }

    // ---------------------------------------------------------------- classes

    /** The classes a train has coaches for, in schema display order. */
    public List<ClassRow> listTrainClasses(String trainNo) throws SQLException {
        String sql =
                "SELECT DISTINCT tc.class_code, tc.class_name, tc.class_order "
                + "FROM coaches c "
                + "JOIN travel_classes tc ON tc.class_code = c.class_code "
                + "WHERE c.train_no = ? ORDER BY tc.class_order";
        List<ClassRow> rows = new ArrayList<>();
        try (Connection c = connections.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, trainNo);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new ClassRow(rs.getString("class_code"),
                                          rs.getString("class_name")));
                }
            }
        }
        return rows;
    }

    /** Every class in the system, for the search form's class dropdown. */
    public List<ClassRow> listAllClasses() throws SQLException {
        String sql = "SELECT class_code, class_name FROM travel_classes ORDER BY class_order";
        List<ClassRow> rows = new ArrayList<>();
        try (Connection c = connections.open();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                rows.add(new ClassRow(rs.getString("class_code"),
                                      rs.getString("class_name")));
            }
        }
        return rows;
    }

    // ---------------------------------------------------------------- journeys

    /**
     * Ensures a {@code journeys} row and its rake exist for this train and
     * date, and returns it.
     *
     * <p>This is the one write in the search path, and it is idempotent:
     * {@code INSERT IGNORE} against the {@code (train_no, journey_date)} unique
     * key means a second search reuses the row instead of creating a rival.
     *
     * <p>The transaction is managed only when this DAO opened the connection in
     * autocommit mode. If the caller handed us a connection that is already
     * inside its transaction - the self-check does exactly that - committing or
     * rolling back here would silently break the caller's atomicity.
     *
     * <p>{@code journey_date} is always the departure date, even for an
     * overnight train, which is what the schema comment specifies.
     */
    public JourneyRef ensureJourney(String trainNo, LocalDate journeyDate) throws SQLException {
        try (Connection c = connections.open()) {
            boolean manageTransaction = c.getAutoCommit();
            if (manageTransaction) {
                c.setAutoCommit(false);
            }
            try {
                try (PreparedStatement insert = c.prepareStatement(
                        "INSERT IGNORE INTO journeys (train_no, journey_date) VALUES (?, ?)")) {
                    insert.setString(1, trainNo);
                    insert.setDate(2, java.sql.Date.valueOf(journeyDate));
                    insert.executeUpdate();
                }
                long journeyId;
                String serviceStatus;
                try (PreparedStatement select = c.prepareStatement(
                        "SELECT journey_id, service_status FROM journeys "
                        + "WHERE train_no = ? AND journey_date = ?")) {
                    select.setString(1, trainNo);
                    select.setDate(2, java.sql.Date.valueOf(journeyDate));
                    try (ResultSet rs = select.executeQuery()) {
                        if (!rs.next()) {
                            throw new SQLException(
                                    "Journey for train " + trainNo + " on " + journeyDate + " vanished");
                        }
                        journeyId = rs.getLong("journey_id");
                        serviceStatus = rs.getString("service_status");
                    }
                }
                try (PreparedStatement attach = c.prepareStatement(
                        "INSERT IGNORE INTO journey_coaches (journey_id, coach_id, coach_status) "
                        + "SELECT ?, coach_id, 'ATTACHED' FROM coaches WHERE train_no = ?")) {
                    attach.setLong(1, journeyId);
                    attach.setString(2, trainNo);
                    attach.executeUpdate();
                }
                if (manageTransaction) {
                    c.commit();
                }
                return new JourneyRef(journeyId, serviceStatus);
            } catch (SQLException | RuntimeException failure) {
                if (manageTransaction) {
                    c.rollback();
                }
                throw failure;
            }
        }
    }

    // ---------------------------------------------------------------- fares

    /**
     * The fare rule for a class over one segment, resolved most-specific-wins
     * from {@code fare_rules}.
     *
     * <p>Specificity is ordered route-and-distance, then route, then origin,
     * then class default, and the most recent {@code valid_from} breaks ties.
     * {@code valid_from} / {@code valid_to} let a fare revision apply from a
     * date without rewriting past bookings.
     *
     * <p>The charged distance is always the distance actually travelled, never
     * the distance a rule happens to be pinned to: a rule pinned to 150 km
     * still charges per km over whatever segment was searched.
     *
     * @return the rule, or empty when no rule applies to this class and date
     */
    public Optional<FareRule> fareRule(String classCode, String fromCode,
                                       String toCode, int segmentKm,
                                       LocalDate journeyDate) throws SQLException {
        String sql =
                "SELECT base_fare, per_km_fare FROM fare_rules "
                + "WHERE class_code = ? "
                + "  AND valid_from <= ? "
                + "  AND (valid_to IS NULL OR valid_to >= ?) "
                + "  AND (origin_station_code IS NULL OR origin_station_code = ?) "
                + "  AND (destination_station_code IS NULL OR destination_station_code = ?) "
                + "  AND (distance_km IS NULL OR distance_km = ?) "
                + "ORDER BY (origin_station_code IS NOT NULL) DESC, "
                + "         (destination_station_code IS NOT NULL) DESC, "
                + "         (distance_km IS NOT NULL) DESC, "
                + "         valid_from DESC "
                + "LIMIT 1";
        try (Connection c = connections.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, classCode);
            ps.setDate(2, java.sql.Date.valueOf(journeyDate));
            ps.setDate(3, java.sql.Date.valueOf(journeyDate));
            ps.setString(4, fromCode);
            ps.setString(5, toCode);
            ps.setInt(6, segmentKm);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new FareRule(
                        rs.getBigDecimal("base_fare"),
                        rs.getBigDecimal("per_km_fare"),
                        segmentKm));
            }
        }
    }

    /**
     * The resolved fare rule's two halves, so a caller can show how a fare was
     * reached instead of only its result.
     *
     * @param chargedDistanceKm the distance actually billed, which equals the
     *     segment searched: a pinned rule only matches when its distance is the
     *     same, so the two can never disagree
     */
    public record FareRule(BigDecimal baseFare, BigDecimal perKmFare, int chargedDistanceKm) {

        /** Base plus distance, to two decimal places, for one passenger. */
        public BigDecimal fareForOnePassenger() {
            return baseFare.add(perKmFare.multiply(BigDecimal.valueOf(chargedDistanceKm)))
                    .setScale(2, RoundingMode.HALF_UP);
        }
    }

    private static LocalTime toLocalTime(java.sql.Time time) {
        return time == null ? null : time.toLocalTime();
    }

    /**
     * Convenience for callers that only need the number, such as search results.
     *
     * <p>Delegates to {@link #fareRule} so there is exactly one fare query.
     */
    public Optional<BigDecimal> farePerPassenger(String classCode, String fromCode,
                                                  String toCode, int segmentKm,
                                                  LocalDate journeyDate) throws SQLException {
        return fareRule(classCode, fromCode, toCode, segmentKm, journeyDate)
                .map(FareRule::fareForOnePassenger);
    }

    // ---------------------------------------------------------------- class profile

    /**
     * What a class can offer: whether it is a sleeper, whether it has a pantry,
     * and which berth positions its coaches actually contain.
     *
     * <p>The positions are read from {@code berths} rather than inferred from
     * {@code is_sleeper}, because the two disagree. AC 2-Tier is flagged a
     * sleeper class but has no middle berths, so a preference list derived from
     * the flag alone would offer something that cannot be booked.
     *
     * @return the profile, or empty when the class code is unknown
     */
    public Optional<ClassProfile> classProfile(String classCode) throws SQLException {
        String sql =
                "SELECT tc.class_code, tc.class_name, tc.is_sleeper, tc.has_pantry, b.position "
                + "FROM travel_classes tc "
                + "JOIN coaches c ON c.class_code = tc.class_code "
                + "JOIN berths b ON b.coach_id = c.coach_id "
                + "WHERE tc.class_code = ? "
                + "GROUP BY tc.class_code, tc.class_name, tc.is_sleeper, tc.has_pantry, b.position "
                + "ORDER BY FIELD(b.position, 'LOWER', 'MIDDLE', 'UPPER', 'SIDE', 'WINDOW', 'AISLE')";
        List<BerthPreference> preferences = new ArrayList<>();
        String name = null;
        boolean sleeper = false;
        boolean pantry = false;
        boolean found = false;
        try (Connection c = connections.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, classCode);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    found = true;
                    name = rs.getString("class_name");
                    sleeper = rs.getBoolean("is_sleeper");
                    pantry = rs.getBoolean("has_pantry");
                    preferences.addAll(BerthPreference.forPosition(rs.getString("position")));
                }
            }
        }
        if (!found) {
            return Optional.empty();
        }
        return Optional.of(new ClassProfile(classCode, name, sleeper, pantry, preferences));
    }

    // ---------------------------------------------------------------- records

    /**
     * Where a train's route sits relative to the requested segment, before
     * fares and availability are attached.
     *
     * @param arrivalTime non-null: the destination cannot be the originating
     *                    stop, and only the origin may omit an arrival time
     */
    public record RouteMatch(
            String trainNo,
            String trainName,
            int fromSeq,
            int toSeq,
            LocalTime departureTime,
            LocalTime arrivalTime,
            int arrivalDayOffset,
            int segmentKm) {
    }

    /** A class the train serves. */
    public record ClassRow(String classCode, String className) {
    }

    /**
     * A materialised journey.
     *
     * @param serviceStatus {@code SCHEDULED}, {@code CANCELLED} or {@code FULL}
     */
    public record JourneyRef(long journeyId, String serviceStatus) {
        public boolean isScheduled() {
            return "SCHEDULED".equals(serviceStatus);
        }
    }

    /**
     * {@code BerthCounts} moved to {@link AvailabilityDAO} with the availability
     * queries it belongs to. It is no longer declared here: one type, one home,
     * so the search screen and the booking engine cannot disagree about how many
     * berths are free.
     */
}