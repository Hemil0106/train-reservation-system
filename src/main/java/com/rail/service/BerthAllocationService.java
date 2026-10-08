package com.rail.service;

import com.rail.db.AvailabilityDAO.CandidateBerth;
import com.rail.model.BerthAllocation;
import com.rail.model.BerthPreference;
import com.rail.model.PassengerDraft;

import java.util.ArrayList;
import java.util.List;

/**
 * Decides which berth each passenger gets.
 *
 * <p>This class holds the whole allocation policy, in one readable method,
 * because a berth policy nobody can follow is a berth policy nobody can trust.
 * It does no SQL: the caller hands it the journey's berths, already locked and
 * already marked with whether they are held, so the decision itself is pure and
 * can be checked without a database.
 *
 * <h2>The policy</h2>
 *
 * <ol>
 *   <li><b>Preferences get first refusal.</b> Every passenger who asked for
 *       something is placed before anyone who did not. This ordering is the
 *       difference between a fair result and a quietly bad one: allocate in
 *       passenger order alone and the first passenger to leave the berth field
 *       alone takes the only lower berth on the coach, leaving the passenger who
 *       actually asked for lower with an upper. Priority by expressed preference
 *       costs two passes and removes that unfairness.</li>
 *   <li><b>Within a pass, first free berth in coach order.</b> Coaches in
 *       position order, then berth number. Filling front to back keeps a party
 *       together and makes the result repeatable - the same request against the
 *       same inventory always allocates the same berths.</li>
 *   <li><b>Unmet preference falls back to any free berth.</b> A passenger who
 *       asked for lower and cannot have it travels in whatever is free rather
 *       than being refused a seat they could have had. The fallback is recorded,
 *       never hidden: {@link BerthAllocation#preferenceHonoured()} is false, and
 *       the confirmation screen says so in words.</li>
 *   <li><b>All or nothing.</b> If any passenger cannot be seated, the whole
 *       booking is refused. There is no partial booking and no waitlist, so a
 *       traveller is never left believing two of their three seats exist.</li>
 * </ol>
 *
 * <p>A berth held on a segment that does <em>not</em> overlap the one being
 * booked is free and may be reused - the same physical berth can legitimately be
 * sold to the next leg. That decision belongs to
 * {@link AvailabilityDAO#lockCandidateBerths}, which is why this class never has
 * to think about intervals at all.
 *
 * <p>Nothing here guarantees a requested berth. Allocation is a best effort over
 * what is free at this instant, inside a transaction that stops anyone else
 * taking the same berth while it decides.
 */
public final class BerthAllocationService {

    /**
     * Allocates one berth per passenger.
     *
     * @param passengers the party, in the order it will be shown
     * @param candidates every berth of the class on this journey, already locked
     *     and already marked held or free <em>for this segment</em>
     * @return one allocation per passenger, in the same order, so the caller can
     *     zip it against the passenger rows it is about to write
     * @throws BookingException if the party cannot be seated in full
     */
    public List<BerthAllocation> allocate(List<PassengerDraft> passengers,
                                          List<CandidateBerth> candidates)
            throws BookingException {
        if (passengers.isEmpty()) {
            throw new IllegalArgumentException("a booking needs at least one passenger");
        }

        List<CandidateBerth> free = new ArrayList<>();
        for (CandidateBerth candidate : candidates) {
            if (!candidate.occupied()) {
                free.add(candidate);
            }
        }
        // Counted before the passes drain the list, because "only N berths are
        // free" has to mean how many there were when the booking started.
        int freeBerths = free.size();

        // berthIds[i] is the berth given to passengers.get(i), null while unplaced.
        CandidateBerth[] berthIds = new CandidateBerth[passengers.size()];

        placeRequestedPositionsFirst(passengers, free, berthIds);
        placeEveryoneElse(passengers, free, berthIds);

        return describe(passengers, berthIds, freeBerths);
    }

    /**
     * Pass one: everyone who asked for a position gets one, if a free berth of
     * that position exists.
     *
     * <p>{@code NO_PREFERENCE} is not a position, so those passengers are left for
     * pass two.
     */
    private void placeRequestedPositionsFirst(List<PassengerDraft> passengers,
                                                 List<CandidateBerth> free,
                                                 CandidateBerth[] berthIds)
            throws BookingException {
        for (int index = 0; index < passengers.size(); index++) {
            BerthPreference wanted = passengers.get(index).berthPreference();
            if (wanted == BerthPreference.NO_PREFERENCE) {
                continue;
            }
            berthIds[index] = takeFirst(free, candidate ->
                    BerthPreference.fromPosition(candidate.position()).dbValue()
                            .equals(wanted.dbValue()));
        }
    }

    /**
     * Pass two: everybody still without a berth takes whatever is left, in coach
     * order.
     *
     * <p>This covers the passengers who expressed no preference and the ones
     * whose preference could not be met. They are treated identically on purpose
     * - a fallback seat is a real seat, and giving it second-class treatment
     * would be inventing a distinction the application has no basis for.
     */
    private void placeEveryoneElse(List<PassengerDraft> passengers,
                                   List<CandidateBerth> free,
                                   CandidateBerth[] berthIds) {
        for (int index = 0; index < passengers.size(); index++) {
            if (berthIds[index] != null) {
                continue;
            }
            berthIds[index] = takeFirst(free, candidate -> true);
        }
    }

    /** The first free berth matching {@code wanted}, removing it from the pool. */
    private static CandidateBerth takeFirst(List<CandidateBerth> free,
                                            java.util.function.Predicate<CandidateBerth> wanted) {
        for (int index = 0; index < free.size(); index++) {
            if (wanted.test(free.get(index))) {
                // Removing rather than marking keeps every later pass from
                // handing out the same berth twice.
                return free.remove(index);
            }
        }
        return null;
    }

    /** Pairs each passenger with the berth chosen for them. */
    private List<BerthAllocation> describe(List<PassengerDraft> passengers,
                                           CandidateBerth[] berthIds,
                                           int freeBerths)
            throws BookingException {
        List<BerthAllocation> allocations = new ArrayList<>(passengers.size());
        for (int index = 0; index < passengers.size(); index++) {
            CandidateBerth berth = berthIds[index];
            if (berth == null) {
                throw insufficient(freeBerths, passengers.size(), index);
            }
            PassengerDraft passenger = passengers.get(index);
            allocations.add(new BerthAllocation(
                    passenger.fullName(),
                    passenger.age(),
                    passenger.gender(),
                    passenger.berthPreference(),
                    berth.berthId(),
                    berth.coachId(),
                    berth.coachNumber(),
                    berth.berthNumber(),
                    berth.berthType(),
                    BerthPreference.fromPosition(berth.position())));
        }
        return allocations;
    }

    /**
     * The refusal, naming what was being asked for.
     *
     * <p>Says how many berths are free and how many people there are, because
     * "no seats available" on its own leaves a passenger unable to tell a full
     * train from a five-berth class they asked to put nine people in. The count
     * is of <em>free</em> berths, so a class that exists but is already full
     * reports the truth rather than its own size.
     */
    private static BookingException insufficient(int freeBerths, int wanted, int seated) {
        StringBuilder message = new StringBuilder();
        if (freeBerths == 0) {
            message.append("No berths are free in this class for this segment")
                    .append(", so the booking cannot be made.");
        } else {
            message.append(freeBerths == 1 ? "Only 1 berth is" : "Only " + freeBerths + " berths are");
            message.append(" free in this class for this segment, but ").append(wanted)
                    .append(wanted == 1 ? " passenger is" : " passengers are")
                    .append(" travelling. No seats were reserved.");
        }
        if (seated > 0) {
            message.append(" ").append(seated).append(" of the party could have been seated, ")
                    .append("but a partial booking is not created.");
        }
        return new BookingException(message.toString());
    }
}