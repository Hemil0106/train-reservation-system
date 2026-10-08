package com.busticket.ui;

import com.busticket.model.Bus;
import com.busticket.model.Seat;
import com.busticket.service.BookingResult;
import com.busticket.service.BookingService;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.time.LocalDate;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;

/** Passenger form alongside a live summary of the existing selection. */
public class PassengerPanel extends JPanel {

    private final MainFrame frame;
    private final JTextField nameField = new JTextField(22);
    private final JTextField mobileField = new JTextField(22);
    private final JTextField emailField = new JTextField(22);
    private final JLabel routeValue = Style.fieldValue("");
    private final JLabel dateValue = Style.fieldValue("");
    private final JLabel busValue = Style.fieldValue("");
    private final JLabel departureValue = Style.fieldValue("");
    private final JLabel seatsValue = Style.fieldValue("");
    private final JLabel fareValue = Style.fieldValue("");
    private final JLabel totalValue = Style.fieldValue("");
    private Bus bus;
    private LocalDate journeyDate;
    private List<Integer> seatNumbers;

    public PassengerPanel(MainFrame frame) {
        this.frame = frame;
        setLayout(new BorderLayout());
        Style.prepare(this);
        JPanel columns = new JPanel(new GridLayout(1, 2, 16, 0));
        columns.setOpaque(false);
        columns.add(buildForm());
        columns.add(buildSummary());
        add(columns, BorderLayout.CENTER);
    }

    private JPanel buildForm() {
        Style.RoundedPanel card = Style.card();
        card.setLayout(new BorderLayout(0, 18));
        card.setBorder(BorderFactory.createEmptyBorder(20, 22, 20, 22));
        JPanel header = new JPanel(new GridLayout(2, 1, 0, 4));
        header.setOpaque(false);
        header.add(Style.sectionHeading("Passenger information"));
        header.add(Style.muted("Use the traveller's details for this booking."));
        card.add(header, BorderLayout.NORTH);
        JPanel form = new JPanel(new GridBagLayout());
        form.setOpaque(false);
        addInput(form, 0, "Full name", nameField, "Example: Asha Patil");
        addInput(form, 1, "Mobile number", mobileField, "10 digit Indian number");
        addInput(form, 2, "Email address", emailField, "Optional");
        card.add(form, BorderLayout.CENTER);
        JPanel actions = new JPanel(new BorderLayout());
        actions.setOpaque(false);
        JButton back = Style.secondary("Back to Seats");
        back.addActionListener(event -> frame.goBackToSeatSelection());
        JButton confirm = Style.primary("Confirm Booking");
        confirm.addActionListener(event -> confirmClicked());
        actions.add(back, BorderLayout.WEST);
        actions.add(confirm, BorderLayout.EAST);
        card.add(actions, BorderLayout.SOUTH);
        return card;
    }

    private void addInput(JPanel form, int row, String label, JTextField field, String hint) {
        field.setFont(Style.font(java.awt.Font.PLAIN, 14));
        field.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(Style.BORDER),
                BorderFactory.createEmptyBorder(9, 10, 9, 10)));
        // Each field needs its own label, input and helper row. Using a three-row
        // block prevents the helper text from sharing a row with the next label.
        GridBagConstraints title = Style.cell(row * 3, 0);
        title.weightx = 1.0;
        title.fill = GridBagConstraints.HORIZONTAL;
        form.add(Style.fieldLabel(label), title);
        GridBagConstraints input = Style.cell(row * 3 + 1, 0);
        input.weightx = 1.0;
        input.fill = GridBagConstraints.HORIZONTAL;
        form.add(field, input);
        GridBagConstraints note = Style.cell(row * 3 + 2, 0);
        form.add(Style.muted(hint), note);
    }

    private JPanel buildSummary() {
        Style.RoundedPanel card = Style.card();
        card.setLayout(new BorderLayout(0, 18));
        card.setBorder(BorderFactory.createEmptyBorder(20, 22, 20, 22));
        card.add(Style.sectionHeading("Booking summary"), BorderLayout.NORTH);
        JPanel details = new JPanel(new GridLayout(7, 1, 0, 12));
        details.setOpaque(false);
        details.add(detail("Route", routeValue));
        details.add(detail("Journey date", dateValue));
        details.add(detail("Bus", busValue));
        details.add(detail("Departure", departureValue));
        details.add(detail("Selected seats", seatsValue));
        details.add(detail("Fare per seat", fareValue));
        details.add(detail("Total fare", totalValue));
        card.add(details, BorderLayout.CENTER);
        return card;
    }

    private JPanel detail(String label, JLabel value) {
        JPanel line = new JPanel(new GridLayout(2, 1, 0, 3));
        line.setOpaque(false);
        line.add(Style.fieldLabel(label));
        line.add(value);
        return line;
    }

    public void display(Bus bus, LocalDate journeyDate, List<Integer> seatNumbers) {
        this.bus = bus;
        this.journeyDate = journeyDate;
        this.seatNumbers = seatNumbers;
        routeValue.setText(bus.getRoute());
        dateValue.setText(journeyDate.format(RoutePanel.DATE_FORMAT));
        busValue.setText(bus.getBusName() + " (" + bus.getBusId() + ")");
        departureValue.setText(bus.getDepartureTime().toString());
        seatsValue.setText(Seat.formatSeatNumbers(seatNumbers));
        fareValue.setText("Rs " + bus.getFarePerSeat());
        totalValue.setText("Rs " + bus.getTotalFare(seatNumbers.size()));
    }

    private void confirmClicked() {
        BookingResult result = BookingService.createBooking(nameField.getText(), mobileField.getText(),
                emailField.getText(), journeyDate, bus, seatNumbers);
        if (!result.isSuccess()) { Style.warn(this, result.getMessage()); return; }
        frame.showConfirmation(result.getBooking());
    }

    public void reset() {
        nameField.setText("");
        mobileField.setText("");
        emailField.setText("");
        bus = null;
        journeyDate = null;
        seatNumbers = null;
    }
}
