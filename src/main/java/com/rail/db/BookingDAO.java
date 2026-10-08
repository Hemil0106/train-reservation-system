package com.rail.db;

import com.rail.model.BerthAllocation;
import com.rail.model.BookingContact;
import com.rail.model.BookingDetail;
import com.rail.model.BookingStatus;
import com.rail.model.BookingSummary;
import com.rail.model.FareEstimate;
import com.rail.model.PassengerDraft;
import com.rail.model.TrainSelection;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Reads and writes booking rows: history for the dashboard, and the three
 * inserts the booking engine commits together.
 *
 * <p>Every method that returns a specific traveller's data requires the
 * {@code user_id} to filter on. There is deliberately no "list all bookings"
 * method for ordinary use: a caller cannot accidentally render somebody else's
 * ticket because no query exists that would produce it. The single
 * whole-table count is administrative and is reachable only through
 * {@code AuthService}'s admin check.
 *
 * <p>The three write methods are the only place in the application that inserts
 * into these tables, and each is called once per booking from inside one
 * transaction. They are kept separate rather than merged into a single
 * multi-row statement because the passenger ids are generated as they are
 * inserted and the reservation rows need them.
 *
 * <p>As in {@link UserDao}, the caller owns the {@link Connection} - for the
 * write path, one already inside a transaction that {@code BookingService}
 * commits or rolls back.
 */
public final class BookingDAO {

    /** Bound so a caller cannot request an unbounded result set by accident. */
    private static final int MAX_HISTORY_ROWS = 50;

    private final Connection connection;

    public BookingDAO(Connection connection) {
        this.connection = connection;
    }

    /** Total bookings belonging to one user, across all statuses. */
    public int countForUser(long userId) throws SQLException {
        return count("SELECT COUNT(*) FROM bookings WHERE user_id = ?", userId);
    }

    /** Bookings belonging to one user that are still live (not cancelled). */
    public int countActiveForUser(long userId) throws SQLException {
        return count("SELECT COUNT(*) FROM bookings"
                + " WHERE user_id = ? AND status IN ('CONFIRMED', 'PARTIALLY_CANCELLED')", userId);
    }

    private int count(String sql, long userId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, userId);
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? results.getInt(1) : 0;
            }
        }
    }

    /**
     * The user's own bookings, most recent journey first.
     *
     * <p>{@code bookings} already carries the journey date and both station
     * codes denormalised, so only {@code trains} needs joining, for its name.
     *
     * @param limit how many rows the caller wants; silently capped
     */
    public List<BookingSummary> historyForUser(long userId, int limit) throws SQLException {
        int rows = Math.max(1, Math.min(limit, MAX_HISTORY_ROWS));
        String sql = "SELECT b.pnr, b.train_no, t.train_name, b.journey_date,"
                + " b.boarding_station_code, b.destination_station_code,"
                + " b.travel_class_code, b.status, b.total_fare"
                + " FROM bookings b"
                + " JOIN trains t ON t.train_no = b.train_no"
                + " WHERE b.user_id = ?"
                + " ORDER BY b.journey_date DESC, b.pnr DESC"
                + " LIMIT ?";

        List<BookingSummary> results = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, userId);
            statement.setInt(2, rows);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    results.add(readSummary(rs));
                }
            }
        }
        return results;
    }

    /**
     * Looks up one booking by PNR, restricted to its owner.
     *
     * <p>The {@code user_id} is part of the WHERE clause rather than checked
     * afterwards, so guessing another traveller's PNR returns empty. Used by the
     * PNR Status screen.
     *
     * @param pnr    the number the passenger typed
     * @param userId the signed-in traveller
     */
    public Optional<BookingSummary> findByPnrForUser(long pnr, long userId) throws SQLException {
        String sql = "SELECT b.pnr, b.train_no, t.train_name, b.journey_date,"
                + " b.boarding_station_code, b.destination_station_code,"
                + " b.travel_class_code, b.status, b.total_fare"
                + " FROM bookings b"
                + " JOIN trains t ON t.train_no = b.train_no"
                + " WHERE b.pnr = ? AND b.user_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, pnr);
            statement.setLong(2, userId);
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? Optional.of(readSummary(results)) : Optional.empty();
            }
        }
    }

    private static BookingSummary readSummary(ResultSet results) throws SQLException {
        Date journeyDate = results.getDate("journey_date");
        return new BookingSummary(
                results.getLong("pnr"),
                results.getString("train_no"),
                results.getString("train_name"),
                journeyDate == null ? null : journeyDate.toLocalDate(),
                results.getString("boarding_station_code"),
                results.getString("destination_station_code"),
                results.getString("travel_class_code"),
                results.getString("status"),
                results.getBigDecimal("total_fare").doubleValue());
    }

    /**
     * One booking as a full ticket: passengers, berths, fares and cancellation.
     *
     * <p>Three parameterized reads, all scoped to the owner. The booking itself
     * is joined for the four names it denormalises only codes for; its
     * passengers come with the berth each holds; its cancellation, if there is
     * one, comes from {@link CancellationDAO}. Scoping every one of them to
     * {@code user_id} rather than filtering in Java means another traveller's
     * ticket is never loaded into memory at all.
     *
     * <p>Seat and status are read per reservation, not copied from the booking:
     * the reservation is the row that actually holds the berth, so it is the row
     * that has to be correct.
     */
    public Optional<BookingDetail> ticketForUser(long pnr, long userId) throws SQLException {
        String bookingSql =
                "SELECT b.pnr, b.user_id, b.train_no, t.train_name, b.journey_date,"
                + " b.boarding_station_code, from_station.station_name AS from_name,"
                + " b.destination_station_code, to_station.station_name AS to_name,"
                + " b.travel_class_code, tc.class_name, b.status, b.total_fare"
                + " FROM bookings b"
                + " JOIN trains t ON t.train_no = b.train_no"
                + " JOIN stations from_station"
                + "   ON from_station.station_code = b.boarding_station_code"
                + " JOIN stations to_station"
                + "   ON to_station.station_code = b.destination_station_code"
                + " JOIN travel_classes tc ON tc.class_code = b.travel_class_code"
                + " WHERE b.pnr = ? AND b.user_id = ?";

        try (PreparedStatement statement = connection.prepareStatement(bookingSql)) {
            statement.setLong(1, pnr);
            statement.setLong(2, userId);
            try (ResultSet results = statement.executeQuery()) {
                if (!results.next()) {
                    return Optional.empty();
                }
                String status = results.getString("status");
                Date journeyDate = results.getDate("journey_date");
                return Optional.of(new BookingDetail(
                        results.getLong("pnr"),
                        results.getLong("user_id"),
                        results.getString("train_no"),
                        results.getString("train_name"),
                        journeyDate == null ? null : journeyDate.toLocalDate(),
                        results.getString("boarding_station_code"),
                        results.getString("from_name"),
                        results.getString("destination_station_code"),
                        results.getString("to_name"),
                        results.getString("travel_class_code"),
                        results.getString("class_name"),
                        status,
                        BookingStatus.fromDb(status),
                        results.getBigDecimal("total_fare"),
                        passengersOf(pnr, userId),
                        new CancellationDAO(connection).latestFor(pnr).orElse(null)));
            }
        }
    }

    /**
     * The ticket's passengers, each with their berth.
     *
     * <p>Joined to {@code bookings} as well, so the second read is scoped to the
     * owner too. Without that join a caller could pass any PNR and read any
     * passenger list, which is the one hole the ownership rule must not have.
     */
    private List<BookingDetail.Passenger> passengersOf(long pnr, long userId)
            throws SQLException {
        String sql =
                "SELECT p.passenger_id, p.full_name, p.age, p.gender, p.berth_preference,"
                + " p.is_primary, r.berth_id, c.coach_number, r.berth_number,"
                + " r.berth_type, r.position, r.amount, r.status"
                + " FROM passengers p"
                + " JOIN bookings b ON b.pnr = p.pnr AND b.user_id = ?"
                + " JOIN seat_reservations r ON r.pnr = p.pnr AND r.passenger_id = p.passenger_id"
                + " JOIN coaches c ON c.coach_id = r.coach_id"
                + " WHERE p.pnr = ?"
                + " ORDER BY p.passenger_id";
        List<BookingDetail.Passenger> passengers = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, userId);
            statement.setLong(2, pnr);
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    passengers.add(new BookingDetail.Passenger(
                            results.getLong("passenger_id"),
                            results.getString("full_name"),
                            results.getInt("age"),
                            results.getString("gender"),
                            results.getString("berth_preference"),
                            results.getBoolean("is_primary"),
                            results.getLong("berth_id"),
                            results.getString("coach_number"),
                            results.getInt("berth_number"),
                            results.getString("berth_type"),
                            results.getString("position"),
                            results.getBigDecimal("amount"),
                            results.getString("status")));
                }
            }
        }
        return passengers;
    }

    /**
     * Total bookings across all users.
     *
     * <p>Administrative. Callers are expected to have passed
     * {@code SessionManager.requireAdmin()} first - see
     * {@code AuthService.adminDashboardCounts}.
     */
    public long countAllBookings() throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM bookings");
             ResultSet results = statement.executeQuery()) {
            return results.next() ? results.getLong(1) : 0L;
        }
    }

    // ---------------------------------------------------------------- writes

    /**
     * Takes the booking's own row lock, and reports the status it found there.
     *
     * <p>Every cancellation of this booking must queue behind this lock, so two
     * of them cannot both read {@code CONFIRMED} and both refund. Returns
     * {@code null} when the PNR is not this account's.
     *
     * <p>One table, deliberately. The equivalent lock on
     * {@link #ticketForUser} would drag {@code trains}, {@code stations} and
     * {@code travel_classes} into the lock set with their joined rows, and this
     * build's database account is not permitted to lock those. The ticket is
     * read afterwards, while this lock is still held, so it cannot go stale.
     */
    public String lockStatusForUser(long pnr, long userId) throws SQLException {
        String sql = "SELECT status FROM bookings WHERE pnr = ? AND user_id = ? FOR UPDATE";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, pnr);
            statement.setLong(2, userId);
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? results.getString("status") : null;
            }
        }
    }

    /**
     * Marks the booking cancelled.
     *
     * <p>An update, never a delete: the booking, its passengers and its berths
     * are the record of what was sold, and a cancelled ticket still has to be
     * printable.
     */
    public void markCancelled(long pnr) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE bookings SET status = 'CANCELLED', updated_at = CURRENT_TIMESTAMP"
                + " WHERE pnr = ? AND status <> 'CANCELLED'")) {
            statement.setLong(1, pnr);
            statement.executeUpdate();
        }
    }

    /**
     * Releases every berth this booking holds, by cancelling its reservations.
     *
     * <p>Scoped to the PNR alone on purpose. It runs while
     * {@link #lockStatusForUser} holds the booking lock, and every reservation
     * in the table belongs to exactly one booking, so no reservation outside this
     * booking can be touched. {@code status <> 'CANCELLED'} keeps the row count
     * returned honest for a caller that wants to know whether there was anything
     * left to release.
     *
     * @return how many reservations changed
     */
    public int cancelReservations(long pnr) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE seat_reservations SET status = 'CANCELLED'"
                + " WHERE pnr = ? AND status <> 'CANCELLED'")) {
            statement.setLong(1, pnr);
            return statement.executeUpdate();
        }
    }

    /**
     * Inserts the booking row, with a PNR the caller generated.
     *
     * <p>The PNR is a parameter rather than something read back from an
     * auto-increment, because a sequential counter would let one passenger guess
     * another's. {@code pnr} is the table's primary key, so a collision arrives
     * as a duplicate-key error the caller can retry - see
     * {@code BookingService}.
     *
     * <p>Written before the passengers, because {@code passengers.pnr} and
     * {@code seat_reservations.pnr} both reference this row.
     *
     * @param contactName the account holder's name; the schema requires it and
     *     the booking screen does not ask for it a second time
     */
    public void insertBooking(long pnr, long journeyId, TrainSelection selection,
                              FareEstimate fare, String contactName, BookingContact contact,
                              long userId) throws SQLException {
        String sql = "INSERT INTO bookings (pnr, journey_id, train_no, journey_date,"
                + " boarding_stop_seq, boarding_station_code,"
                + " destination_stop_seq, destination_station_code,"
                + " travel_class_code, total_fare, passenger_count, status,"
                + " contact_name, contact_mobile, contact_email, user_id)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, pnr);
            statement.setLong(2, journeyId);
            statement.setString(3, selection.trainNo());
            statement.setDate(4, Date.valueOf(selection.journeyDate()));
            statement.setInt(5, selection.fromSeq());
            statement.setString(6, selection.fromStation().code());
            statement.setInt(7, selection.toSeq());
            statement.setString(8, selection.toStation().code());
            statement.setString(9, selection.classCode());
            statement.setBigDecimal(10, fare.total());
            statement.setInt(11, selection.passengers());
            statement.setString(12, BookingStatus.CONFIRMED.dbValue());
            statement.setString(13, contactName);
            statement.setString(14, contact.mobile());
            statement.setString(15, contact.email());
            statement.setLong(16, userId);
            statement.executeUpdate();
        }
    }

    /**
     * Inserts one passenger and returns their generated id.
     *
     * <p>{@code berth_preference} stores the <em>request</em>, not the berth
     * granted; the reality goes to {@code seat_reservations.position}. That is
     * why both columns exist, and collapsing them would erase the evidence that
     * the fallback policy was used.
     *
     * @param primary true for the first passenger, who is the contact person
     */
    public long insertPassenger(long pnr, PassengerDraft passenger, boolean primary)
            throws SQLException {
        String sql = "INSERT INTO passengers (pnr, full_name, age, gender,"
                + " berth_preference, is_primary) VALUES (?, ?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql,
                java.sql.Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, pnr);
            statement.setString(2, passenger.fullName());
            statement.setInt(3, passenger.age());
            statement.setString(4, passenger.gender().dbValue());
            statement.setString(5, passenger.berthPreference().dbValue());
            statement.setInt(6, primary ? 1 : 0);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("MySQL did not return a passenger id");
                }
                return keys.getLong(1);
            }
        }
    }

    /**
     * Inserts one passenger's berth hold for the whole booked segment.
     *
     * <p>The coach, berth number, type and position are copied in rather than
     * joined at read time, so a later change to the coach layout cannot silently
     * rewrite the history of what somebody travelled in. That is the usual
     * denormalisation trade: a stale copy of the truth, kept on purpose.
     *
     * <p>{@code from_stop_seq} and {@code to_stop_seq} are what make overlap
     * detection possible later, and are the reason a non-overlapping booking on
     * the same berth is allowed.
     *
     * @param amount this passenger's share of the fare
     */
    public void insertSeatReservation(long pnr, long passengerId, long journeyId,
                                      BerthAllocation berth, String classCode,
                                      int fromSeq, int toSeq,
                                      String fromStationCode, String toStationCode,
                                      BigDecimal amount) throws SQLException {
        String sql = "INSERT INTO seat_reservations (pnr, passenger_id, journey_id,"
                + " berth_id, coach_id, travel_class_code, from_stop_seq, to_stop_seq,"
                + " from_station_code, to_station_code, berth_number, berth_type,"
                + " position, amount, status)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, pnr);
            statement.setLong(2, passengerId);
            statement.setLong(3, journeyId);
            statement.setLong(4, berth.berthId());
            statement.setLong(5, berth.coachId());
            statement.setString(6, classCode);
            statement.setInt(7, fromSeq);
            statement.setInt(8, toSeq);
            statement.setString(9, fromStationCode);
            statement.setString(10, toStationCode);
            statement.setInt(11, berth.berthNumber());
            statement.setString(12, berth.berthType());
            statement.setString(13, berth.grantedPosition().dbValue());
            statement.setBigDecimal(14, amount);
            statement.setString(15, BookingStatus.CONFIRMED.dbValue());
            statement.executeUpdate();
        }
    }
}