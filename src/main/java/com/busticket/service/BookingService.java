package com.busticket.service;

import com.busticket.model.Bus;
import com.busticket.model.Booking;
import com.busticket.model.Seat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Does the actual booking work and keeps the records.
 *
 * A bus runs every day, so a seat being taken on one date must not take it on
 * any other date. Booked seats are therefore held per journey - a journey
 * being one bus on one date - in JOURNEY_SEATS below, keyed by bus id and
 * date. The Seat objects on a Bus only say whether the seat itself is usable,
 * which is a property of the bus rather than of a date.
 *
 * A booking is all or nothing: every requested seat is checked first, and
 * the seats are only taken once all of them have passed. A failure therefore
 * never leaves half a group booked.
 *
 * Mobile number rule: an Indian mobile number is 10 digits long and starts
 * with 6, 7, 8 or 9. Spaces, dashes and a leading "+91" or "91" country
 * code are all accepted and removed first, so 9876543210, "+91 98765 43210"
 * and 919876543210 are the same number and are all stored as 9876543210.
 *
 * Email rule: optional, but when one is given it must have some text, a
 * single "@", then a domain with a dot that has text on both sides - so
 * "asha@example.com" is accepted and "asha@example" is not.
 *
 * ponytail: records live in memory and every method is unsynchronised, which
 * is fine for the one Swing thread. Make the maps instance fields and add
 * synchronized if a server ever shares this between passengers.
 */
public class BookingService {

    /** Booking ids run from BT1001 upwards. */
    private static int nextBookingNumber = 1001;

    /** Every committed booking, oldest first, keyed by booking id. */
    private static final Map<String, Booking> BOOKINGS = new LinkedHashMap<String, Booking>();

    /** Booked seat numbers for one journey, keyed by journeyKey(bus, date). */
    private static final Map<String, Set<Integer>> JOURNEY_SEATS =
            new HashMap<String, Set<Integer>>();

    private BookingService() {
        // This class only provides the booking operations, so it is never created.
    }

    /**
     * Books the seats and records the booking, or explains why it could not.
     *
     * This is the only way seats get taken. It never throws and never shows
     * anything itself - the caller decides what to do with the result.
     */
    public static BookingResult createBooking(String passengerName, String mobile, String email,
                                              LocalDate journeyDate, Bus bus,
                                              List<Integer> seatNumbers) {
        // 1. The passenger's own details.
        String problem = validatePassengerName(passengerName);
        if (problem == null) {
            problem = validateMobile(mobile);
        }
        if (problem == null) {
            problem = validateEmail(email);
        }
        if (problem != null) {
            return BookingResult.failure(problem);
        }

        // 3 and 4. Check the bus, the date and every seat through the same
        // public rules the rest of the app uses, so there is only ever one
        // version of the seat rules. This runs immediately before the commit
        // below and before anything has been changed, which makes it the last
        // look at availability: if it passes, nothing between here and the
        // commit can fail, so a booking never gets part-committed.
        problem = validateSelection(bus, journeyDate, seatNumbers);
        if (problem != null) {
            return BookingResult.failure(problem);
        }

        // A TreeSet drops any repeat and puts the seats in order, so a seat
        // can never be counted twice towards the fare or taken twice.
        TreeSet<Integer> seats = new TreeSet<Integer>(seatNumbers);

        // 5, 6 and 7. Everything passed, so commit: take the id, build the
        // booking, store it and only then reserve the seats.
        String bookingId = String.format("BT%d", nextBookingNumber);
        nextBookingNumber++;

        Booking booking = new Booking(bookingId, passengerName.trim(),
                normaliseMobile(mobile), journeyDate, bus, new ArrayList<Integer>(seats));
        booking.setEmail(email == null || email.trim().isEmpty() ? null : email.trim());

        BOOKINGS.put(bookingId, booking);

        Set<Integer> taken = JOURNEY_SEATS.get(journeyKey(bus, journeyDate));
        if (taken == null) {
            taken = new HashSet<Integer>();
            JOURNEY_SEATS.put(journeyKey(bus, journeyDate), taken);
        }
        taken.addAll(seats);

        return BookingResult.success(booking);
    }

    /** Was this seat already taken on this bus on this date? */
    public static boolean isSeatBooked(Bus bus, LocalDate journeyDate, int seatNumber) {
        Set<Integer> taken = JOURNEY_SEATS.get(journeyKey(bus, journeyDate));
        return taken != null && taken.contains(seatNumber);
    }

    /** The seat numbers booked on this bus on this date, in order. */
    public static List<Integer> getBookedSeats(Bus bus, LocalDate journeyDate) {
        Set<Integer> taken = JOURNEY_SEATS.get(journeyKey(bus, journeyDate));
        if (taken == null) {
            return new ArrayList<Integer>();
        }
        return new ArrayList<Integer>(new TreeSet<Integer>(taken));
    }

    /**
     * How many seats are still free on this bus on this date: the ones the
     * bus itself can offer, minus the ones already booked for that date.
     */
    public static int countAvailableSeats(Bus bus, LocalDate journeyDate) {
        int free = 0;
        for (Seat seat : bus.getSeats()) {
            if (seat.isAvailable() && !isSeatBooked(bus, journeyDate, seat.getSeatNumber())) {
                free++;
            }
        }
        return free;
    }

    /** Every committed booking, oldest first. */
    public static List<Booking> getAllBookings() {
        return new ArrayList<Booking>(BOOKINGS.values());
    }

    /** Looks a booking up by its id, or null when there is no such id. */
    public static Booking findBooking(String bookingId) {
        return BOOKINGS.get(bookingId);
    }

    /** One journey is one bus on one date, e.g. "BUS03|2026-10-07". */
    private static String journeyKey(Bus bus, LocalDate journeyDate) {
        return bus.getBusId() + "|" + journeyDate;
    }

    private static String format(Integer seatNumber) {
        return String.format("%02d", seatNumber);
    }

    /** Null when the name is fine, otherwise the message to show. */
    public static String validatePassengerName(String name) {
        if (name == null || name.trim().isEmpty()) {
            return "Passenger name is required.";
        }
        return null;
    }

    /** Null when the mobile number is fine, otherwise the message to show. */
    public static String validateMobile(String mobile) {
        if (mobile == null || mobile.trim().isEmpty()) {
            return "Mobile number is required.";
        }

        String digits = normaliseMobile(mobile);

        if (digits.length() != 10 || !isAllDigits(digits)) {
            return "Enter a 10-digit mobile number.";
        }
        if (digits.charAt(0) < '6' || digits.charAt(0) > '9') {
            return "An Indian mobile number starts with 6, 7, 8 or 9.";
        }
        return null;
    }

    /**
     * Strips spaces, dashes and an optional +91 / 91 country code, leaving
     * the bare 10 digits. Only removes the country code when 10 digits are
     * left, so a number that genuinely begins 91 is not damaged.
     */
    public static String normaliseMobile(String mobile) {
        String digits = mobile.trim().replace(" ", "").replace("-", "");

        if (digits.startsWith("+91")) {
            String rest = digits.substring(3);
            if (isTenDigits(rest)) {
                digits = rest;
            }
        } else if (digits.startsWith("91")) {
            String rest = digits.substring(2);
            if (isTenDigits(rest)) {
                digits = rest;
            }
        }
        return digits;
    }

    /** Null when the email is fine (including blank), otherwise the message. */
    public static String validateEmail(String email) {
        if (email == null || email.trim().isEmpty()) {
            return null; // email is optional
        }

        String text = email.trim();
        int at = text.indexOf('@');

        // Need text before the @, and only one @.
        if (at <= 0 || text.indexOf('@', at + 1) >= 0) {
            return "Enter an email address like name@example.com.";
        }

        // The domain needs a dot with text on both sides of it.
        String domain = text.substring(at + 1);
        int dot = domain.indexOf('.');
        if (dot <= 0 || dot == domain.length() - 1) {
            return "Enter an email address like name@example.com.";
        }
        return null;
    }

    /**
     * Checks the chosen bus, date and seats without booking anything.
     *
     * Returns null when the selection may be booked, otherwise the first
     * problem found. This is the one place the seat rules are written, so
     * checking on screen and committing can never disagree.
     */
    public static String validateSelection(Bus bus, LocalDate journeyDate,
                                           List<Integer> seatNumbers) {
        if (bus == null) {
            return "Choose a bus before confirming.";
        }
        if (journeyDate == null) {
            return "Choose a journey date before confirming.";
        }
        if (seatNumbers == null || seatNumbers.isEmpty()) {
            return "Select at least one seat before confirming.";
        }

        for (Integer seatNumber : seatNumbers) {
            if (seatNumber == null || seatNumber < 1 || seatNumber > Bus.SEAT_COUNT) {
                return "Seat " + seatNumber + " does not exist on this bus.";
            }
            if (!bus.getSeats().get(seatNumber - 1).isAvailable()) {
                return "Seat " + format(seatNumber) + " cannot be used on this bus.";
            }
            if (isSeatBooked(bus, journeyDate, seatNumber)) {
                return "Seat " + format(seatNumber) + " is already booked for "
                        + journeyDate + ".";
            }
        }
        return null;
    }

    private static boolean isTenDigits(String text) {
        return text.length() == 10 && isAllDigits(text);
    }

    private static boolean isAllDigits(String text) {
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (character < '0' || character > '9') {
                return false;
            }
        }
        return true;
    }
}