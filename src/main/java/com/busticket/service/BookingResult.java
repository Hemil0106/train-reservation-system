package com.busticket.service;

import com.busticket.model.Booking;

/**
 * The answer from BookingService: did the booking happen, and if not, why not?
 *
 * A failure never carries a booking, so the UI can safely show whatever is
 * in here without having to guess whether anything was reserved.
 */
public class BookingResult {

    private final boolean success;
    private final String message;
    private final Booking booking;

    private BookingResult(boolean success, String message, Booking booking) {
        this.success = success;
        this.message = message;
        this.booking = booking;
    }

    /** The booking is committed, so getBooking() is safe to read. */
    public static BookingResult success(Booking booking) {
        return new BookingResult(true, null, booking);
    }

    /** Nothing was reserved, and message says why. getBooking() is null. */
    public static BookingResult failure(String message) {
        return new BookingResult(false, message, null);
    }

    public boolean isSuccess() {
        return success;
    }

    /** Why it failed. Null after a success. */
    public String getMessage() {
        return message;
    }

    /** The committed booking. Null after a failure. */
    public Booking getBooking() {
        return booking;
    }
}