package com.busticket.model;

import java.util.List;

/**
 * One seat on a bus.
 *
 * Each Bus builds its own Seat objects, so booking a seat on one bus
 * never affects the same seat number on another bus.
 */
public class Seat {

    /** Seat numbers run 1 to 20; the real number never changes. */
    private final int seatNumber;

    /** true = free to book, false = already booked. */
    private boolean available;

    public Seat(int seatNumber) {
        this.seatNumber = seatNumber;
        this.available = true; // every seat starts free
    }

    public int getSeatNumber() {
        return seatNumber;
    }

    /** Seat numbers are shown with a leading zero, so 1 is displayed as "01". */
    public String getDisplayNumber() {
        return String.format("%02d", seatNumber);
    }

    /** Is this seat still free? */
    public boolean isAvailable() {
        return available;
    }

    /** Marks the seat as booked (false) or frees it again (true). */
    public void setAvailable(boolean available) {
        this.available = available;
    }

    /** Turns a list of seat numbers into display text such as "03, 07, 12". */
    public static String formatSeatNumbers(List<Integer> seatNumbers) {
        StringBuilder text = new StringBuilder();

        for (Integer seatNumber : seatNumbers) {
            if (text.length() > 0) {
                text.append(", ");
            }
            text.append(String.format("%02d", seatNumber));
        }
        return text.toString();
    }

    @Override
    public String toString() {
        return "Seat " + getDisplayNumber() + (available ? " (available)" : " (booked)");
    }
}