package com.rail.service;

import com.rail.db.BookingDAO;
import com.rail.db.ConnectionSource;
import com.rail.db.DatabaseConnection;
import com.rail.model.BookingDetail;
import com.rail.model.BookingSummary;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * Booking history, always scoped to the signed-in traveller.
 *
 * <p>Isolation is enforced twice on purpose, because the two layers fail
 * differently. {@link #myBookings} takes the {@code user_id} straight from the
 * session, so a caller cannot influence it at all. {@link #bookingsFor} takes a
 * {@code user_id} argument and is therefore gated by
 * {@link SessionManager#requireSelfOrAdmin}, for the case where some future
 * screen is handed an id from a click handler or a URL-ish route.
 *
 * <p>Filtering happens in the WHERE clause. There is no "fetch everything then
 * filter in Java" path, so another traveller's booking is never loaded into
 * memory in the first place.
 */
public final class BookingQueryService {

    /** How many rows the dashboard asks for. */
    private static final int DASHBOARD_ROWS = 5;

    /** How many rows the My Bookings screen asks for. */
    private static final int FULL_HISTORY_ROWS = 50;

    private final ConnectionSource connections;

    public BookingQueryService() {
        this(DatabaseConnection::getConnection);
    }

    /** See {@link AuthService#AuthService(ConnectionSource)}. */
    public BookingQueryService(ConnectionSource connections) {
        this.connections = connections;
    }

    /** Total bookings belonging to the signed-in traveller. */
    public int myBookingCount() throws AccessDeniedException, SQLException {
        return countFor(SessionManager.get().requireUser().userId());
    }

    /** Bookings belonging to the signed-in traveller that are still live. */
    public int myActiveBookingCount() throws AccessDeniedException, SQLException {
        long userId = SessionManager.get().requireUser().userId();
        try (Connection connection = connections.open()) {
            return new BookingDAO(connection).countActiveForUser(userId);
        }
    }

    /** The signed-in traveller's most recent journeys, for the dashboard. */
    public List<BookingSummary> myRecentBookings() throws AccessDeniedException, SQLException {
        return myBookings(DASHBOARD_ROWS);
    }

    /** The signed-in traveller's full booking history. */
    public List<BookingSummary> myBookings() throws AccessDeniedException, SQLException {
        return myBookings(FULL_HISTORY_ROWS);
    }

    private List<BookingSummary> myBookings(int limit) throws AccessDeniedException, SQLException {
        long userId = SessionManager.get().requireUser().userId();
        try (Connection connection = connections.open()) {
            return new BookingDAO(connection).historyForUser(userId, limit);
        }
    }

    /**
     * Another account's bookings.
     *
     * @throws AccessDeniedException unless the caller is that account or an
     *                                administrator
     */
    public List<BookingSummary> bookingsFor(long userId, int limit)
            throws AccessDeniedException, SQLException {
        SessionManager.get().requireSelfOrAdmin(userId);
        try (Connection connection = connections.open()) {
            return new BookingDAO(connection).historyForUser(userId, limit);
        }
    }

    /**
     * One of the signed-in traveller's own bookings, by PNR.
     *
     * <p>Scoped to the session in SQL, so another traveller's PNR simply is not
     * found - there is no code path that can report it and then hide it.
     */
    public Optional<BookingSummary> myBookingByPnr(long pnr)
            throws AccessDeniedException, SQLException {
        long userId = SessionManager.get().requireUser().userId();
        try (Connection connection = connections.open()) {
            return new BookingDAO(connection).findByPnrForUser(pnr, userId);
        }
    }

    /**
     * One of the signed-in traveller's own bookings as a full ticket.
     *
     * <p>The same read the PNR screen and the My Bookings list both use, so the
     * two cannot show different answers for the same PNR. Scoped to the session in
     * SQL exactly as {@link #myBookingByPnr} is: another traveller's ticket is not
     * found, rather than found and then hidden.
     */
    public Optional<BookingDetail> myTicketByPnr(long pnr)
            throws AccessDeniedException, SQLException {
        long userId = SessionManager.get().requireUser().userId();
        try (Connection connection = connections.open()) {
            return new BookingDAO(connection).ticketForUser(pnr, userId);
        }
    }

    private int countFor(long userId) throws SQLException {
        try (Connection connection = connections.open()) {
            return new BookingDAO(connection).countForUser(userId);
        }
    }
}