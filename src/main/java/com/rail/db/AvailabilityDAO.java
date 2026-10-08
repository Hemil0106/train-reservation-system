package com.rail.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Every question about "is this berth free" lives here, in one place.
 *
 * <p>The booking engine and the search screen both need to know whether a berth
 * is occupied, and they must agree. When they ask separately they drift, and a
 * search result that says a berth is free while the booking engine disagrees is
 * the bug this class exists to make impossible: there is one SQL statement for
 * a candidate berth and one for an occupied segment.
 *
 * <h2>Why a locking read exists at all</h2>
 *
 * <p>{@code seat_reservations} has <em>no</em> unique constraint stopping two
 * bookings from taking the same berth on overlapping segments. MySQL's CHECK
 * constraints cannot contain a subquery, so overlapping-booking protection
 * cannot live in the schema. It lives here instead, in
 * {@link #lockCandidateBerths}, and it works like this:
 *
* <p>Inside the booking transaction, the journey's inventory rows are read
 * <em>FOR UPDATE</em> and its reservations are read <em>FOR UPDATE</em> as a
 * current read. A second transaction attempting the same booking blocks
 * there rather than reading a stale answer. When the first commits, the second
 * resumes and - because a locking read always sees the latest committed rows -
 * sees the reservation that was just written, finds the berth occupied, and
 * allocates a different one or fails cleanly. Both callers are serialised on the
 * same rows, so the same berth is never sold twice.
 *
 * <p>The ordering is not incidental. Each locking read is a single statement in a
 * fixed order ({@code coach_id}, then {@code berth_id}), so two transactions
 * wanting the same inventory always queue in the same order and cannot deadlock
 * by grabbing each other's rows crossed.
 *
 * <p>As in {@link UserDao}, the caller owns the {@link Connection}, and for the
      * booking path that connection is already inside a transaction.
      */
public final class AvailabilityDAO {

    /**
     * Reservation states that hold a berth.
     *
     * <p>{@code RAC} is included because the column allows it, even though this
     * application never writes it: an RAC hold does occupy a berth, and a check
     * that ignored it would report a held seat as free.
     */
    private static final String OCCUPYING = "('CONFIRMED','RAC')";

    private final Connection connection;

    public AvailabilityDAO(Connection connection) {
        this.connection = connection;
    }

    /**
     * Every berth of one class attached to one journey, each marked with whether
     * it is held <em>on the requested segment</em>.
     *
     * <p>Taking all of them, held ones included, is what lets the allocation
     * pass in Java decide between "free but the wrong position" and "not free at
     * all" without a second query per passenger.
     *
     * <p>The hold test is the overlap comparison, scoped to this segment. That
     * scoping is what allows a berth sold for one leg to be resold for the next:
     * a reservation covering stops 1-3 leaves a booking for stops 3-5 free,
     * because the intervals touch but do not overlap. An occupancy test that
     * ignored the segment would wrongly report that berth as sold.
     *
     * <p>Occupancy is read as a current read, on purpose. Under MySQL's default
     * REPEATABLE READ, a plain SELECT reads the snapshot taken at the
     * transaction's first read and would keep reporting the answer it saw before
     * it waited on the lock. A locking read is always a <em>current</em> read, so
     * the occupancy statement sees rows committed by whoever held the lock a
     * moment ago. That is what makes the wait safe rather than merely slow.
     *
     * <p>Must be called inside a transaction.
     *
     * @param fromSeq first stop of the requested segment, inclusive
     * @param toSeq   stop the segment ends at, exclusive
     */
    public List<CandidateBerth> lockCandidateBerths(long journeyId, String classCode,
                                                    int fromSeq, int toSeq) throws SQLException {
        lockJourneyInventory(journeyId);
        Set<Long> occupied = lockOccupiedBerths(journeyId, fromSeq, toSeq);
        String sql =
                "SELECT b.berth_id, b.coach_id, c.coach_number, b.berth_number,"
                + "       b.berth_type, b.position"
                + " FROM journey_coaches jc"
                + " JOIN coaches c ON c.coach_id = jc.coach_id"
                + " JOIN berths b ON b.coach_id = c.coach_id"
                + " WHERE jc.journey_id = ? AND jc.coach_status = 'ATTACHED'"
                + "   AND c.class_code = ?"
                + " ORDER BY c.position_in_train, b.berth_number";
        List<CandidateBerth> candidates = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, journeyId);
            statement.setString(2, classCode);
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    long berthId = results.getLong("berth_id");
                    candidates.add(new CandidateBerth(
                            berthId,
                            results.getLong("coach_id"),
                            results.getString("coach_number"),
                            results.getInt("berth_number"),
                            results.getString("berth_type"),
                            results.getString("position"),
                            occupied.contains(berthId)));
                }
            }
        }
        return candidates;
    }

    /**
     * Serialises every booking touching this journey.
     *
     * <p>Locks every attached coach of the journey in {@code coach_id} order. This
     * is the statement that makes two concurrent bookings queue up: whoever gets
     * here first holds the journey until it commits or rolls back, and the second
     * one then re-reads occupancy as a current read and cannot pick a berth the
     * first one just sold.
     *
     * <p>ponytail: it locks every coach of the journey rather than only the ones
     * in the requested class, so bookings in different classes queue behind each
     * other too. Coarser than necessary and one statement instead of several -
     * acceptable at one desktop user. Narrowing it means a statement per class,
     * which must still agree on lock order or two transactions can deadlock.
     *
     * <p>No lock clause can be used on {@code coaches}, {@code trains},
     * {@code stations} or {@code fare_rules}: the application account has plain
     * {@code SELECT} on those and is refused a locking read. The writable
     * inventory tables are lockable, which is why the locking reads live here.
     */
    private void lockJourneyInventory(long journeyId) throws SQLException {
        String sql = "SELECT coach_id FROM journey_coaches"
                + " WHERE journey_id = ? AND coach_status = 'ATTACHED'"
                + " ORDER BY coach_id FOR UPDATE";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, journeyId);
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    results.getLong(1);
                }
            }
        }
    }

    /**
     * The berths held on this segment, as a current read.
     *
     * <p>{@code seat_reservations} is writable by this account, so it can carry a
     * locking clause. Locking the occupied set also takes gap locks over the
     * uncovered range, so a transaction that has not inserted its reservation yet
     * still cannot be raced past.
     */
    private Set<Long> lockOccupiedBerths(long journeyId, int fromSeq, int toSeq)
            throws SQLException {
        String sql = "SELECT berth_id FROM seat_reservations"
                + " WHERE journey_id = ? AND status IN " + OCCUPYING
                + "   AND from_stop_seq < ? AND to_stop_seq > ?"
                + " ORDER BY berth_id FOR UPDATE";
        Set<Long> held = new LinkedHashSet<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, journeyId);
            statement.setInt(2, toSeq);
            statement.setInt(3, fromSeq);
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    held.add(results.getLong(1));
                }
            }
        }
        return held;
    }

    /**
     * Whether this berth is held by any reservation overlapping the segment.
     *
     * <p>Read-only, and <strong>not</strong> for allocation decisions: it is a
     * plain SELECT, so inside a REPEATABLE READ transaction it answers from the
     * snapshot and would not see a concurrent commit. Allocation uses the
     * segment-scoped occupancy carried by {@link #lockCandidateBerths}.
     *
     * <p>Its job is independent verification - the self-check calls it to confirm
     * after the fact that no reservation overlaps another on any berth, using
     * the same comparison the allocator used rather than trusting the
     * allocator's own bookkeeping.
     */
    public boolean isBerthOccupiedOnSegment(long berthId, int fromSeq, int toSeq)
            throws SQLException {
        String sql = "SELECT 1 FROM seat_reservations"
                + " WHERE berth_id = ? AND status IN " + OCCUPYING
                + "   AND from_stop_seq < ? AND to_stop_seq > ? LIMIT 1";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, berthId);
            statement.setInt(2, toSeq);
            statement.setInt(3, fromSeq);
            try (ResultSet results = statement.executeQuery()) {
                return results.next();
            }
        }
    }

/**
 * A berth the journey has, with whether it is currently held.
 *
 * @param berthId primary key of the berth
 * @param coachId the coach it belongs to
 * @param coachNumber the coach's number as passengers know it, e.g. {@code AC1}
 * @param berthNumber its number within that coach, 1-based
 * @param berthType {@code SEAT}, {@code SLEEPER} or {@code AC_BERTH}
 * @param position which position it is: {@code LOWER}, {@code MIDDLE},
 *     {@code UPPER}, {@code WINDOW}, {@code AISLE} or {@code SIDE}
 * @param occupied true when a reservation already holds it on the segment
 *     the caller asked about, not merely somewhere on the journey
 */
    public record CandidateBerth(long berthId,
                                 long coachId,
                                 String coachNumber,
                                 int berthNumber,
                                 String berthType,
                                 String position,
                                 boolean occupied) {
    }

    /**
     * How many berths a class has on a segment, and how many are free.
     *
     * @param total berths attached to the journey in this class, held ones
     *     included
     * @param available how many of those are free for the whole segment
     */
    public record BerthCounts(int total, int available) {
    }

    /**
     * Counts a class's berths on a segment, for the search screen.
     *
     * <p>Static, and taking a {@link ConnectionSource} rather than a bound
     * connection, because the search screen reads outside any transaction and
     * simply wants a connection for the length of one query. The booking path
     * cannot use this: it needs the row-level locks that
     * {@link #lockCandidateBerths} takes.
     *
     * <p>Uses the same overlap comparison as the booking path, so the number a
     * passenger searches on and the berths they are offered cannot disagree.
     */
    public static BerthCounts countForSegment(ConnectionSource connections, long journeyId,
                                              String classCode, int fromSeq, int toSeq)
            throws SQLException {
        String sql =
                "SELECT COUNT(*) AS total_berths, "
                + "       COALESCE(SUM(CASE WHEN NOT EXISTS ("
                + "           SELECT 1 FROM seat_reservations sr "
                + "           WHERE sr.journey_id = jc.journey_id "
                + "             AND sr.berth_id = b.berth_id "
                + "             AND sr.status IN " + OCCUPYING + " "
                + "             AND sr.from_stop_seq < ? "
                + "             AND sr.to_stop_seq > ? "
                + "       ) THEN 1 ELSE 0 END), 0) AS available_berths "
                + "FROM journey_coaches jc "
                + "JOIN coaches c ON c.coach_id = jc.coach_id "
                + "JOIN berths b ON b.coach_id = c.coach_id "
                + "WHERE jc.journey_id = ? AND jc.coach_status = 'ATTACHED' "
                + "  AND c.class_code = ?";
        try (Connection c = connections.open();
             PreparedStatement statement = c.prepareStatement(sql)) {
            statement.setInt(1, toSeq);
            statement.setInt(2, fromSeq);
            statement.setLong(3, journeyId);
            statement.setString(4, classCode);
            try (ResultSet results = statement.executeQuery()) {
                if (!results.next()) {
                    return new BerthCounts(0, 0);
                }
                return new BerthCounts(results.getInt("total_berths"),
                                       results.getInt("available_berths"));
            }
        }
    }
}