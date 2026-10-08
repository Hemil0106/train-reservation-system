package com.rail.db;

import com.rail.model.AdminView;
import com.rail.model.StopInfo;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Time;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Every SQL statement the administration area needs.
 *
 * <p>All of it is bound by {@link PreparedStatement}. Nothing here builds SQL
 * from a value an administrator typed: identifiers cannot be bound, so the
 * only strings composed into a statement are this file's own constants, and
 * every one of them takes its values through {@code setXxx}.
 *
 * <p>The DAO holds a connection it was handed rather than opening one, so the
 * caller owns the transaction. {@link com.rail.service.AdminService} opens it,
 * sets auto-commit off, and commits or rolls back as a unit - a rake that is
 * half configured, or a route whose stops were replaced without their
 * distances, must not be possible.
 *
 * <p>Reads return records; writes return nothing and let a {@link
 * SQLException} speak. Translating a driver's message into something an
 * administrator can act on is the service's job, not this class's.
 */
public final class AdminDAO {

    private final Connection connection;

    public AdminDAO(Connection connection) {
        this.connection = connection;
    }

    // ---------------------------------------------------------------- overview

    /**
     * The headline counts, in one row of one query.
     *
     * <p>Four scalar subqueries rather than four round trips. At this scale the
     * difference is irrelevant; one statement is simply one thing to keep
     * consistent with itself.
     */
    public AdminView.Summary summary() throws SQLException {
        String sql = "SELECT (SELECT COUNT(*) FROM trains) AS trains,"
                + " (SELECT COUNT(*) FROM trains WHERE is_active) AS active_trains,"
                + " (SELECT COUNT(*) FROM stations) AS stations,"
                + " (SELECT COUNT(*) FROM stations WHERE is_active) AS active_stations,"
                + " (SELECT COUNT(*) FROM bookings) AS bookings";
        AdminView.Summary totals;
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet results = statement.executeQuery()) {
            if (!results.next()) {
                throw new SQLException("the overview query returned no row");
            }
            totals = new AdminView.Summary(
                    results.getInt("trains"),
                    results.getInt("active_trains"),
                    results.getInt("stations"),
                    results.getInt("active_stations"),
                    results.getInt("bookings"),
                    Map.of());
        }

        // Grouped separately so a status with no rows still shows as zero
        // rather than vanishing from the panel.
        Map<String, Integer> byStatus = new LinkedHashMap<>();
        for (String status : List.of("CONFIRMED", "PARTIALLY_CANCELLED", "CANCELLED", "WAITLISTED")) {
            byStatus.put(status, 0);
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT status, COUNT(*) AS total FROM bookings GROUP BY status ORDER BY status");
             ResultSet results = statement.executeQuery()) {
            while (results.next()) {
                byStatus.put(results.getString("status"), results.getInt("total"));
            }
        }
        return new AdminView.Summary(totals.trains(), totals.activeTrains(),
                totals.stations(), totals.activeStations(), totals.bookings(), byStatus);
    }

    // ---------------------------------------------------------------- trains

    /**
     * Every train with what is configured for it.
     *
     * <p>{@code classes} is the set of class codes its coaches declare, which is
     * how the schema says a train's supported classes are defined - there is no
     * separate train-to-class table, and inventing one would let a train claim
     * a class it has no berths in.
     */
    public List<AdminView.TrainRow> listTrains() throws SQLException {
        String sql =
                "SELECT t.train_no, t.train_name, t.train_type, t.runs_daily,"
                        + " t.distance_km, t.is_active,"
                        + " (SELECT COUNT(*) FROM train_stops s WHERE s.train_no = t.train_no)"
                        + "   AS stop_count,"
                        + " (SELECT GROUP_CONCAT(DISTINCT c.class_code ORDER BY c.class_code)"
                        + "    FROM coaches c WHERE c.train_no = t.train_no) AS classes,"
                        + " (SELECT COUNT(*) FROM bookings b WHERE b.train_no = t.train_no)"
                        + "   AS bookings"
                        + " FROM trains t ORDER BY t.train_no";
        List<AdminView.TrainRow> rows = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet results = statement.executeQuery()) {
            while (results.next()) {
                int distance = results.getInt("distance_km");
                rows.add(new AdminView.TrainRow(
                        results.getString("train_no"),
                        results.getString("train_name"),
                        results.getString("train_type"),
                        results.getBoolean("runs_daily"),
                        results.wasNull() ? null : distance,
                        results.getBoolean("is_active"),
                        results.getInt("stop_count"),
                        results.getString("classes"),
                        results.getInt("bookings")));
            }
        }
        return rows;
    }

    public Optional<AdminView.TrainRow> findTrain(String trainNo) throws SQLException {
        return listTrains().stream().filter(row -> row.trainNo().equals(trainNo)).findFirst();
    }

    public void insertTrain(AdminView.TrainInput train) throws SQLException {
        String sql = "INSERT INTO trains (train_no, train_name, train_type, runs_daily,"
                + " distance_km) VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, train.trainNo());
            statement.setString(2, train.trainName());
            statement.setString(3, train.trainType());
            statement.setBoolean(4, train.runsDaily());
            bindNullableInt(statement, 5, train.distanceKm());
            statement.executeUpdate();
        }
    }

    public void updateTrain(AdminView.TrainInput train) throws SQLException {
        String sql = "UPDATE trains SET train_name = ?, train_type = ?, runs_daily = ?,"
                + " distance_km = ? WHERE train_no = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, train.trainName());
            statement.setString(2, train.trainType());
            statement.setBoolean(3, train.runsDaily());
            bindNullableInt(statement, 4, train.distanceKm());
            statement.setString(5, train.trainNo());
            statement.executeUpdate();
        }
    }

    /**
     * Deactivates or reactivates a train.
     *
     * <p>The reversible operation, deliberately the only one offered: a train is
     * never deleted, because {@code journeys}, {@code bookings} and
     * {@code reservations} all reference it and a passenger holding a ticket must
     * not be able to lose it to a tidy-up.
     */
    public void setTrainActive(String trainNo, boolean active) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE trains SET is_active = ? WHERE train_no = ?")) {
            statement.setBoolean(1, active);
            statement.setString(2, trainNo);
            statement.executeUpdate();
        }
    }

    // ---------------------------------------------------------------- stations

    /**
     * Every station with what depends on it.
     *
     * <p>{@code bookingUses} counts bookings that name the station as either
     * end of the journey. A station with either figure above zero is one that
     * could never safely be removed, and the panel says so rather than offering
     * a delete that would be refused.
     */
    public List<AdminView.StationRow> listStations() throws SQLException {
        String sql =
                "SELECT s.station_code, s.station_name, s.city, s.zone, s.is_active,"
                        + " (SELECT COUNT(*) FROM train_stops t WHERE t.station_code = s.station_code)"
                        + "   AS stop_uses,"
                        + " (SELECT COUNT(*) FROM bookings b"
                        + "    WHERE b.boarding_station_code = s.station_code"
                        + "       OR b.destination_station_code = s.station_code) AS booking_uses"
                        + " FROM stations s ORDER BY s.station_name";
        List<AdminView.StationRow> rows = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet results = statement.executeQuery()) {
            while (results.next()) {
                rows.add(new AdminView.StationRow(
                        results.getString("station_code"),
                        results.getString("station_name"),
                        results.getString("city"),
                        results.getString("zone"),
                        results.getBoolean("is_active"),
                        results.getInt("stop_uses"),
                        results.getInt("booking_uses")));
            }
        }
        return rows;
    }

    public Optional<AdminView.StationRow> findStation(String code) throws SQLException {
        return listStations().stream().filter(row -> row.code().equals(code)).findFirst();
    }

    public void insertStation(AdminView.StationInput station) throws SQLException {
        String sql = "INSERT INTO stations (station_code, station_name, city, zone)"
                + " VALUES (?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, station.code());
            statement.setString(2, station.name());
            statement.setString(3, station.city());
            bindNullableText(statement, 4, station.zone());
            statement.executeUpdate();
        }
    }

    /** Renames or moves a station. Never renames it: the code is the key. */
    public void updateStation(AdminView.StationInput station) throws SQLException {
        String sql = "UPDATE stations SET station_name = ?, city = ?, zone = ?"
                + " WHERE station_code = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, station.name());
            statement.setString(2, station.city());
            bindNullableText(statement, 3, station.zone());
            statement.setString(4, station.code());
            statement.executeUpdate();
        }
    }

    // ---------------------------------------------------------------- stops

    /**
     * A train's route, in order, with the station names joined in for display.
     */
    public List<StopInfo> listStops(String trainNo) throws SQLException {
        String sql = "SELECT s.stop_seq, s.station_code, st.station_name, s.arrival_time,"
                + " s.departure_time, s.day_offset, s.distance_km"
                + " FROM train_stops s"
                + " JOIN stations st ON st.station_code = s.station_code"
                + " WHERE s.train_no = ? ORDER BY s.stop_seq";
        List<StopInfo> stops = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, trainNo);
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    Time arrival = results.getTime("arrival_time");
                    stops.add(new StopInfo(
                            results.getInt("stop_seq"),
                            results.getString("station_code"),
                            results.getString("station_name"),
                            arrival == null ? null : arrival.toLocalTime(),
                            results.getTime("departure_time").toLocalTime(),
                            results.getInt("day_offset"),
                            results.getInt("distance_km")));
                }
            }
        }
        return stops;
    }

    /**
     * Replaces a train's whole route.
     *
     * <p>Delete-then-insert inside the caller's transaction, rather than an
     * insert and a reorder per row: the route is one thing with one ordering, so
     * replacing it in one statement pair is both simpler and the only way the
     * {@code (train_no, stop_seq)} primary key can be renumbered contiguously.
     *
     * <p>Refused by {@code fk_bookings_boarding_stop} /
     * {@code fk_bookings_destination_stop} if any booking names a stop that no
     * longer exists. {@code AdminService} checks that first and says so in words.
     */
    public void replaceStops(String trainNo, List<AdminView.StopInput> stops)
            throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM train_stops WHERE train_no = ?")) {
            delete.setString(1, trainNo);
            delete.executeUpdate();
        }
        String sql = "INSERT INTO train_stops (train_no, stop_seq, station_code,"
                + " arrival_time, departure_time, day_offset, distance_km)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement insert = connection.prepareStatement(sql)) {
            for (AdminView.StopInput stop : stops) {
                insert.setString(1, trainNo);
                insert.setInt(2, stop.stopSeq());
                insert.setString(3, stop.stationCode());
                bindNullableTime(insert, 4, stop.arrivalTime());
                insert.setTime(5, Time.valueOf(stop.departureTime()));
                insert.setInt(6, stop.dayOffset());
                insert.setInt(7, stop.distanceKm());
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    /** Edits one stop's timings in place, leaving the ordering alone. */
    public void updateStopTiming(String trainNo, int stopSeq, LocalTime arrival,
                                 LocalTime departure, int dayOffset, int distanceKm)
            throws SQLException {
        String sql = "UPDATE train_stops SET arrival_time = ?, departure_time = ?,"
                + " day_offset = ?, distance_km = ? WHERE train_no = ? AND stop_seq = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindNullableTime(statement, 1, arrival);
            statement.setTime(2, Time.valueOf(departure));
            statement.setInt(3, dayOffset);
            statement.setInt(4, distanceKm);
            statement.setString(5, trainNo);
            statement.setInt(6, stopSeq);
            statement.executeUpdate();
        }
    }

    /** How many bookings name any stop of this train, at either end. */
    public int bookingsOnStopsOf(String trainNo) throws SQLException {
        return count("SELECT COUNT(*) FROM bookings WHERE train_no = ?", trainNo);
    }

    // ---------------------------------------------------------------- the rake

    /**
     * A train's coaches with their berth counts and how many are sold.
     *
     * <p>{@code sold_berths} counts reservations in any non-cancelled state, so
     * a berth that has held a booking at some point is visibly not free to
     * remove even if it is free to sell.
     */
    public List<AdminView.CoachRow> listCoaches(String trainNo) throws SQLException {
        String sql =
                "SELECT c.coach_id, c.train_no, c.coach_number, c.class_code, c.coach_type,"
                        + " c.position_in_train, c.has_toilet,"
                        + " (SELECT COUNT(*) FROM berths b WHERE b.coach_id = c.coach_id)"
                        + "   AS berth_count,"
                        + " (SELECT COUNT(DISTINCT r.berth_id) FROM seat_reservations r"
                        + "    WHERE r.coach_id = c.coach_id AND r.status <> 'CANCELLED')"
                        + "   AS sold_berths"
                        + " FROM coaches c WHERE c.train_no = ?"
                        + " ORDER BY c.position_in_train, c.coach_number";
        List<AdminView.CoachRow> rows = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, trainNo);
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    rows.add(new AdminView.CoachRow(
                            results.getLong("coach_id"),
                            results.getString("train_no"),
                            results.getString("coach_number"),
                            results.getString("class_code"),
                            results.getString("coach_type"),
                            results.getInt("position_in_train"),
                            results.getBoolean("has_toilet"),
                            results.getInt("berth_count"),
                            results.getInt("sold_berths")));
                }
            }
        }
        return rows;
    }

    /** Whether anything has ever been sold on this coach's berths. */
    public int reservationsOnCoach(long coachId) throws SQLException {
        return count("SELECT COUNT(*) FROM seat_reservations WHERE coach_id = ?", coachId);
    }

    /**
     * How many journeys this coach has run on.
     *
     * <p>{@code fk_jc_coach} refuses to delete a coach that appears on any
     * journey, because an old booking's coach snapshot would point at nothing.
     * Checking here means the refusal can be a sentence rather than a driver
     * error.
     */
    public int journeysUsingCoach(long coachId) throws SQLException {
        return count("SELECT COUNT(*) FROM journey_coaches WHERE coach_id = ?", coachId);
    }

    public int reservationsOnBerth(long berthId) throws SQLException {
        return count("SELECT COUNT(*) FROM seat_reservations WHERE berth_id = ?", berthId);
    }

    public void insertCoach(AdminView.CoachInput coach) throws SQLException {
        String sql = "INSERT INTO coaches (train_no, coach_number, class_code, coach_type,"
                + " position_in_train, has_toilet) VALUES (?, ?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, coach.trainNo());
            statement.setString(2, coach.coachNumber());
            statement.setString(3, coach.classCode());
            statement.setString(4, coach.coachType());
            statement.setInt(5, coach.position());
            statement.setBoolean(6, coach.hasToilet());
            statement.executeUpdate();
        }
    }

    /**
     * Removes a coach, which removes its berths with it.
     *
     * <p>Refused by {@code fk_res_coach} if it has ever sold a berth, and by
     * {@code fk_jc_coach} if it has run on a journey. The service checks both
     * first so the refusal reads as a sentence.
     */
    public void deleteCoach(long coachId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM coaches WHERE coach_id = ?")) {
            statement.setLong(1, coachId);
            statement.executeUpdate();
        }
    }

    public List<AdminView.BerthRow> listBerths(long coachId) throws SQLException {
        String sql = "SELECT b.berth_id, b.coach_id, c.coach_number, b.berth_number,"
                + " b.berth_type, b.position,"
                + " (SELECT COUNT(*) FROM seat_reservations r WHERE r.berth_id = b.berth_id"
                + "  AND r.status <> 'CANCELLED') AS sold_count"
                + " FROM berths b JOIN coaches c ON c.coach_id = b.coach_id"
                + " WHERE b.coach_id = ? ORDER BY b.berth_number";
        List<AdminView.BerthRow> rows = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, coachId);
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    rows.add(new AdminView.BerthRow(
                            results.getLong("berth_id"),
                            results.getLong("coach_id"),
                            results.getString("coach_number"),
                            results.getInt("berth_number"),
                            results.getString("berth_type"),
                            results.getString("position"),
                            results.getInt("sold_count")));
                }
            }
        }
        return rows;
    }

    public void insertBerth(AdminView.BerthInput berth) throws SQLException {
        String sql = "INSERT INTO berths (coach_id, berth_number, berth_type, position)"
                + " VALUES (?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, berth.coachId());
            statement.setInt(2, berth.berthNumber());
            statement.setString(3, berth.berthType());
            statement.setString(4, berth.position());
            statement.executeUpdate();
        }
    }

    public void deleteBerth(long berthId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM berths WHERE berth_id = ?")) {
            statement.setLong(1, berthId);
            statement.executeUpdate();
        }
    }

    /** Whether a class offers LOWER/MIDDLE/UPPER or WINDOW/AISLE/SIDE. */
    public boolean isSleeperClass(String classCode) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT is_sleeper FROM travel_classes WHERE class_code = ?")) {
            statement.setString(1, classCode);
            try (ResultSet results = statement.executeQuery()) {
                return results.next() && results.getBoolean(1);
            }
        }
    }

    // ---------------------------------------------------------------- fares

    /** Every fare rule, most specific first, then newest first. */
    public List<AdminView.FareRule> listFareRules() throws SQLException {
        String sql = "SELECT fare_rule_id, class_code, origin_station_code,"
                + " destination_station_code, distance_km, base_fare, per_km_fare,"
                + " quota_seats, tatkal_quota_seats, valid_from, valid_to"
                + " FROM fare_rules"
                + " ORDER BY class_code, origin_station_code, destination_station_code,"
                + "          valid_from DESC, fare_rule_id DESC";
        List<AdminView.FareRule> rows = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet results = statement.executeQuery()) {
            while (results.next()) {
                rows.add(readFareRule(results));
            }
        }
        return rows;
    }

    public void insertFareRule(AdminView.FareRule rule) throws SQLException {
        String sql = "INSERT INTO fare_rules (class_code, origin_station_code,"
                + " destination_station_code, distance_km, base_fare, per_km_fare,"
                + " quota_seats, tatkal_quota_seats, valid_from, valid_to)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindFareRule(statement, rule, 1);
            statement.executeUpdate();
        }
    }

    public void updateFareRule(AdminView.FareRule rule) throws SQLException {
        String sql = "UPDATE fare_rules SET class_code = ?, origin_station_code = ?,"
                + " destination_station_code = ?, distance_km = ?, base_fare = ?,"
                + " per_km_fare = ?, quota_seats = ?, tatkal_quota_seats = ?,"
                + " valid_from = ?, valid_to = ? WHERE fare_rule_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindFareRule(statement, rule, 1);
            statement.setLong(11, rule.ruleId());
            statement.executeUpdate();
        }
    }

    public void deleteFareRule(long ruleId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM fare_rules WHERE fare_rule_id = ?")) {
            statement.setLong(1, ruleId);
            statement.executeUpdate();
        }
    }

    private static void bindFareRule(PreparedStatement statement, AdminView.FareRule rule,
                                     int from) throws SQLException {
        statement.setString(from, rule.classCode());
        bindNullableText(statement, from + 1, rule.origin());
        bindNullableText(statement, from + 2, rule.destination());
        bindNullableInt(statement, from + 3, rule.distanceKm());
        statement.setBigDecimal(from + 4, rule.baseFare());
        statement.setBigDecimal(from + 5, rule.perKmFare());
        statement.setInt(from + 6, rule.quotaSeats());
        statement.setInt(from + 7, rule.tatkalSeats());
        statement.setDate(from + 8, java.sql.Date.valueOf(rule.validFrom()));
        if (rule.validTo() == null) {
            statement.setNull(from + 9, java.sql.Types.DATE);
        } else {
            statement.setDate(from + 9, java.sql.Date.valueOf(rule.validTo()));
        }
    }

    private static AdminView.FareRule readFareRule(ResultSet results) throws SQLException {
        int distance = results.getInt("distance_km");
        Integer distanceKm = results.wasNull() ? null : distance;
        java.sql.Date validToDate = results.getDate("valid_to");
        return new AdminView.FareRule(
                results.getLong("fare_rule_id"),
                results.getString("class_code"),
                results.getString("origin_station_code"),
                results.getString("destination_station_code"),
                distanceKm,
                results.getBigDecimal("base_fare"),
                results.getBigDecimal("per_km_fare"),
                results.getInt("quota_seats"),
                results.getInt("tatkal_quota_seats"),
                results.getDate("valid_from").toLocalDate(),
                validToDate == null ? null : validToDate.toLocalDate());
    }

    // ---------------------------------------------------------------- plumbing

    /** Every station code, so a route can be validated without a query per stop. */
    public List<String> stationCodes() throws SQLException {
        List<String> codes = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT station_code FROM stations ORDER BY station_code");
             ResultSet results = statement.executeQuery()) {
            while (results.next()) {
                codes.add(results.getString(1));
            }
        }
        return codes;
    }

    public boolean classExists(String classCode) throws SQLException {
        return count("SELECT COUNT(*) FROM travel_classes WHERE class_code = ?", classCode) > 0;
    }

    /** Whether a coach id is real, so a stale one is refused rather than ignored. */
    public boolean coachExists(long coachId) throws SQLException {
        return count("SELECT COUNT(*) FROM coaches WHERE coach_id = ?", coachId) > 0;
    }

    /** The travel classes a coach may be built in, code and label, for a picker. */
    public List<String> travelClasses() throws SQLException {
        List<String> classes = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT class_code FROM travel_classes ORDER BY class_code");
             ResultSet results = statement.executeQuery()) {
            while (results.next()) {
                classes.add(results.getString(1));
            }
        }
        return classes;
    }

    /** Whether a berth id is real, for the same reason. */
    public boolean berthExists(long berthId) throws SQLException {
        return count("SELECT COUNT(*) FROM berths WHERE berth_id = ?", berthId) > 0;
    }

    /** Whether this train already has a coach with this number. */
    public boolean coachNumberInUse(String trainNo, String coachNumber) throws SQLException {
        return count("SELECT COUNT(*) FROM coaches WHERE train_no = ? AND coach_number = ?",
                trainNo, coachNumber) > 0;
    }

    /** Whether this coach already has a berth with this number. */
    public boolean berthNumberInUse(long coachId, int berthNumber) throws SQLException {
        return count("SELECT COUNT(*) FROM berths WHERE coach_id = ? AND berth_number = ?",
                coachId, berthNumber) > 0;
    }

    /** The class a coach belongs to, which decides what a berth in it may be. */
    public String classCodeOfCoach(long coachId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT class_code FROM coaches WHERE coach_id = ?")) {
            statement.setLong(1, coachId);
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? results.getString(1) : null;
            }
        }
    }

    public boolean trainExists(String trainNo) throws SQLException {
        return count("SELECT COUNT(*) FROM trains WHERE train_no = ?", trainNo) > 0;
    }

    /** Which train already carries this name, or {@code null}. */
    public String trainNumberNamed(String trainName) throws SQLException {
        return singleText("SELECT train_no FROM trains WHERE train_name = ?", trainName);
    }

    /** Which station code already carries this name, or {@code null}. */
    public String stationCodeNamed(String stationName) throws SQLException {
        return singleText("SELECT station_code FROM stations WHERE station_name = ?", stationName);
    }

    /** How many trains call at a station. */
    public int countStopsAt(String code) throws SQLException {
        return count("SELECT COUNT(*) FROM train_stops WHERE station_code = ?", code);
    }

    /** How many bookings travel to or from a station. */
    public int bookingsNaming(String code) throws SQLException {
        return count("SELECT COUNT(*) FROM bookings WHERE boarding_station_code = ?"
                + " OR destination_station_code = ?", code, code);
    }

    /** How many fare rules price a route through a station. */
    public int fareRulesNaming(String code) throws SQLException {
        return count("SELECT COUNT(*) FROM fare_rules WHERE origin_station_code = ?"
                + " OR destination_station_code = ?", code, code);
    }

    private String singleText(String sql, String value) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, value);
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? results.getString(1) : null;
            }
        }
    }

    public Optional<AdminView.FareRule> findFareRule(long ruleId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT fare_rule_id, class_code, origin_station_code,"
                        + " destination_station_code, distance_km, base_fare, per_km_fare,"
                        + " quota_seats, tatkal_quota_seats, valid_from, valid_to"
                        + " FROM fare_rules WHERE fare_rule_id = ?")) {
            statement.setLong(1, ruleId);
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? Optional.of(readFareRule(results)) : Optional.empty();
            }
        }
    }

    private int count(String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) {
                statement.setObject(i + 1, values[i]);
            }
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? results.getInt(1) : 0;
            }
        }
    }

    /** SQL NULL rather than a sentinel: {@code distance_km} and {@code zone} are nullable. */
    private static void bindNullableInt(PreparedStatement statement, int index, Integer value)
            throws SQLException {
        if (value == null) {
            statement.setNull(index, java.sql.Types.INTEGER);
        } else {
            statement.setInt(index, value);
        }
    }

    private static void bindNullableText(PreparedStatement statement, int index, String value)
            throws SQLException {
        if (value == null || value.isBlank()) {
            statement.setNull(index, java.sql.Types.VARCHAR);
        } else {
            statement.setString(index, value);
        }
    }

    private static void bindNullableTime(PreparedStatement statement, int index, LocalTime value)
            throws SQLException {
        if (value == null) {
            statement.setNull(index, java.sql.Types.TIME);
        } else {
            statement.setTime(index, Time.valueOf(value));
        }
    }
}