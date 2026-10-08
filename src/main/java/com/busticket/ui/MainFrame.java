package com.busticket.ui;

import com.busticket.model.Booking;
import com.busticket.model.Bus;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.time.LocalDate;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JToggleButton;
import javax.swing.UIManager;

/** Application shell, navigation, and the booking context shared by screens. */
public class MainFrame extends JFrame {

    private static final String ROUTE_SCREEN = "route";
    private static final String BUS_SCREEN = "buses";
    private static final String SEAT_SCREEN = "seats";
    private static final String PASSENGER_SCREEN = "passenger";
    private static final String CONFIRMATION_SCREEN = "confirmation";
    private static final String HISTORY_SCREEN = "history";

    private final java.awt.CardLayout cardLayout = new java.awt.CardLayout();
    private final JPanel screens = new JPanel(cardLayout);
    private final JLabel title = new JLabel();
    private final JLabel subtitle = new JLabel();

    private final RoutePanel routePanel;
    private final BusSelectionPanel busSelectionPanel;
    private final SeatSelectionPanel seatSelectionPanel;
    private final PassengerPanel passengerPanel;
    private final ConfirmationPanel confirmationPanel;
    private final BookingHistoryPanel historyPanel;

    private String chosenSource;
    private String chosenDestination;
    private LocalDate journeyDate;
    private Bus chosenBus;

    public MainFrame() {
        super("GoBus | Bus Ticket Booking");
        applyLightTheme();
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1150, 760);
        setMinimumSize(new Dimension(950, 650));
        setLocationRelativeTo(null);

        routePanel = new RoutePanel(this);
        busSelectionPanel = new BusSelectionPanel(this);
        seatSelectionPanel = new SeatSelectionPanel(this);
        passengerPanel = new PassengerPanel(this);
        confirmationPanel = new ConfirmationPanel(this);
        historyPanel = new BookingHistoryPanel(this);

        screens.add(routePanel, ROUTE_SCREEN);
        screens.add(busSelectionPanel, BUS_SCREEN);
        screens.add(seatSelectionPanel, SEAT_SCREEN);
        screens.add(passengerPanel, PASSENGER_SCREEN);
        screens.add(confirmationPanel, CONFIRMATION_SCREEN);
        screens.add(historyPanel, HISTORY_SCREEN);

        JPanel shell = new JPanel(new BorderLayout());
        shell.setBackground(Style.BACKGROUND);
        shell.add(buildSidebar(), BorderLayout.WEST);
        shell.add(buildContent(), BorderLayout.CENTER);
        setContentPane(shell);
        showRouteSelection();
    }

    private JPanel buildContent() {
        JPanel content = new JPanel(new BorderLayout());
        content.setBackground(Style.BACKGROUND);
        content.add(buildHeader(), BorderLayout.NORTH);
        content.add(screens, BorderLayout.CENTER);
        return content;
    }

    private JPanel buildHeader() {
        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(Style.BACKGROUND);
        header.setBorder(BorderFactory.createEmptyBorder(22, 28, 0, 28));
        JPanel copy = new JPanel(new GridLayout(2, 1, 0, 2));
        copy.setOpaque(false);
        title.setFont(Style.font(Font.BOLD, 26));
        title.setForeground(Style.TEXT);
        subtitle.setFont(Style.font(Font.PLAIN, 13));
        subtitle.setForeground(Style.MUTED);
        copy.add(title);
        copy.add(subtitle);
        header.add(copy, BorderLayout.WEST);

        JLabel ready = new JLabel("  READY TO BOOK  ");
        ready.setFont(Style.font(Font.BOLD, 11));
        ready.setForeground(Style.SUCCESS);
        ready.setOpaque(true);
        ready.setBackground(new java.awt.Color(0xE7F7EF));
        ready.setBorder(BorderFactory.createEmptyBorder(7, 8, 7, 8));
        JPanel status = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 7));
        status.setOpaque(false);
        status.add(ready);
        header.add(status, BorderLayout.EAST);
        return header;
    }

    private JPanel buildSidebar() {
        JPanel sidebar = new JPanel(new BorderLayout());
        sidebar.setBackground(Style.SIDEBAR);
        sidebar.setPreferredSize(new Dimension(226, 0));
        sidebar.setBorder(BorderFactory.createEmptyBorder(26, 14, 20, 14));

        JPanel top = new JPanel(new BorderLayout(0, 28));
        top.setOpaque(false);
        top.add(brandBlock(), BorderLayout.NORTH);
        JPanel nav = new JPanel(new GridLayout(3, 1, 0, 8));
        nav.setOpaque(false);
        ButtonGroup group = new ButtonGroup();
        nav.add(navButton("Home", group, this::showRouteSelection, true));
        nav.add(navButton("Book Tickets", group, this::showRouteSelection, false));
        nav.add(navButton("My Bookings", group, this::showBookingHistory, false));
        top.add(nav, BorderLayout.CENTER);
        sidebar.add(top, BorderLayout.NORTH);

        JLabel project = new JLabel("Java Swing Application");
        project.setFont(Style.font(Font.PLAIN, 11));
        project.setForeground(new java.awt.Color(0x8FA5C0));
        sidebar.add(project, BorderLayout.SOUTH);
        return sidebar;
    }

    private JPanel brandBlock() {
        JPanel brand = new JPanel(new GridLayout(3, 1, 0, 3));
        brand.setOpaque(false);
        JLabel mark = new JLabel("GO");
        mark.setFont(Style.font(Font.BOLD, 18));
        mark.setForeground(Style.WHITE);
        JLabel name = new JLabel("GoBus");
        name.setFont(Style.font(Font.BOLD, 22));
        name.setForeground(Style.WHITE);
        JLabel tagline = new JLabel("Travel made simple");
        tagline.setFont(Style.font(Font.PLAIN, 12));
        tagline.setForeground(new java.awt.Color(0x9FB4CD));
        brand.add(mark);
        brand.add(name);
        brand.add(tagline);
        return brand;
    }

    private JToggleButton navButton(String text, ButtonGroup group, Runnable action, boolean selected) {
        JToggleButton button = new JToggleButton(text, selected);
        button.setFont(Style.font(Font.BOLD, 13));
        button.setForeground(selected ? Style.WHITE : new java.awt.Color(0xC5D4E8));
        button.setBackground(selected ? Style.PRIMARY : Style.SIDEBAR);
        button.setBorder(BorderFactory.createEmptyBorder(11, 14, 11, 8));
        button.setFocusPainted(false);
        button.setHorizontalAlignment(JButton.LEFT);
        button.addActionListener(e -> action.run());
        group.add(button);
        return button;
    }

    public void showRouteSelection() {
        setHeader("Find your next journey", "Search routes, choose seats, and travel with confidence.");
        cardLayout.show(screens, ROUTE_SCREEN);
    }

    public void showBusSelection(String source, String destination, LocalDate date) {
        chosenSource = source;
        chosenDestination = destination;
        journeyDate = date;
        busSelectionPanel.display(source, destination, date);
        setHeader("Available buses", source + " to " + destination + " on " + date.format(RoutePanel.DATE_FORMAT));
        cardLayout.show(screens, BUS_SCREEN);
    }

    public void showSeatSelection(Bus bus, LocalDate date) {
        chosenBus = bus;
        journeyDate = date;
        seatSelectionPanel.display(bus, date);
        setHeader("Choose your seats", bus.getBusName() + " | " + bus.getRoute());
        cardLayout.show(screens, SEAT_SCREEN);
    }

    public void goBackToBusSelection() {
        showBusSelection(chosenSource, chosenDestination, journeyDate);
    }

    public void showPassengerDetails() {
        passengerPanel.display(chosenBus, journeyDate, seatSelectionPanel.getSelectedSeatNumbers());
        setHeader("Passenger details", "Add traveller information and review your journey.");
        cardLayout.show(screens, PASSENGER_SCREEN);
    }

    public void goBackToSeatSelection() {
        setHeader("Choose your seats", chosenBus.getBusName() + " | " + chosenBus.getRoute());
        cardLayout.show(screens, SEAT_SCREEN);
    }

    public void showConfirmation(Booking booking) {
        confirmationPanel.display(booking);
        setHeader("Booking confirmed", "Your seats are reserved for the selected journey.");
        cardLayout.show(screens, CONFIRMATION_SCREEN);
    }

    public void showBookingHistory() {
        historyPanel.display();
        setHeader("My bookings", "Bookings are kept for this application session.");
        cardLayout.show(screens, HISTORY_SCREEN);
    }

    public void newBooking() {
        passengerPanel.reset();
        seatSelectionPanel.clearSelection();
        showRouteSelection();
    }

    /** Current search and selection context, retained for navigation and checks. */
    public String getChosenSource() { return chosenSource; }
    public String getChosenDestination() { return chosenDestination; }
    public LocalDate getJourneyDate() { return journeyDate; }
    public Bus getChosenBus() { return chosenBus; }

    private void setHeader(String pageTitle, String pageSubtitle) {
        title.setText(pageTitle);
        subtitle.setText(pageSubtitle);
    }

    private void applyLightTheme() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // Swing's built-in look remains usable when system theming is unavailable.
        }
    }
}
