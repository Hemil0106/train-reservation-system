package com.busticket.ui;

import com.busticket.model.Bus;
import com.busticket.model.Seat;
import com.busticket.service.BookingService;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.Timer;

/** Graphical 2+2 cabin layout while retaining service-backed seat rules. */
public class SeatSelectionPanel extends JPanel {

    private static final Color AVAILABLE = new Color(0xF7FAFF);
    private static final Color SELECTED = Style.PRIMARY;
    private static final Color BOOKED = new Color(0xE5E7EB);
    private final MainFrame frame;
    private final List<JButton> seatButtons = new ArrayList<JButton>();
    private final TreeSet<Integer> selectedSeatNumbers = new TreeSet<Integer>();
    private Bus bus;
    private LocalDate journeyDate;

    private final JLabel busValue = Style.fieldValue("");
    private final JLabel routeValue = Style.fieldValue("");
    private final JLabel dateValue = Style.fieldValue("");
    private final JLabel selectedValue = Style.fieldValue("None");
    private final JLabel fareValue = Style.fieldValue("");
    private final JLabel totalValue = Style.fieldValue("Rs 0");
    private final JButton continueButton = Style.primary("Continue");

    public SeatSelectionPanel(MainFrame frame) {
        this.frame = frame;
        setLayout(new BorderLayout(0, 16));
        Style.prepare(this);
        createSeatButtons();
        add(buildJourneyBar(), BorderLayout.NORTH);
        add(buildContent(), BorderLayout.CENTER);
    }

    private JPanel buildJourneyBar() {
        Style.RoundedPanel bar = Style.card();
        bar.setLayout(new BorderLayout(18, 0));
        bar.setBorder(BorderFactory.createEmptyBorder(15, 18, 15, 18));
        JPanel details = new JPanel(new GridLayout(1, 3, 24, 0));
        details.setOpaque(false);
        details.add(detail("Bus", busValue));
        details.add(detail("Route", routeValue));
        details.add(detail("Journey date", dateValue));
        bar.add(details, BorderLayout.CENTER);
        JButton back = Style.secondary("Back");
        back.addActionListener(event -> frame.goBackToBusSelection());
        bar.add(back, BorderLayout.EAST);
        return bar;
    }

    private JPanel detail(String label, JLabel value) {
        JPanel item = new JPanel(new GridLayout(2, 1, 0, 3));
        item.setOpaque(false);
        item.add(Style.fieldLabel(label));
        item.add(value);
        return item;
    }

    private JPanel buildContent() {
        JPanel content = new JPanel(new GridLayout(1, 2, 16, 0));
        content.setOpaque(false);
        content.add(buildCabin());
        content.add(buildSummary());
        return content;
    }

    private JPanel buildCabin() {
        Style.RoundedPanel cabin = Style.card();
        cabin.setLayout(new BorderLayout(0, 14));
        cabin.setBorder(BorderFactory.createEmptyBorder(18, 20, 18, 20));
        JLabel heading = Style.sectionHeading("Seat map");
        cabin.add(heading, BorderLayout.NORTH);
        JPanel map = new JPanel(new GridBagLayout());
        map.setOpaque(false);
        JLabel driver = new JLabel("Driver");
        driver.setFont(Style.font(Font.BOLD, 12));
        driver.setForeground(Style.MUTED);
        GridBagConstraints driverCell = Style.cell(0, 3);
        driverCell.gridwidth = 2;
        map.add(driver, driverCell);
        for (int index = 0; index < Bus.SEAT_COUNT; index += 4) {
            int row = index / 4 + 1;
            addSeat(map, seatButtons.get(index), row, 0);
            addSeat(map, seatButtons.get(index + 1), row, 1);
            addSeat(map, seatButtons.get(index + 2), row, 3);
            addSeat(map, seatButtons.get(index + 3), row, 4);
        }
        cabin.add(map, BorderLayout.CENTER);
        JPanel legend = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        legend.setOpaque(false);
        legend.add(legend("Available", AVAILABLE, Style.PRIMARY));
        legend.add(legend("Selected", SELECTED, Style.WHITE));
        legend.add(legend("Booked", BOOKED, Style.MUTED));
        cabin.add(legend, BorderLayout.SOUTH);
        return cabin;
    }

    private void addSeat(JPanel map, JButton seat, int row, int column) {
        GridBagConstraints cell = Style.cell(row, column);
        cell.insets = new java.awt.Insets(6, 6, 6, 6);
        map.add(seat, cell);
    }

    private JLabel legend(String text, Color background, Color foreground) {
        JLabel label = new JLabel("  " + text + "  ");
        label.setOpaque(true);
        label.setBackground(background);
        label.setForeground(foreground);
        label.setFont(Style.font(Font.BOLD, 11));
        label.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
        return label;
    }

    private JPanel buildSummary() {
        Style.RoundedPanel summary = Style.card();
        summary.setLayout(new BorderLayout(0, 16));
        summary.setBorder(BorderFactory.createEmptyBorder(20, 22, 20, 22));
        summary.add(Style.sectionHeading("Booking summary"), BorderLayout.NORTH);
        JPanel lines = new JPanel(new GridLayout(4, 1, 0, 14));
        lines.setOpaque(false);
        lines.add(detail("Selected seats", selectedValue));
        lines.add(detail("Fare per seat", fareValue));
        lines.add(detail("Number of seats", new JLabel() {
            @Override public String getText() { return String.valueOf(selectedSeatNumbers.size()); }
        }));
        lines.add(detail("Total fare", totalValue));
        summary.add(lines, BorderLayout.CENTER);
        JPanel actions = new JPanel(new BorderLayout(0, 7));
        actions.setOpaque(false);
        JLabel note = Style.muted("Seats are reserved only after confirmation.");
        continueButton.addActionListener(event -> continueClicked());
        actions.add(note, BorderLayout.NORTH);
        actions.add(continueButton, BorderLayout.SOUTH);
        summary.add(actions, BorderLayout.SOUTH);
        return summary;
    }

    public void display(Bus bus, LocalDate journeyDate) {
        boolean sameJourney = this.bus == bus && Objects.equals(this.journeyDate, journeyDate);
        this.bus = bus;
        this.journeyDate = journeyDate;
        if (!sameJourney) { selectedSeatNumbers.clear(); }
        Iterator<Integer> iterator = selectedSeatNumbers.iterator();
        while (iterator.hasNext()) {
            if (isTaken(iterator.next())) { iterator.remove(); }
        }
        busValue.setText(bus.getBusId() + " | " + bus.getBusName());
        routeValue.setText(bus.getRoute());
        dateValue.setText(journeyDate.format(RoutePanel.DATE_FORMAT));
        fareValue.setText("Rs " + bus.getFarePerSeat());
        refresh();
    }

    public void toggleSeat(int seatNumber) {
        if (isTaken(seatNumber)) { return; }
        if (!selectedSeatNumbers.remove(seatNumber)) { selectedSeatNumbers.add(seatNumber); }
        refresh();
        pulse(seatButtons.get(seatNumber - 1));
    }

    private void pulse(JButton button) {
        Timer timer = new Timer(60, null);
        timer.addActionListener(event -> { button.setBorder(BorderFactory.createLineBorder(Style.SECONDARY_BLUE, 3));
            timer.stop(); refresh(); });
        timer.setRepeats(false);
        timer.start();
    }

    private void refresh() {
        for (int index = 0; index < seatButtons.size(); index++) {
            int seatNumber = index + 1;
            JButton button = seatButtons.get(index);
            if (isTaken(seatNumber)) {
                paintSeat(button, BOOKED, Style.MUTED, false);
            } else if (selectedSeatNumbers.contains(seatNumber)) {
                paintSeat(button, SELECTED, Style.WHITE, true);
            } else {
                paintSeat(button, AVAILABLE, Style.PRIMARY, true);
            }
        }
        selectedValue.setText(describeSelection());
        totalValue.setText("Rs " + getTotalFare());
        continueButton.setEnabled(!selectedSeatNumbers.isEmpty());
    }

    private void paintSeat(JButton button, Color background, Color foreground, boolean enabled) {
        button.setEnabled(enabled);
        button.setBackground(background);
        button.setForeground(foreground);
        button.setBorder(BorderFactory.createLineBorder(enabled ? Style.PRIMARY : Style.BORDER, 1));
    }

    private void createSeatButtons() {
        for (int index = 0; index < Bus.SEAT_COUNT; index++) {
            int number = index + 1;
            JButton button = new JButton(String.format("%02d", number));
            button.setFont(Style.font(Font.BOLD, 12));
            button.setPreferredSize(new Dimension(56, 42));
            button.setFocusPainted(false);
            button.setOpaque(true);
            button.addActionListener(event -> toggleSeat(number));
            seatButtons.add(button);
        }
    }

    private boolean isTaken(int seatNumber) {
        Seat seat = bus.getSeats().get(seatNumber - 1);
        return !seat.isAvailable() || BookingService.isSeatBooked(bus, journeyDate, seatNumber);
    }

    public void clearSelection() {
        selectedSeatNumbers.clear();
        if (bus != null) { refresh(); }
    }

    public List<Integer> getSelectedSeatNumbers() { return new ArrayList<Integer>(selectedSeatNumbers); }
    public int getTotalFare() { return bus == null ? 0 : bus.getTotalFare(selectedSeatNumbers.size()); }
    public String findContinueProblem() {
        return selectedSeatNumbers.isEmpty() ? "Please select at least one seat before continuing." : null;
    }

    private void continueClicked() {
        String problem = findContinueProblem();
        if (problem != null) { Style.warn(this, problem); return; }
        frame.showPassengerDetails();
    }

    private String describeSelection() {
        String values = Seat.formatSeatNumbers(new ArrayList<Integer>(selectedSeatNumbers));
        return values.isEmpty() ? "None" : values;
    }
}
