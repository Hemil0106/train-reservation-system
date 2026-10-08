package com.busticket.ui;

import com.busticket.model.Booking;
import com.busticket.model.Seat;
import com.busticket.service.BookingService;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.table.DefaultTableModel;

/** Session-only booking history backed by the records already held by BookingService. */
public class BookingHistoryPanel extends JPanel {

    private final DefaultTableModel tableModel = new DefaultTableModel(
            new String[] {"Booking ID", "Passenger", "Route", "Journey date", "Bus", "Seats", "Total"}, 0) {
        @Override public boolean isCellEditable(int row, int column) { return false; }
    };
    private final JTable table = new JTable(tableModel);
    private final JPanel emptyState = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 80));
    private final JPanel body = new JPanel(new BorderLayout());

    public BookingHistoryPanel(MainFrame frame) {
        setLayout(new BorderLayout());
        Style.prepare(this);
        add(Style.screenHeading("Your booking history"), BorderLayout.NORTH);

        table.setRowHeight(34);
        table.setFont(Style.font(java.awt.Font.PLAIN, 13));
        table.getTableHeader().setFont(Style.font(java.awt.Font.BOLD, 12));
        table.getTableHeader().setBackground(Style.WHITE);
        table.setGridColor(Style.BORDER);
        table.setShowVerticalLines(false);

        JLabel message = new JLabel("No confirmed bookings yet. Your completed journeys will appear here.");
        message.setFont(Style.font(java.awt.Font.PLAIN, 14));
        message.setForeground(Style.MUTED);
        emptyState.setOpaque(false);
        emptyState.add(message);
        body.setOpaque(false);
        add(body, BorderLayout.CENTER);
    }

    public void display() {
        tableModel.setRowCount(0);
        List<Booking> bookings = BookingService.getAllBookings();
        if (bookings.isEmpty()) {
            body.removeAll();
            body.add(emptyState, BorderLayout.CENTER);
        } else {
            for (Booking booking : bookings) {
                tableModel.addRow(new Object[] {
                        booking.getBookingId(), booking.getPassengerName(), booking.getRoute(),
                        booking.getJourneyDate().format(RoutePanel.DATE_FORMAT),
                        booking.getBus().getBusName(), Seat.formatSeatNumbers(booking.getSeatNumbers()),
                        "Rs " + booking.getTotalFare()
                });
            }
            JPanel tableCard = Style.card();
            tableCard.setLayout(new BorderLayout());
            tableCard.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
            JScrollPane scroll = new JScrollPane(table);
            scroll.setBorder(null);
            tableCard.add(scroll, BorderLayout.CENTER);
            body.removeAll();
            body.add(tableCard, BorderLayout.CENTER);
        }
        body.revalidate();
        body.repaint();
    }
}
