package com.busticket.model;

import java.time.LocalDate;
import java.util.List;

/**
 * A passenger's ticket booking: who is travelling, on which bus, on
 * which date and in which seats.
 */
public class Booking {

    private String bookingId;
    private String passengerName;
    private String mobileNumber;

    /** Optional - left null when the passenger does not give one. */
    private String email;

    private LocalDate journeyDate;

    /** The bus that was chosen, and the seats that were chosen on it. */
    private final Bus bus;
    private final List<Integer> seatNumbers;

    public Booking(String bookingId, String passengerName, String mobileNumber,
                   LocalDate journeyDate, Bus bus, List<Integer> seatNumbers) {
        this.bookingId = bookingId;
        this.passengerName = passengerName;
        this.mobileNumber = mobileNumber;
        this.journeyDate = journeyDate;
        this.bus = bus;
        this.seatNumbers = seatNumbers;
    }

    public String getBookingId() {
        return bookingId;
    }

    public String getPassengerName() {
        return passengerName;
    }

    public void setPassengerName(String passengerName) {
        this.passengerName = passengerName;
    }

    public String getMobileNumber() {
        return mobileNumber;
    }

    public void setMobileNumber(String mobileNumber) {
        this.mobileNumber = mobileNumber;
    }

    public String getEmail() {
        return email;
    }

    /** Optional, so this may be called with null to leave it blank. */
    public void setEmail(String email) {
        this.email = email;
    }

    public LocalDate getJourneyDate() {
        return journeyDate;
    }

    public void setJourneyDate(LocalDate journeyDate) {
        this.journeyDate = journeyDate;
    }

    public Bus getBus() {
        return bus;
    }

    public List<Integer> getSeatNumbers() {
        return seatNumbers;
    }

    /**
     * The route is worked out from the chosen bus instead of being stored
     * separately, so it can never disagree with the bus.
     */
    public String getRoute() {
        return bus.getRoute();
    }

    /**
     * Fare per seat multiplied by the number of chosen seats. Also worked
     * out rather than stored, so it is always correct.
     */
    public int getTotalFare() {
        return bus.getTotalFare(seatNumbers.size());
    }
}