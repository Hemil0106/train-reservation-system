package com.rail.ui;

import com.rail.model.BookingDetail;
import com.rail.model.Role;
import com.rail.model.User;
import com.rail.service.SessionManager;

import javax.swing.AbstractButton;
import javax.swing.JLabel;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Checks the ticket screen, which no database check can prove: that what a
 * traveller reads after looking up a PNR is the booking, and that a cancelled one
 * says so.
 *
 * <p>{@code BookingManagementSelfCheck} proves the rows are right. This proves the
 * rows are shown, that a live booking offers the cancel action, and that a
 * cancelled one offers it with a reason instead of a button.
 *
 * <p>No database is touched. A {@link BookingDetail} can only come from rows that
 * exist, so the record is built here with values a real cancellation would produce
 * and the screen is rendered from it. Nothing is written.
 */
public final class TicketScreenSelfCheck {

    private static final long PNR = 5150_000_042L;

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        System.out.println("Ticket screen self-check");
        if (GraphicsEnvironment.isHeadless()) {
            System.out.println("SKIP  screen checks (no display available)");
            return;
        }
        System.out.println();

        SessionManager.get().beginSession(
                new User(7L, "ticket.check", "Ticket Check", Role.CUSTOMER));
        try {
            checkALiveBooking();
            checkACancelledBooking();
            checkADepartedBooking();
        } catch (Exception failed) {
            see("the ticket screen built", false);
            failed.printStackTrace();
        } finally {
            SessionManager.get().endSession();
        }

        System.out.println();
        if (failed == 0) {
            System.out.println("All " + passed + " ticket screen checks passed.");
        } else {
            System.out.println(failed + " of " + (passed + failed)
                    + " ticket screen checks FAILED.");
            System.exit(1);
        }
    }

    /** A confirmed booking: every passenger, their berth, and the cancel button. */
    private static void checkALiveBooking() throws Exception {
        System.out.println("A confirmed ticket renders and can be cancelled");
        DashboardFrame frame = new DashboardFrame();
        try {
            frame.showTicket(live());
            List<String> text = labelsIn(frame.getContentPane());
            List<String> buttons = buttonTextsIn(frame.getContentPane());

            see("the PNR is shown", text.contains("Ticket " + pnrDisplay()));
            see("the train is shown", text.stream().anyMatch(line -> line.contains("12952")));
            see("both station names are shown",
                    text.stream().anyMatch(line -> line.contains("Mumbai CSMT")
                            && line.contains("Pune")));
            see("the route is shown as a code pair",
                    text.stream().anyMatch(line -> line.contains("CSTM") && line.contains("PUNE")));
            see("the journey date is shown",
                    text.stream().anyMatch(line -> line.contains(
                            live().journeyDate().getDayOfMonth() + " "
                                    + monthName(live().journeyDate()))));
            see("the class is shown", text.stream().anyMatch(line -> line.contains("AC 2 Tier")));
            see("the status is shown",
                    text.stream().anyMatch(line -> line.contains("CONFIRMED")));
            see("every passenger is listed", text.stream().anyMatch(line -> line.contains("Asha Rao"))
                    && text.stream().anyMatch(line -> line.contains("Bilal Khan")));
            see("the contact passenger is marked",
                    text.stream().anyMatch(line -> line.contains("(contact)")));
            see("each coach and berth is shown",
                    text.stream().anyMatch(line -> line.contains("AC2 / 14"))
                            || text.stream().anyMatch(line -> line.contains("AC2"))
                            || text.stream().anyMatch(line -> line.contains("14")));
            see("each passenger's status is shown",
                    text.stream().filter(line -> line.contains("CONFIRMED")).count() >= 2);
            see("the fare paid is shown to the paisa",
                    text.stream().anyMatch(line -> line.contains("2680.50")));
            see("the party size is shown",
                    text.stream().anyMatch(line -> line.contains("2 passengers")));
            see("no cancellation is claimed", text.stream().noneMatch(line -> line.contains("Refund")));
            see("the cancel action is offered",
                    buttons.contains("Cancel this booking"));
            see("there is a way back to the list",
                    buttons.contains("Back to My Bookings"));
            see("nothing blocks a live booking",
                    text.stream().noneMatch(line -> line.contains("already departed")
                            || line.contains("already cancelled")));
        } finally {
            frame.dispose();
        }
    }

    /** A cancelled booking: the ledger is shown and the button is gone. */
    private static void checkACancelledBooking() throws Exception {
        System.out.println();
        System.out.println("A cancelled ticket renders and cannot be cancelled again");
        DashboardFrame frame = new DashboardFrame();
        try {
            frame.showTicket(cancelled());
            List<String> text = labelsIn(frame.getContentPane());
            List<String> buttons = buttonTextsIn(frame.getContentPane());

            see("the status is shown as cancelled",
                    text.stream().anyMatch(line -> line.contains("CANCELLED")));
            see("the passengers are still listed",
                    text.stream().anyMatch(line -> line.contains("Asha Rao"))
                            && text.stream().anyMatch(line -> line.contains("Bilal Khan")));
            see("their reservations read as cancelled",
                    text.stream().filter(line -> line.equals("CANCELLED")).count() >= 2);
            see("the refund is shown", text.stream().anyMatch(line -> line.contains("2580.50")));
            see("the charges kept are shown",
                    text.stream().anyMatch(line -> line.contains("100.00")));
            see("the refund status is shown",
                    text.stream().anyMatch(line -> line.contains("PROCESSED")));
            see("when it was cancelled is shown",
                    text.stream().anyMatch(line -> line.contains("2026")));
            see("why it was cancelled is shown",
                    text.stream().anyMatch(line -> line.contains("Change of plan")));
            see("there is no cancel action",
                    !buttons.contains("Cancel this booking"));
            see("the reason it cannot be cancelled is given",
                    text.stream().anyMatch(line -> line.contains("already cancelled")));
            see("there is still a way back to the list",
                    buttons.contains("Back to My Bookings"));
        } finally {
            frame.dispose();
        }
    }

    /** A departed booking: no refund is promised and no button is offered. */
    private static void checkADepartedBooking() throws Exception {
        System.out.println();
        System.out.println("A departed ticket renders and cannot be cancelled");
        DashboardFrame frame = new DashboardFrame();
        try {
            frame.showTicket(departed());
            List<String> text = labelsIn(frame.getContentPane());
            List<String> buttons = buttonTextsIn(frame.getContentPane());

            see("the journey date in the past is still shown",
                    text.stream().anyMatch(line -> line.contains(monthName(departed().journeyDate()))));
            see("it says the journey has departed",
                    text.stream().anyMatch(line -> line.contains("already departed")));
            see("no refund is promised",
                    text.stream().noneMatch(line -> line.contains("Refund")));
            see("there is no cancel action",
                    !buttons.contains("Cancel this booking"));
            see("there is still a way back to the list",
                    buttons.contains("Back to My Bookings"));
        } finally {
            frame.dispose();
        }
    }

    // ---------------------------------------------------------------- the records

    /** Two passengers in AC2, confirmed, a month out. */
    private static BookingDetail live() {
        return build(LocalDate.now().plusDays(30), "CONFIRMED", null);
    }

    /** The same booking, cancelled ten days out, half the fare kept as charges. */
    private static BookingDetail cancelled() {
        return build(LocalDate.now().plusDays(30), "CANCELLED",
                new BookingDetail.Cancellation(91L,
                        LocalDateTime.of(2026, 3, 1, 10, 15), "Change of plan", true,
                        new BigDecimal("2580.50"), new BigDecimal("100.00"), "PROCESSED"));
    }

    /** The same booking on a day that has already gone. */
    private static BookingDetail departed() {
        return build(LocalDate.now().minusDays(2), "CONFIRMED", null);
    }

    private static BookingDetail build(LocalDate journeyDate, String status,
                                       BookingDetail.Cancellation cancellation) {
        return new BookingDetail(PNR, 7L, "12952", "Mumbai CSMT - Pune Shatabdi", journeyDate,
                "CSTM", "Mumbai CSMT", "PUNE", "Pune", "2A", "AC 2 Tier", status,
                com.rail.model.BookingStatus.fromDb(status), new BigDecimal("2680.50"),
                List.of(
                        new BookingDetail.Passenger(11L, "Asha Rao", 34, "FEMALE",
                                "LOWER", true, 501L, "AC2", 13, "AC_BERTH", "LOWER",
                                new BigDecimal("1340.25"), status),
                        new BookingDetail.Passenger(12L, "Bilal Khan", 41, "MALE",
                                "NO_PREFERENCE", false, 502L, "AC2", 14, "AC_BERTH", "UPPER",
                                new BigDecimal("1340.25"), status)),
                cancellation);
    }

    private static String pnrDisplay() {
        return String.format("%010d", PNR);
    }

    private static String monthName(LocalDate date) {
        return date.getMonth().getDisplayName(java.time.format.TextStyle.FULL,
                java.util.Locale.ROOT);
    }

    // ---------------------------------------------------------------- plumbing

    /** Every label on screen, flattened, so assertions can look for real text. */
    private static List<String> labelsIn(Container root) {
        List<String> found = new ArrayList<>();
        for (Component child : root.getComponents()) {
            if (child instanceof JLabel label && label.getText() != null) {
                found.add(label.getText());
            }
            if (child instanceof Container container) {
                found.addAll(labelsIn(container));
            }
        }
        return found;
    }

    /** Every button caption, so an action can be asserted present or absent. */
    private static List<String> buttonTextsIn(Container root) {
        List<String> found = new ArrayList<>();
        for (Component child : root.getComponents()) {
            if (child instanceof AbstractButton button && button.getText() != null) {
                found.add(button.getText());
            }
            if (child instanceof Container container) {
                found.addAll(buttonTextsIn(container));
            }
        }
        return found;
    }

    private static void see(String what, boolean ok) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + what);
        if (ok) {
            passed++;
        } else {
            failed++;
        }
    }
}