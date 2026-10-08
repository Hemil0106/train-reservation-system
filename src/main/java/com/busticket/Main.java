package com.busticket;

import com.busticket.ui.MainFrame;

import javax.swing.SwingUtilities;

/**
 * Entry point of the Bus Ticket Booking System.
 */
public class Main {

    public static void main(String[] args) {

        // Swing is not thread safe: every GUI call must happen on the
        // event-dispatch thread (EDT), which invokeLater schedules for us.
        SwingUtilities.invokeLater(() -> {
            MainFrame window = new MainFrame();
            window.setVisible(true);
        });
    }
}