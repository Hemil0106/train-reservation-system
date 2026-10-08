package com.busticket.data;

import com.busticket.model.Bus;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * The sample bus schedules used by the application.
 *
 * There is no database, so the schedules are written here in code.
 */
public class BusData {

    /**
     * The buses are created once, the first time this class is used, and then
     * kept for the whole run. Every screen calls getAllBuses() and gets these
     * same Bus objects, so a seat booked on one screen is also seen as booked
     * on the next one.
     */
    private static final List<Bus> BUSES = createSampleBuses();

    /** This class only hands out data, so it is never created. */
    private BusData() {
    }

    /** All sample buses, shared by every screen. Do not modify the list. */
    public static List<Bus> getAllBuses() {
        return BUSES;
    }

    /**
     * The buses that run one route, for example Mumbai to Pune.
     *
     * The returned list is a new list, but it holds the very same Bus objects
     * as getAllBuses(), so a seat booked here is still seen as booked elsewhere.
     */
    public static List<Bus> getBusesByRoute(String source, String destination) {
        List<Bus> matching = new ArrayList<Bus>();

        for (Bus bus : BUSES) {
            if (bus.getSource().equals(source) && bus.getDestination().equals(destination)) {
                matching.add(bus);
            }
        }
        return matching;
    }

    private static List<Bus> createSampleBuses() {
        List<Bus> buses = new ArrayList<Bus>();

        // Mumbai to Pune
        buses.add(new Bus("BUS01", "Shivneri Express", "Mumbai", "Pune",
                LocalTime.of(6, 0), LocalTime.of(9, 0), 350));
        buses.add(new Bus("BUS02", "VRL Luxury", "Mumbai", "Pune",
                LocalTime.of(18, 30), LocalTime.of(21, 45), 450));

        // Pune to Mumbai
        buses.add(new Bus("BUS03", "Neeta Travels", "Pune", "Mumbai",
                LocalTime.of(7, 15), LocalTime.of(10, 30), 350));
        buses.add(new Bus("BUS04", "Orange Tours", "Pune", "Mumbai",
                LocalTime.of(20, 0), LocalTime.of(23, 15), 500));

        // Mumbai to Nashik
        buses.add(new Bus("BUS05", "Rajhans Express", "Mumbai", "Nashik",
                LocalTime.of(5, 45), LocalTime.of(8, 15), 200));
        buses.add(new Bus("BUS06", "Galaxy Travels", "Mumbai", "Nashik",
                LocalTime.of(16, 0), LocalTime.of(18, 30), 275));

        // Nashik to Mumbai
        buses.add(new Bus("BUS07", "Sai Travels", "Nashik", "Mumbai",
                LocalTime.of(6, 30), LocalTime.of(9, 15), 220));
        buses.add(new Bus("BUS08", "Konduskar", "Nashik", "Mumbai",
                LocalTime.of(19, 0), LocalTime.of(21, 45), 300));

        // Mumbai to Surat
        buses.add(new Bus("BUS09", "Yash Travels", "Mumbai", "Surat",
                LocalTime.of(8, 0), LocalTime.of(12, 0), 400));
        // Night service: leaves at 21:00 and reaches Surat at 01:00 the next day.
        buses.add(new Bus("BUS10", "Pooja Travels", "Mumbai", "Surat",
                LocalTime.of(21, 0), LocalTime.of(1, 0), 550));

        // Pune to Nashik
        buses.add(new Bus("BUS11", "Shivneri Express", "Pune", "Nashik",
                LocalTime.of(9, 0), LocalTime.of(11, 30), 250));
        buses.add(new Bus("BUS12", "VRL Luxury", "Pune", "Nashik",
                LocalTime.of(17, 30), LocalTime.of(20, 0), 325));

        return buses;
    }
}