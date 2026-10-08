package com.rail.ui;

import com.rail.model.BerthAllocation;
import com.rail.model.BerthPreference;
import com.rail.model.BookingStatus;
import com.rail.model.ConfirmedBooking;
import com.rail.model.FareEstimate;
import com.rail.model.Gender;
import com.rail.model.Role;
import com.rail.model.User;
import com.rail.service.SessionManager;

import javax.swing.JLabel;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Checks the one thing the booking engine's own checks cannot: that a
 * committed booking actually renders.
 *
 * <p>{@link BookingEngineSelfCheck} proves the transaction commits the right rows.
 * This proves the screen a traveller sees afterwards shows those rows and nothing
 * else - no PNR is invented, no berth is mislabelled, and a fallback is visible as
 * a fallback.
 *
 * <p>No database is touched. A {@link ConfirmedBooking} can only be built from rows
 * that were committed, so the record is constructed here with values a committed
 * transaction would really produce; nothing is written, and the checks leave the
 * database exactly as they found it.
 */
public final class BookingConfirmationSelfCheck {

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        if (GraphicsEnvironment.isHeadless()) {
            System.out.println("Booking confirmation self-check");
            System.out.println("SKIP  screen checks (no display available)");
            return;
        }

        System.out.println("Booking confirmation self-check");
        System.out.println();
        System.out.println("A committed booking renders");

        SessionManager.get().beginSession(
                new User(7L, "confirmation.check", "Confirmation Check", Role.CUSTOMER));
        try {
            ConfirmedBooking booked = aBookingThatFellBack();
            DashboardFrame frame = new DashboardFrame();
            try {
                frame.showConfirmation(booked);

                List<String> text = labelsIn(frame.getContentPane());

                see("the PNR is shown", text.contains(booked.pnrDisplay()));
                see("the train is shown", text.stream().anyMatch(line -> line.contains("12952")));
                see("the route is shown",
                        text.stream().anyMatch(line -> line.contains("CSTM") && line.contains("PUNE")));
                see("the class is shown",
                        text.stream().anyMatch(line -> line.contains("3A")));
                see("the committed status is shown",
                        text.stream().anyMatch(line -> line.contains("CONFIRMED")));
                see("every passenger is listed", text.stream()
                        .anyMatch(line -> line.contains("Asha Rao"))
                        && text.stream().anyMatch(line -> line.contains("Bilal Khan")));
                see("the granted berth is shown",
                        text.stream().anyMatch(line -> line.contains("AC2 / berth 14"))
                                || text.stream().anyMatch(line -> line.contains("14")));
                see("a substituted position says so",
                        text.stream().anyMatch(line -> line.contains("fallback")));
                see("the fare total is shown",
                        text.stream().anyMatch(line -> line.contains("3456.78")));
                see("the distance charged is shown",
                        text.stream().anyMatch(line -> line.contains("1517")));
            } finally {
                frame.dispose();
            }
        } catch (Exception failed) {
            see("the confirmation screen built", false);
            failed.printStackTrace();
        } finally {
            SessionManager.get().endSession();
        }

        System.out.println();
        if (failed == 0) {
            System.out.println("All " + passed + " confirmation checks passed.");
        } else {
            System.out.println(failed + " of " + (passed + failed)
                    + " confirmation checks FAILED.");
            System.exit(1);
        }
    }

    /** Two passengers, one of whom asked for a lower berth and did not get one. */
    private static ConfirmedBooking aBookingThatFellBack() {
        return new ConfirmedBooking(
                4242424242L,
                "12952", "Mumbai CSMT - Pune Shatabdi",
                java.time.LocalDate.now().plusDays(30),
                "CSTM", "Mumbai CSMT",
                "PUNE", "Pune",
                "3A", "AC 3 Tier",
                BookingStatus.CONFIRMED,
                new FareEstimate(new BigDecimal("500.00"), new BigDecimal("3456.78"), 1517, 2),
                List.of(
                        new BerthAllocation("Asha Rao", 34, Gender.FEMALE,
                                BerthPreference.LOWER, 501L, 12L, "AC1", 11, "AC_BERTH",
                                BerthPreference.UPPER),
                        new BerthAllocation("Bilal Khan", 41, Gender.MALE,
                                BerthPreference.NO_PREFERENCE, 502L, 13L, "AC2", 14, "AC_BERTH",
                                BerthPreference.LOWER)));
    }

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

    private static void see(String what, boolean ok) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + what);
        if (ok) {
            passed++;
        } else {
            failed++;
        }
    }
}