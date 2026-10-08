package com.busticket.ui;

import com.busticket.model.Booking;
import com.busticket.model.Seat;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;

/** Ticket-like confirmation for a booking already committed by BookingService. */
public class ConfirmationPanel extends JPanel {

    private final MainFrame frame;
    private final JLabel bookingIdValue = Style.fieldValue("");
    private final JLabel passengerValue = Style.fieldValue("");
    private final JLabel routeValue = Style.fieldValue("");
    private final JLabel dateValue = Style.fieldValue("");
    private final JLabel busValue = Style.fieldValue("");
    private final JLabel departureValue = Style.fieldValue("");
    private final JLabel seatsValue = Style.fieldValue("");
    private final JLabel fareValue = Style.fieldValue("");
    private final JLabel totalValue = Style.fieldValue("");

    public ConfirmationPanel(MainFrame frame) {
        this.frame = frame;
        setLayout(new BorderLayout());
        Style.prepare(this);
        add(buildTicket(), BorderLayout.CENTER);
    }

    private JPanel buildTicket() {
        Style.RoundedPanel ticket = Style.card();
        ticket.setLayout(new BorderLayout(0, 18));
        ticket.setBorder(BorderFactory.createEmptyBorder(25, 30, 24, 30));
        JPanel top = new JPanel(new BorderLayout(16, 0));
        top.setOpaque(false);
        top.add(new CheckmarkPanel(), BorderLayout.WEST);
        JPanel message = new JPanel(new GridLayout(3, 1, 0, 4));
        message.setOpaque(false);
        JLabel heading = new JLabel("Booking Confirmed");
        heading.setFont(Style.font(Font.BOLD, 23));
        heading.setForeground(Style.TEXT);
        message.add(heading);
        message.add(Style.muted("Your journey is reserved. Keep the booking ID for reference."));
        bookingIdValue.setFont(Style.font(Font.BOLD, 17));
        bookingIdValue.setForeground(Style.PRIMARY);
        message.add(bookingIdValue);
        top.add(message, BorderLayout.CENTER);
        ticket.add(top, BorderLayout.NORTH);

        JPanel details = new JPanel(new GridLayout(3, 3, 18, 16));
        details.setOpaque(false);
        details.add(detail("Passenger", passengerValue));
        details.add(detail("Route", routeValue));
        details.add(detail("Journey date", dateValue));
        details.add(detail("Bus", busValue));
        details.add(detail("Departure", departureValue));
        details.add(detail("Selected seats", seatsValue));
        details.add(detail("Fare per seat", fareValue));
        details.add(detail("Total fare", totalValue));
        details.add(detail("Status", Style.fieldValue("Confirmed")));
        ticket.add(details, BorderLayout.CENTER);

        JPanel actions = new JPanel(new BorderLayout());
        actions.setOpaque(false);
        JButton newBooking = Style.primary("New Booking");
        newBooking.addActionListener(event -> frame.newBooking());
        JButton bookings = Style.secondary("View Bookings");
        bookings.addActionListener(event -> frame.showBookingHistory());
        JPanel left = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 8, 0));
        left.setOpaque(false);
        left.add(newBooking);
        left.add(bookings);
        actions.add(left, BorderLayout.WEST);
        JButton exit = Style.secondary("Exit");
        exit.addActionListener(event -> frame.dispose());
        actions.add(exit, BorderLayout.EAST);
        ticket.add(actions, BorderLayout.SOUTH);
        return ticket;
    }

    private JPanel detail(String label, JLabel value) {
        JPanel panel = new JPanel(new GridLayout(2, 1, 0, 4));
        panel.setOpaque(false);
        panel.add(Style.fieldLabel(label));
        panel.add(value);
        return panel;
    }

    public void display(Booking booking) {
        bookingIdValue.setText("Booking ID: " + booking.getBookingId());
        passengerValue.setText(booking.getPassengerName());
        routeValue.setText(booking.getRoute());
        dateValue.setText(booking.getJourneyDate().format(RoutePanel.DATE_FORMAT));
        busValue.setText(booking.getBus().getBusName());
        departureValue.setText(booking.getBus().getDepartureTime().toString());
        seatsValue.setText(Seat.formatSeatNumbers(booking.getSeatNumbers()));
        fareValue.setText("Rs " + booking.getBus().getFarePerSeat());
        totalValue.setText("Rs " + booking.getTotalFare());
    }

    private static final class CheckmarkPanel extends JPanel {
        CheckmarkPanel() {
            setOpaque(false);
            setPreferredSize(new java.awt.Dimension(56, 56));
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(0xE7F7EF));
            g.fillOval(2, 2, 50, 50);
            g.setColor(Style.SUCCESS);
            g.setStroke(new java.awt.BasicStroke(4f, java.awt.BasicStroke.CAP_ROUND,
                    java.awt.BasicStroke.JOIN_ROUND));
            g.drawLine(16, 27, 24, 35);
            g.drawLine(24, 35, 39, 18);
            g.dispose();
        }
    }
}
