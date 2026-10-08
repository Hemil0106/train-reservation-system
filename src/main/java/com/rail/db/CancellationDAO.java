package com.rail.db;

import com.rail.model.BookingDetail;
import com.rail.service.RefundPolicy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Optional;

/**
 * Reads and writes the cancellation ledger.
 *
 * <p>One row per cancellation, appended and never edited or removed: the table
 * is what makes "this fare was refunded, on this date, for this reason" a fact
 * rather than a guess. A whole-booking cancellation leaves
 * {@code passenger_id} and {@code reservation_id} null, which is how the row
 * says "the whole ticket" without a second flag column.
 *
 * <p>The passenger rows and the reservations are not touched here. They belong
 * to {@link BookingDAO}, which owns both, so there is exactly one place in the
 * application that writes each table.
 */
public final class CancellationDAO {

    private static final String COLUMNS =
            "cancellation_id, cancelled_at, reason, is_refundable, refund_amount,"
            + " cancellation_charges, refund_status";

    private final Connection connection;

    public CancellationDAO(Connection connection) {
        this.connection = connection;
    }

    /**
     * The cancellation recorded against this booking, if any.
     *
     * <p>The most recent one, by id, so that if a whole booking is ever
     * cancelled and then a passenger within it is cancelled too, the ticket shows
     * the one that explains its current state.
     *
     * <p>Not scoped to a user: the caller has already established ownership by
     * loading the booking itself, and re-deriving it here would mean joining the
     * bookings table for a question this row cannot answer on its own.
     */
    public Optional<BookingDetail.Cancellation> latestFor(long pnr) throws SQLException {
        String sql = "SELECT " + COLUMNS + " FROM cancellations WHERE pnr = ?"
                + " ORDER BY cancellation_id DESC LIMIT 1";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, pnr);
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? Optional.of(read(results)) : Optional.empty();
            }
        }
    }

    /**
     * Appends the cancellation row.
     *
     * <p>{@code cancelled_at} is left to the column's own default, so the time
     * recorded is the database's, not the application host's - two machines with
     * skewed clocks should not disagree about when a refund happened.
     *
     * <p>Written last in the transaction, after the booking and its reservations
     * have been updated, so a cancellation row can only ever exist alongside an
     * already-cancelled booking.
     *
     * @param pnr the whole booking being cancelled
     * @param userId the signed-in account that asked, recorded for audit
     * @param reason why, in the passenger's words
     * @param outcome the money this produced, from {@link RefundPolicy}
     * @return the new row's id
     */
    public long insert(long pnr, long userId, String reason, RefundPolicy.Outcome outcome)
            throws SQLException {
        String sql = "INSERT INTO cancellations (pnr, cancelled_by_user_id, reason,"
                + " is_refundable, refund_amount, cancellation_charges, refund_status)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement statement =
                     connection.prepareStatement(sql, PreparedStatement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, pnr);
            statement.setLong(2, userId);
            statement.setString(3, reason);
            statement.setBoolean(4, outcome.refundable());
            statement.setBigDecimal(5, outcome.refundAmount());
            statement.setBigDecimal(6, outcome.charges());
            statement.setString(7, outcome.refundStatus());
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : 0L;
            }
        }
    }

    private static BookingDetail.Cancellation read(ResultSet results) throws SQLException {
        Timestamp cancelledAt = results.getTimestamp("cancelled_at");
        return new BookingDetail.Cancellation(
                results.getLong("cancellation_id"),
                cancelledAt == null ? null : cancelledAt.toLocalDateTime(),
                results.getString("reason"),
                results.getBoolean("is_refundable"),
                results.getBigDecimal("refund_amount"),
                results.getBigDecimal("cancellation_charges"),
                results.getString("refund_status"));
    }
}