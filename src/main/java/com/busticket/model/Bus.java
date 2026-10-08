package com.busticket.model;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * One bus service: a named journey from a source city to a destination
 * city, with a fixed fare and its own set of 20 seats.
 */
public class Bus {

    /** Every bus has 20 seats, numbered 1 to 20. */
    public static final int SEAT_COUNT = 20;

    private String busId;
    private String busName;
    private String source;
    private String destination;
    private LocalTime departureTime;
    private LocalTime arrivalTime;

    /** Fare for one seat, in whole rupees (never a floating point value). */
    private int farePerSeat;

    /**
     * This list belongs to this bus instance only. It is created in the
     * constructor and never replaced, so seat states stay independent
     * between different buses.
     */
    private final List<Seat> seats = new ArrayList<Seat>();

    public Bus(String busId, String busName, String source, String destination,
               LocalTime departureTime, LocalTime arrivalTime, int farePerSeat) {
        this.busId = busId;
        this.busName = busName;
        this.source = source;
        this.destination = destination;
        this.departureTime = departureTime;
        this.arrivalTime = arrivalTime;
        this.farePerSeat = farePerSeat;

        for (int seatNumber = 1; seatNumber <= SEAT_COUNT; seatNumber++) {
            seats.add(new Seat(seatNumber));
        }
    }

    public String getBusId() {
        return busId;
    }

    public void setBusId(String busId) {
        this.busId = busId;
    }

    public String getBusName() {
        return busName;
    }

    public void setBusName(String busName) {
        this.busName = busName;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getDestination() {
        return destination;
    }

    public void setDestination(String destination) {
        this.destination = destination;
    }

    public LocalTime getDepartureTime() {
        return departureTime;
    }

    public void setDepartureTime(LocalTime departureTime) {
        this.departureTime = departureTime;
    }

    public LocalTime getArrivalTime() {
        return arrivalTime;
    }

    public void setArrivalTime(LocalTime arrivalTime) {
        this.arrivalTime = arrivalTime;
    }

    public int getFarePerSeat() {
        return farePerSeat;
    }

    public void setFarePerSeat(int farePerSeat) {
        this.farePerSeat = farePerSeat;
    }

    /** The live list of this bus's 20 seats. Do not replace it, only read it. */
    public List<Seat> getSeats() {
        return seats;
    }

    /** Counts how many seats are still free. */
    public int countAvailableSeats() {
        int available = 0;
        for (Seat seat : seats) {
            if (seat.isAvailable()) {
                available++;
            }
        }
        return available;
    }

    /** The fare for the given number of seats on this bus. */
    public int getTotalFare(int seatCount) {
        return farePerSeat * seatCount;
    }

    /** "Mumbai - Pune", used to group buses by route. */
    public String getRoute() {
        return source + " - " + destination;
    }

    @Override
    public String toString() {
        return busId + " " + busName + " (" + getRoute() + ")";
    }
}