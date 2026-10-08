package com.busticket;

import com.busticket.data.BusData;
import com.busticket.model.Bus;
import com.busticket.model.Booking;
import com.busticket.model.Seat;
import com.busticket.service.BookingResult;
import com.busticket.service.BookingService;
import com.busticket.ui.MainFrame;
import com.busticket.ui.RoutePanel;
import com.busticket.ui.SeatSelectionPanel;

import java.awt.GraphicsEnvironment;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Plain self-check for the model classes and the sample data.
 *
 * It uses no test framework on purpose, so the project keeps zero
 * dependencies. Run it with:
 *
 *   javac -cp target\classes -d target\test-classes src\test\java\com\busticket\ModelSelfCheck.java
 *   java -cp "target\classes;target\test-classes" com.busticket.ModelSelfCheck
 */
public class ModelSelfCheck {

    private static int checksRun = 0;

    public static void main(String[] args) {
        List<Bus> buses = BusData.getAllBuses();

        check("sample data loads", !buses.isEmpty());
        check("all six routes are present", hasAllSixRoutes(buses));
        check("bus ids are unique", hasUniqueBusIds(buses));
        check("every bus has 20 seats", everyBusHasTwentySeats(buses));
        check("seat numbers are 1 to 20 and start free", seatNumbersAreCorrect(buses.get(0)));
        check("seat 1 shows as 01 and seat 20 as 20", displayNumbersAreCorrect(buses.get(0)));
        check("buses keep independent seat states", seatStatesAreIndependent(buses));
        check("Bus getters and setters work", busAccessorsWork());
        check("Seat status can be checked and changed", seatStatusCanBeChanged());
        check("Booking keeps passenger details", bookingKeepsPassengerDetails(buses.get(0)));
        check("Booking route comes from the bus", bookingRouteIsCorrect(buses.get(0)));
        check("Booking total fare is fare x seats", bookingTotalFareIsCorrect(buses.get(0)));

        checkRouteSearchRules();
        checkNavigationKeepsRouteAndDate();
        checkSeatSelection();
        checkPassengerValidation();
        checkBookingRules();

        System.out.println();
        System.out.println("All " + checksRun + " checks passed.");
    }

    private static boolean hasAllSixRoutes(List<Bus> buses) {
        String[] required = {"Mumbai - Pune", "Pune - Mumbai", "Mumbai - Nashik",
                "Nashik - Mumbai", "Mumbai - Surat", "Pune - Nashik"};

        for (String route : required) {
            if (!hasRoute(buses, route)) {
                System.out.println("   missing route: " + route);
                return false;
            }
        }
        return true;
    }

    private static boolean hasRoute(List<Bus> buses, String route) {
        for (Bus bus : buses) {
            if (bus.getRoute().equals(route)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasUniqueBusIds(List<Bus> buses) {
        Set<String> ids = new HashSet<String>();
        for (Bus bus : buses) {
            // add() returns false when the id is already in the set.
            if (!ids.add(bus.getBusId())) {
                System.out.println("   duplicate id: " + bus.getBusId());
                return false;
            }
        }
        return true;
    }

    private static boolean everyBusHasTwentySeats(List<Bus> buses) {
        for (Bus bus : buses) {
            if (bus.getSeats().size() != Bus.SEAT_COUNT) {
                System.out.println("   " + bus.getBusId() + " has " + bus.getSeats().size() + " seats");
                return false;
            }
            if (bus.countAvailableSeats() != Bus.SEAT_COUNT) {
                return false;
            }
        }
        return true;
    }

    private static boolean seatNumbersAreCorrect(Bus bus) {
        List<Seat> seats = bus.getSeats();
        for (int index = 0; index < seats.size(); index++) {
            Seat seat = seats.get(index);
            if (seat.getSeatNumber() != index + 1) {
                return false;
            }
            if (!seat.isAvailable()) {
                return false;
            }
        }
        return true;
    }

    private static boolean displayNumbersAreCorrect(Bus bus) {
        List<Seat> seats = bus.getSeats();
        return "01".equals(seats.get(0).getDisplayNumber())
                && "20".equals(seats.get(19).getDisplayNumber());
    }

    private static boolean seatStatesAreIndependent(List<Bus> buses) {
        Bus first = buses.get(0);
        Bus second = buses.get(1);

        // Book seat 1 on the first bus only.
        first.getSeats().get(0).setAvailable(false);

        if (!second.getSeats().get(0).isAvailable()) {
            System.out.println("   booking a seat on one bus changed another bus");
            return false;
        }
        if (!first.getSeats().get(1).isAvailable()) {
            System.out.println("   booking seat 1 also changed seat 2");
            return false;
        }
        if (first.countAvailableSeats() != 19) {
            return false;
        }
        return true;
    }

    private static boolean busAccessorsWork() {
        // A throwaway bus, so the shared sample data is left untouched.
        Bus bus = new Bus("TEST01", "Test Express", "Pune", "Nashik",
                LocalTime.of(9, 0), LocalTime.of(11, 30), 250);

        bus.setBusName("Renamed Express");
        bus.setFarePerSeat(300);
        bus.setSource("Mumbai");
        bus.setDestination("Surat");
        bus.setDepartureTime(LocalTime.of(10, 15));
        bus.setArrivalTime(LocalTime.of(14, 45));
        bus.setBusId("TEST02");

        return "Renamed Express".equals(bus.getBusName())
                && bus.getFarePerSeat() == 300
                && "Mumbai - Surat".equals(bus.getRoute())
                && bus.getDepartureTime().equals(LocalTime.of(10, 15))
                && bus.getArrivalTime().equals(LocalTime.of(14, 45))
                && "TEST02".equals(bus.getBusId());
    }

    private static boolean seatStatusCanBeChanged() {
        Seat seat = new Seat(7);

        if (!seat.isAvailable()) {
            return false; // a new seat must start free
        }
        if (!"07".equals(seat.getDisplayNumber())) {
            return false;
        }

        seat.setAvailable(false);
        if (seat.isAvailable()) {
            return false;
        }

        seat.setAvailable(true);
        return seat.isAvailable();
    }

    private static boolean bookingKeepsPassengerDetails(Bus bus) {
        List<Integer> seats = new ArrayList<Integer>(Arrays.asList(1, 2, 3));

        Booking booking = new Booking("BKG001", "Asha Patil", "9876543210",
                LocalDate.of(2026, 10, 5), bus, seats);

        if (!"Asha Patil".equals(booking.getPassengerName())
                || !"9876543210".equals(booking.getMobileNumber())
                || !"BKG001".equals(booking.getBookingId())
                || !LocalDate.of(2026, 10, 5).equals(booking.getJourneyDate())
                || booking.getBus() != bus
                || booking.getSeatNumbers().size() != 3) {
            return false;
        }
        if (booking.getEmail() != null) {
            return false; // email is optional and starts empty
        }

        booking.setPassengerName("Asha P. Patil");
        booking.setMobileNumber("9000000000");
        booking.setJourneyDate(LocalDate.of(2026, 10, 6));
        booking.setEmail("asha@example.com");

        return "Asha P. Patil".equals(booking.getPassengerName())
                && "9000000000".equals(booking.getMobileNumber())
                && LocalDate.of(2026, 10, 6).equals(booking.getJourneyDate())
                && "asha@example.com".equals(booking.getEmail());
    }

    private static boolean bookingRouteIsCorrect(Bus bus) {
        Booking booking = new Booking("BKG002", "Ravi Kumar", "9812345678",
                LocalDate.of(2026, 10, 5), bus, new ArrayList<Integer>());
        return bus.getRoute().equals(booking.getRoute());
    }

    private static boolean bookingTotalFareIsCorrect(Bus bus) {
        Booking one = new Booking("BKG003", "Ravi Kumar", "9812345678",
                LocalDate.of(2026, 10, 5), bus, new ArrayList<Integer>(Arrays.asList(4, 5)));
        if (one.getTotalFare() != bus.getFarePerSeat() * 2) {
            return false;
        }

        Booking none = new Booking("BKG004", "Ravi Kumar", "9812345678",
                LocalDate.of(2026, 10, 5), bus, new ArrayList<Integer>());
        return none.getTotalFare() == 0;
    }

    // ----- Route search rules (screen 1) ----------------------------------

    private static void checkRouteSearchRules() {
        String today = LocalDate.now().format(RoutePanel.DATE_FORMAT);
        String yesterday = LocalDate.now().minusDays(1).format(RoutePanel.DATE_FORMAT);

        check("valid Mumbai to Pune search is accepted",
                RoutePanel.findProblem("Mumbai", "Pune", today) == null);
        check("same city is rejected",
                RoutePanel.findProblem("Mumbai", "Mumbai", today) != null);
        check("blank date is rejected",
                RoutePanel.findProblem("Mumbai", "Pune", "") != null);
        check("date with wrong separators is rejected",
                RoutePanel.findProblem("Mumbai", "Pune", "25-12-2026") != null);
        check("impossible date 31/02/2026 is rejected",
                RoutePanel.findProblem("Mumbai", "Pune", "31/02/2026") != null);
        check("month 13 is rejected",
                RoutePanel.findProblem("Mumbai", "Pune", "05/13/2026") != null);
        check("yesterday is rejected",
                RoutePanel.findProblem("Mumbai", "Pune", yesterday) != null);
        check("route with no buses is rejected",
                RoutePanel.findProblem("Surat", "Pune", today) != null);
        check("missing source is rejected",
                RoutePanel.findProblem(null, "Pune", today) != null);
        check("all six routes are searchable and match their own buses",
                allSixRoutesAreSearchable(today));
    }

    private static boolean allSixRoutesAreSearchable(String today) {
        String[][] routes = {
                {"Mumbai", "Pune"}, {"Pune", "Mumbai"}, {"Mumbai", "Nashik"},
                {"Nashik", "Mumbai"}, {"Mumbai", "Surat"}, {"Pune", "Nashik"}};

        for (String[] route : routes) {
            if (RoutePanel.findProblem(route[0], route[1], today) != null) {
                System.out.println("   not searchable: " + route[0] + " to " + route[1]);
                return false;
            }
            // Every bus listed for a route must really be on that route.
            for (Bus bus : BusData.getBusesByRoute(route[0], route[1])) {
                if (!bus.getRoute().equals(route[0] + " - " + route[1])) {
                    return false;
                }
            }
        }
        return true;
    }

    // ----- Screen hand-over (screens 2 and 3) -----------------------------

    private static void checkNavigationKeepsRouteAndDate() {
        if (GraphicsEnvironment.isHeadless()) {
            System.out.println("SKIP  screen hand-over checks (no display available)");
            return;
        }

        MainFrame frame = new MainFrame();
        try {
            LocalDate date = LocalDate.now().plusDays(3);

            frame.showBusSelection("Pune", "Nashik", date);
            check("search keeps the chosen route",
                    "Pune".equals(frame.getChosenSource())
                            && "Nashik".equals(frame.getChosenDestination()));
            check("search keeps the chosen date", date.equals(frame.getJourneyDate()));

            // Take the second bus, the one a user would click "Select" on.
            List<Bus> matching = BusData.getBusesByRoute("Pune", "Nashik");
            Bus chosen = matching.get(1);
            frame.showSeatSelection(chosen, date);

            check("Select passes the exact Bus object", frame.getChosenBus() == chosen);
            check("Select passes the journey date", date.equals(frame.getJourneyDate()));

            frame.goBackToBusSelection();
            check("going back keeps route, date and bus",
                    "Pune".equals(frame.getChosenSource())
                            && "Nashik".equals(frame.getChosenDestination())
                            && date.equals(frame.getJourneyDate())
                            && frame.getChosenBus() == chosen);
        } finally {
            frame.dispose();
        }
    }

    // ----- Seat selection (screen 3) --------------------------------------

    private static void checkSeatSelection() {
        if (GraphicsEnvironment.isHeadless()) {
            System.out.println("SKIP  seat selection checks (no display available)");
            return;
        }

        MainFrame frame = new MainFrame();
        try {
            LocalDate date = LocalDate.now().plusDays(2);

            // Pune to Mumbai has two buses with different fares, Rs 350 and Rs 500.
            List<Bus> route = BusData.getBusesByRoute("Pune", "Mumbai");
            Bus cheap = route.get(0);
            Bus dear = route.get(1);

            SeatSelectionPanel panel = new SeatSelectionPanel(frame);
            panel.display(cheap, date);

            check("seat screen starts with nothing selected",
                    panel.getSelectedSeatNumbers().isEmpty());
            check("Continue is refused with no seats",
                    panel.findContinueProblem() != null);
            check("total fare starts at zero", panel.getTotalFare() == 0);

            // 1. one seat
            panel.toggleSeat(3);
            check("one seat selected", panel.getSelectedSeatNumbers().size() == 1);
            check("the right seat was selected", panel.getSelectedSeatNumbers().get(0) == 3);
            check("fare for one seat is the bus fare",
                    panel.getTotalFare() == cheap.getFarePerSeat());
            check("Continue is allowed once a seat is picked",
                    panel.findContinueProblem() == null);

            // 4. several seats
            panel.toggleSeat(11);
            panel.toggleSeat(12);
            check("three seats selected", panel.getSelectedSeatNumbers().size() == 3);
            check("seat numbers stay in order",
                    panel.getSelectedSeatNumbers().get(0) == 3
                            && panel.getSelectedSeatNumbers().get(1) == 11
                            && panel.getSelectedSeatNumbers().get(2) == 12);
            check("fare for three seats is fare x 3",
                    panel.getTotalFare() == cheap.getFarePerSeat() * 3);

            // 2. deselect
            panel.toggleSeat(11);
            check("deselecting removes only that seat",
                    panel.getSelectedSeatNumbers().size() == 2
                            && panel.getSelectedSeatNumbers().get(0) == 3
                            && panel.getSelectedSeatNumbers().get(1) == 12);
            check("fare drops when a seat is deselected",
                    panel.getTotalFare() == cheap.getFarePerSeat() * 2);

            // 6. a different bus must use its own fare, and start empty
            panel.display(dear, date);
            check("switching bus clears the selection",
                    panel.getSelectedSeatNumbers().isEmpty());
            panel.toggleSeat(5);
            panel.toggleSeat(6);
            check("the newly chosen bus fare is used",
                    panel.getTotalFare() == dear.getFarePerSeat() * 2);

            // 3. booked seats cannot be selected
            Bus partlyBooked = new Bus("TEST99", "Partly Booked Bus", "Pune", "Nashik",
                    LocalTime.of(9, 0), LocalTime.of(11, 30), 250);
            partlyBooked.getSeats().get(4).setAvailable(false); // seat 05

            SeatSelectionPanel bookedPanel = new SeatSelectionPanel(frame);
            bookedPanel.display(partlyBooked, date);
            bookedPanel.toggleSeat(5);
            check("a booked seat cannot be selected",
                    bookedPanel.getSelectedSeatNumbers().isEmpty());
            check("fare stays zero after picking a booked seat",
                    bookedPanel.getTotalFare() == 0);

            bookedPanel.toggleSeat(6);
            check("a free seat beside a booked one still works",
                    bookedPanel.getSelectedSeatNumbers().size() == 1
                            && bookedPanel.getTotalFare() == 250);

            // Selecting must never change the model's own seat states.
            Bus untouched = new Bus("TEST98", "Untouched Bus", "Mumbai", "Surat",
                    LocalTime.of(8, 0), LocalTime.of(12, 0), 400);
            SeatSelectionPanel cleanPanel = new SeatSelectionPanel(frame);
            cleanPanel.display(untouched, date);
            cleanPanel.toggleSeat(7);
            cleanPanel.toggleSeat(8);
            check("picking seats does not mark them booked",
                    untouched.getSeats().get(6).isAvailable()
                            && untouched.getSeats().get(7).isAvailable()
                            && untouched.countAvailableSeats() == Bus.SEAT_COUNT);

            // Coming back to the same journey keeps the chosen seats.
            Bus returnBus = new Bus("TEST97", "Return Bus", "Mumbai", "Pune",
                    LocalTime.of(7, 0), LocalTime.of(11, 0), 300);
            SeatSelectionPanel returnPanel = new SeatSelectionPanel(frame);
            returnPanel.display(returnBus, date);
            returnPanel.toggleSeat(4);
            returnPanel.toggleSeat(5);
            check("two seats picked before going back",
                    returnPanel.getSelectedSeatNumbers().size() == 2);

            returnPanel.display(returnBus, date);
            check("coming back to the same bus and date keeps the seats",
                    returnPanel.getSelectedSeatNumbers().size() == 2
                            && returnPanel.getTotalFare() == 600);

            returnPanel.display(returnBus, date.plusDays(1));
            check("coming back on a different date starts empty",
                    returnPanel.getSelectedSeatNumbers().isEmpty());

            // A seat taken by a booking must drop out of the selection.
            returnPanel.display(returnBus, date);
            returnPanel.toggleSeat(6);
            returnPanel.toggleSeat(7);
            BookingService.createBooking("Another Passenger", "9000000003", null,
                    date, returnBus, Arrays.asList(6));
            returnPanel.display(returnBus, date);
            check("a seat taken while away drops out of the selection",
                    returnPanel.getSelectedSeatNumbers().size() == 1
                            && returnPanel.getSelectedSeatNumbers().get(0) == 7);
            check("the fare follows the remaining seat", returnPanel.getTotalFare() == 300);
        } finally {
            frame.dispose();
        }
    }

    // ----- Passenger details (screen 4) -----------------------------------

    private static void checkPassengerValidation() {
        // 1. name
        check("empty name is rejected", BookingService.validatePassengerName("") != null);
        check("whitespace-only name is rejected",
                BookingService.validatePassengerName("     ") != null);
        check("null name is rejected", BookingService.validatePassengerName(null) != null);
        check("a real name is accepted",
                BookingService.validatePassengerName("Asha Patil") == null);

        // 2 and 3. mobile
        check("empty mobile is rejected", BookingService.validateMobile("") != null);
        check("blank mobile is rejected", BookingService.validateMobile("   ") != null);
        check("9-digit mobile is rejected", BookingService.validateMobile("987654321") != null);
        check("11-digit mobile is rejected", BookingService.validateMobile("98765432100") != null);
        check("mobile with letters is rejected",
                BookingService.validateMobile("98765abcde") != null);
        check("mobile starting with 5 is rejected",
                BookingService.validateMobile("5876543210") != null);
        check("10-digit mobile is accepted",
                BookingService.validateMobile("9876543210") == null);
        check("mobile starting with 6 is accepted",
                BookingService.validateMobile("6123456789") == null);
        check("+91 country code is accepted",
                BookingService.validateMobile("+919876543210") == null);
        check("91 country code is accepted",
                BookingService.validateMobile("919876543210") == null);
        check("+91 with spaces and dashes is accepted",
                BookingService.validateMobile("+91 98765-43210") == null);
        check("+91 is normalised away",
                "9876543210".equals(BookingService.normaliseMobile("+91 98765 43210")));
        check("91 is normalised away",
                "9876543210".equals(BookingService.normaliseMobile("919876543210")));
        check("a number genuinely starting 91 is left alone",
                "9198765432".equals(BookingService.normaliseMobile("9198765432"))
                        && BookingService.validateMobile("9198765432") == null);

        // 4. optional email
        check("blank email is allowed", BookingService.validateEmail("") == null);
        check("null email is allowed", BookingService.validateEmail(null) == null);
        check("a proper email is accepted",
                BookingService.validateEmail("asha@example.com") == null);
        check("email without @ is rejected",
                BookingService.validateEmail("asha.example.com") != null);
        check("email with two @ is rejected",
                BookingService.validateEmail("asha@ex@example.com") != null);
        check("email with no domain dot is rejected",
                BookingService.validateEmail("asha@example") != null);
        check("email with no name is rejected",
                BookingService.validateEmail("@example.com") != null);
        check("email with a trailing dot is rejected",
                BookingService.validateEmail("asha@example.") != null);
        check("email with a leading domain dot is rejected",
                BookingService.validateEmail("asha@.com") != null);
    }

    /**
     * The booking service itself: ids, records, per-date availability and
     * the all-or-nothing rule.
     */
    private static void checkBookingRules() {
        Bus bus = new Bus("TEST50", "Rule Check Bus", "Pune", "Nashik",
                LocalTime.of(9, 0), LocalTime.of(11, 30), 250);
        LocalDate day1 = LocalDate.of(2026, 10, 5);
        LocalDate day2 = LocalDate.of(2026, 10, 6);

        // Earlier checks may already have made bookings, so count from here.
        int recordsBefore = BookingService.getAllBookings().size();

        // 1 and 2. A booking id is issued, and looks like BT1001.
        BookingResult first = BookingService.createBooking("Asha Patil", "9876543210",
                "asha@example.com", day1, bus, Arrays.asList(2));
        String firstId = first.getBooking().getBookingId();
        check("a valid booking succeeds", first.isSuccess());
        check("a booking id looks like BT1001", firstId.matches("BT\\d{4}"));
        check("the booking keeps the passenger name",
                "Asha Patil".equals(first.getBooking().getPassengerName()));
        check("the booking normalises the mobile",
                "9876543210".equals(first.getBooking().getMobileNumber()));
        check("the booking keeps the email",
                "asha@example.com".equals(first.getBooking().getEmail()));
        check("the booking fare is fare x 1 seat", first.getBooking().getTotalFare() == 250);
        check("the booking is recorded",
                first.getBooking() == BookingService.findBooking(firstId));
        check("one seat is now booked on that date",
                BookingService.isSeatBooked(bus, day1, 2)
                        && !BookingService.isSeatBooked(bus, day1, 3));
        check("19 seats are left on that date",
                BookingService.countAvailableSeats(bus, day1) == 19);
        check("the model's own seat flags are untouched",
                bus.countAvailableSeats() == Bus.SEAT_COUNT);

        // 8. Booking the same seat again on the same date must fail.
        BookingResult clash = BookingService.createBooking("Ravi Joshi", "9123456780",
                null, day1, bus, Arrays.asList(2));
        check("an occupied seat cannot be booked again", !clash.isSuccess());
        check("the failure names the seat and the date",
                clash.getMessage().contains("02") && clash.getMessage().contains("2026-10-05"));
        check("a failed booking returns no booking", clash.getBooking() == null);
        check("a failed booking adds no record",
                BookingService.getAllBookings().size() == recordsBefore + 1);

        // 9. A group where only one seat is taken must book nothing at all.
        BookingResult mixed = BookingService.createBooking("Ravi Joshi", "9123456780",
                null, day1, bus, Arrays.asList(7, 2));
        check("a group containing an occupied seat fails", !mixed.isSuccess());
        check("the free seat in the failed group is still free",
                !BookingService.isSeatBooked(bus, day1, 7));
        check("the failed group booked no seats at all",
                BookingService.getBookedSeats(bus, day1).equals(Arrays.asList(2)));

        // 5. Two seats in one booking are charged twice.
        BookingResult pair = BookingService.createBooking("Ravi Joshi", "9123456780",
                null, day1, bus, Arrays.asList(7, 8));
        check("two seats in one booking succeed", pair.isSuccess());
        check("booking ids go up by one, and failures do not consume one",
                nextId(firstId).equals(pair.getBooking().getBookingId()));
        check("two seats are charged twice", pair.getBooking().getTotalFare() == 500);
        check("both seats are booked",
                BookingService.isSeatBooked(bus, day1, 7)
                        && BookingService.isSeatBooked(bus, day1, 8));
        check("17 seats are left on that date",
                BookingService.countAvailableSeats(bus, day1) == 17);

        // Repeat seat numbers must not be charged or booked twice.
        BookingResult repeated = BookingService.createBooking("Neha Rao", "9000000001",
                null, day1, bus, Arrays.asList(9, 9, 9));
        check("a repeated seat is booked once",
                repeated.isSuccess() && repeated.getBooking().getSeatNumbers().size() == 1);
        check("a repeated seat is charged once", repeated.getBooking().getTotalFare() == 250);

        // Date-specific availability: the same bus on another date is clear.
        check("the seats are free on the next day",
                BookingService.countAvailableSeats(bus, day2) == Bus.SEAT_COUNT);
        check("the next day is a different journey",
                !BookingService.isSeatBooked(bus, day2, 2));

        BookingResult nextDay = BookingService.createBooking("Neha Rao", "9000000001",
                null, day2, bus, Arrays.asList(2));
        check("the same seat can be booked on another date", nextDay.isSuccess());
        check("the other date keeps its own booking",
                BookingService.isSeatBooked(bus, day2, 2)
                        && BookingService.isSeatBooked(bus, day1, 2));
        check("day one still has 16 free", BookingService.countAvailableSeats(bus, day1) == 16);
        check("day two now has 19 free", BookingService.countAvailableSeats(bus, day2) == 19);

        // 1. Every committed booking is still on record.
        List<Booking> all = BookingService.getAllBookings();
        check("every committed booking is on record", all.size() == recordsBefore + 4);
        check("records are in the order they were made",
                all.get(recordsBefore).getBookingId().equals(firstId)
                        && all.get(all.size() - 1).getBookingId()
                                .equals(nextDay.getBooking().getBookingId()));
        check("an unknown id finds nothing", BookingService.findBooking("BT9999") == null);

        // 10. The bad cases come back as failures with a message.
        check("a missing bus is refused",
                !BookingService.createBooking("Asha Patil", "9876543210", null,
                        day1, null, Arrays.asList(1)).isSuccess());
        check("a missing date is refused",
                !BookingService.createBooking("Asha Patil", "9876543210", null,
                        null, bus, Arrays.asList(1)).isSuccess());
        check("an empty seat list is refused",
                !BookingService.createBooking("Asha Patil", "9876543210", null,
                        day1, bus, new ArrayList<Integer>()).isSuccess());
        check("a seat number off the end is refused",
                !BookingService.createBooking("Asha Patil", "9876543210", null,
                        day1, bus, Arrays.asList(21)).isSuccess());
        check("a bad name is refused",
                !BookingService.createBooking("  ", "9876543210", null,
                        day1, bus, Arrays.asList(1)).isSuccess());
        check("a bad mobile is refused",
                !BookingService.createBooking("Asha Patil", "12345", null,
                        day1, bus, Arrays.asList(1)).isSuccess());
        check("a bad email is refused",
                !BookingService.createBooking("Asha Patil", "9876543210", "asha@example",
                        day1, bus, Arrays.asList(1)).isSuccess());
        check("all those refusals left the records alone",
                BookingService.getAllBookings().size() == recordsBefore + 4);

        // validateSelection looks at the same per-date state.
        check("validateSelection accepts free seats on that date",
                BookingService.validateSelection(bus, day1, Arrays.asList(1, 3)) == null);
        check("validateSelection refuses a booked seat on that date",
                BookingService.validateSelection(bus, day1, Arrays.asList(1, 2)) != null);
        check("validateSelection allows seats that are busy on another date",
                BookingService.validateSelection(bus, day2, Arrays.asList(7, 8, 9)) == null);
        check("validateSelection refuses those same seats on the first date",
                BookingService.validateSelection(bus, day1, Arrays.asList(7, 8, 9)) != null);

        check("seat numbers format as 02, 03",
                "02, 03".equals(Seat.formatSeatNumbers(Arrays.asList(2, 3))));
    }

    /** The booking id that comes straight after the given one. */
    private static String nextId(String bookingId) {
        int number = Integer.parseInt(bookingId.substring(2));
        return "BT" + (number + 1);
    }

    /** Prints the result and stops the program if a check fails. */
    private static void check(String name, boolean passed) {
        checksRun++;
        if (!passed) {
            throw new IllegalStateException("CHECK FAILED: " + name);
        }
        System.out.println("PASS  " + name);
    }
}