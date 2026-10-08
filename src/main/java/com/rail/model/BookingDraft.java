package com.rail.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The in-progress booking: one selected train plus the passengers typed so far.
 *
 * <p>This is deliberately a plain in-memory object with no database access. The
 * brief requires that a prepared draft never looks like a confirmed booking, and
 * the cheapest way to guarantee that is for the type to have no connection to
 * one. There is no save method here on purpose; nothing can accidentally
 * persist a draft because nothing here can reach the database.
 *
 * <p>Because the draft lives in memory, it is lost when the application exits.
 * That is honest for this stage: a draft that survived a restart would need
 * somewhere to live, and every such place is a bookings-shaped table.
 *
 * <p>A draft belongs to exactly one selected train and is never re-pointed at
 * another. Choosing a different train starts a new draft, because carrying a 2A
 * passenger list over to a 1A train would offer preferences that train cannot
 * honour.
 */
public final class BookingDraft {

    private final TrainSelection selection;
    private final List<PassengerDraft> passengers = new ArrayList<>();
    private BookingContact contact;

    public BookingDraft(TrainSelection selection) {
        if (selection == null) {
            throw new IllegalArgumentException("A booking draft needs a selected train.");
        }
        this.selection = selection;
    }

    public TrainSelection selection() {
        return selection;
    }

    public void addPassenger(PassengerDraft passenger) {
        passengers.add(passenger);
    }

    /**
     * Removes the passenger at {@code index}.
     *
     * @throws IndexOutOfBoundsException if there is no such passenger, so a
     *     stale button press cannot silently remove the wrong person
     */
    public void removePassenger(int index) {
        passengers.remove(index);
    }

    /** An unmodifiable view, so the UI cannot reorder the list behind the service. */
    public List<PassengerDraft> passengers() {
        return Collections.unmodifiableList(new ArrayList<>(passengers));
    }

    public int passengerCount() {
        return passengers.size();
    }

    public BookingContact contact() {
        return contact;
    }

    public void setContact(BookingContact contact) {
        this.contact = contact;
    }
}