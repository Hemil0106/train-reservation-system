package com.busticket.ui;

import com.busticket.data.BusData;
import com.busticket.model.Bus;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;

/** Home dashboard and journey search. */
public class RoutePanel extends JPanel {

    public static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("dd/MM/uuuu").withResolverStyle(ResolverStyle.STRICT);

    private final MainFrame frame;
    private final JComboBox<String> sourceBox = new JComboBox<String>();
    private final JComboBox<String> destinationBox = new JComboBox<String>();
    private final JTextField dateField = new JTextField(10);

    public RoutePanel(MainFrame frame) {
        this.frame = frame;
        setLayout(new BorderLayout());
        Style.prepare(this);
        fillCityBoxes();
        dateField.setText(LocalDate.now().plusDays(1).format(DATE_FORMAT));

        JPanel content = new JPanel();
        content.setOpaque(false);
        content.setLayout(new javax.swing.BoxLayout(content, javax.swing.BoxLayout.Y_AXIS));
        content.add(buildHero());
        content.add(spacer(16));
        content.add(buildSearchCard());
        content.add(spacer(16));
        content.add(buildStats());
        content.add(spacer(20));
        content.add(buildPopularRoutes());
        add(Style.scroll(content), BorderLayout.CENTER);
    }

    private JPanel buildHero() {
        Style.RoundedPanel hero = new Style.RoundedPanel(12, Style.PRIMARY, Style.PRIMARY);
        hero.setLayout(new BorderLayout(16, 0));
        hero.setMaximumSize(new Dimension(Integer.MAX_VALUE, 205));
        hero.setPreferredSize(new Dimension(850, 190));
        hero.setBorder(BorderFactory.createEmptyBorder(28, 30, 24, 22));

        JPanel copy = new JPanel(new GridLayout(3, 1, 0, 7));
        copy.setOpaque(false);
        JLabel eyebrow = new JLabel("GO WHERE YOU WANT TO GO");
        eyebrow.setFont(Style.font(Font.BOLD, 11));
        eyebrow.setForeground(new java.awt.Color(0xD9E7FF));
        JLabel headline = new JLabel("Your next journey starts here");
        headline.setFont(Style.font(Font.BOLD, 26));
        headline.setForeground(Style.WHITE);
        JLabel description = new JLabel("Find your bus, choose your seat, and book your trip in minutes.");
        description.setFont(Style.font(Font.PLAIN, 14));
        description.setForeground(new java.awt.Color(0xE7F0FF));
        copy.add(eyebrow);
        copy.add(headline);
        copy.add(description);
        hero.add(copy, BorderLayout.CENTER);

        BusIllustrationPanel illustration = new BusIllustrationPanel();
        illustration.setPreferredSize(new Dimension(245, 140));
        hero.add(illustration, BorderLayout.EAST);
        return hero;
    }

    private JPanel buildSearchCard() {
        Style.RoundedPanel card = Style.card();
        card.setLayout(new BorderLayout(0, 14));
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, 155));
        card.setBorder(BorderFactory.createEmptyBorder(20, 22, 20, 22));
        card.add(Style.sectionHeading("Plan your journey"), BorderLayout.NORTH);

        JPanel form = new JPanel(new GridBagLayout());
        form.setOpaque(false);
        styleCombo(sourceBox);
        styleCombo(destinationBox);
        styleField(dateField);
        JButton swap = Style.secondary("Swap");
        swap.setToolTipText("Swap source and destination");
        swap.addActionListener(event -> swapRoute());
        JButton search = Style.primary("Search Buses");
        search.addActionListener(event -> searchClicked());

        addField(form, 0, "From", sourceBox, 0, 0.34);
        GridBagConstraints swapCell = Style.cell(1, 0);
        swapCell.gridx = 1;
        swapCell.gridy = 1;
        swapCell.insets = new java.awt.Insets(27, 6, 0, 6);
        form.add(swap, swapCell);
        addField(form, 0, "To", destinationBox, 2, 0.34);
        addField(form, 0, "Journey date", dateField, 3, 0.20);
        GridBagConstraints searchCell = Style.cell(1, 0);
        searchCell.gridx = 4;
        searchCell.gridy = 1;
        searchCell.insets = new java.awt.Insets(27, 8, 0, 0);
        form.add(search, searchCell);
        card.add(form, BorderLayout.CENTER);
        return card;
    }

    private void addField(JPanel form, int row, String label, java.awt.Component field,
                          int column, double weight) {
        GridBagConstraints heading = Style.cell(row, column);
        heading.fill = GridBagConstraints.HORIZONTAL;
        heading.weightx = weight;
        form.add(Style.fieldLabel(label), heading);
        GridBagConstraints input = Style.cell(row + 1, column);
        input.fill = GridBagConstraints.HORIZONTAL;
        input.weightx = weight;
        form.add(field, input);
    }

    private JPanel buildStats() {
        JPanel stats = new JPanel(new GridLayout(1, 3, 12, 0));
        stats.setOpaque(false);
        stats.setMaximumSize(new Dimension(Integer.MAX_VALUE, 90));
        Set<String> routes = new LinkedHashSet<String>();
        for (Bus bus : BusData.getAllBuses()) {
            routes.add(bus.getRoute());
        }
        stats.add(statCard("Routes available", String.valueOf(routes.size())));
        stats.add(statCard("Sample buses", String.valueOf(BusData.getAllBuses().size())));
        stats.add(statCard("Seats per bus", String.valueOf(Bus.SEAT_COUNT)));
        return stats;
    }

    private JPanel statCard(String label, String value) {
        Style.RoundedPanel card = Style.card();
        card.setLayout(new GridLayout(2, 1, 0, 3));
        card.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));
        JLabel number = new JLabel(value);
        number.setFont(Style.font(Font.BOLD, 22));
        number.setForeground(Style.PRIMARY);
        card.add(number);
        card.add(Style.fieldLabel(label));
        return card;
    }

    private JPanel buildPopularRoutes() {
        JPanel section = new JPanel(new BorderLayout(0, 10));
        section.setOpaque(false);
        section.setMaximumSize(new Dimension(Integer.MAX_VALUE, 125));
        section.add(Style.sectionHeading("Popular routes"), BorderLayout.NORTH);
        JPanel routes = new JPanel(new GridLayout(1, 0, 10, 0));
        routes.setOpaque(false);
        Set<String> seen = new LinkedHashSet<String>();
        for (Bus bus : BusData.getAllBuses()) {
            String route = bus.getSource() + " -> " + bus.getDestination();
            if (seen.add(route)) {
                JButton routeButton = Style.secondary(route);
                routeButton.addActionListener(event -> chooseRoute(bus.getSource(), bus.getDestination()));
                routes.add(routeButton);
                if (seen.size() == 4) {
                    break;
                }
            }
        }
        section.add(routes, BorderLayout.CENTER);
        return section;
    }

    private JPanel spacer(int pixels) {
        JPanel spacer = new JPanel();
        spacer.setOpaque(false);
        spacer.setPreferredSize(new Dimension(1, pixels));
        spacer.setMaximumSize(new Dimension(Integer.MAX_VALUE, pixels));
        return spacer;
    }

    private void styleCombo(JComboBox<String> combo) {
        combo.setFont(Style.font(Font.PLAIN, 14));
        combo.setPreferredSize(new Dimension(160, 38));
        combo.setBorder(BorderFactory.createLineBorder(Style.BORDER));
    }

    private void styleField(JTextField field) {
        field.setFont(Style.font(Font.PLAIN, 14));
        field.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(Style.BORDER),
                BorderFactory.createEmptyBorder(8, 10, 8, 10)));
    }

    private void fillCityBoxes() {
        TreeSet<String> sources = new TreeSet<String>();
        TreeSet<String> destinations = new TreeSet<String>();
        for (Bus bus : BusData.getAllBuses()) {
            sources.add(bus.getSource());
            destinations.add(bus.getDestination());
        }
        for (String city : sources) { sourceBox.addItem(city); }
        for (String city : destinations) { destinationBox.addItem(city); }
    }

    private void chooseRoute(String source, String destination) {
        sourceBox.setSelectedItem(source);
        destinationBox.setSelectedItem(destination);
        if (dateField.getText().trim().isEmpty()) {
            dateField.setText(LocalDate.now().plusDays(1).format(DATE_FORMAT));
        }
    }

    private void swapRoute() {
        Object source = sourceBox.getSelectedItem();
        Object destination = destinationBox.getSelectedItem();
        sourceBox.setSelectedItem(destination);
        destinationBox.setSelectedItem(source);
    }

    private void searchClicked() {
        String source = (String) sourceBox.getSelectedItem();
        String destination = (String) destinationBox.getSelectedItem();
        String problem = findProblem(source, destination, dateField.getText().trim());
        if (problem != null) {
            Style.warn(this, problem);
            return;
        }
        frame.showBusSelection(source, destination, LocalDate.parse(dateField.getText().trim(), DATE_FORMAT));
    }

    public static String findProblem(String source, String destination, String dateText) {
        if (source == null || source.isEmpty() || destination == null || destination.isEmpty()) {
            return "Please choose both a source and a destination.";
        }
        if (source.equals(destination)) {
            return "Source and destination must be different cities.";
        }
        if (dateText == null || dateText.isEmpty()) {
            return "Please enter the journey date.";
        }
        LocalDate date;
        try {
            date = LocalDate.parse(dateText, DATE_FORMAT);
        } catch (DateTimeParseException exception) {
            return "Please enter the date as DD/MM/YYYY, for example 25/12/2026.";
        }
        if (date.isBefore(LocalDate.now())) {
            return "The journey date cannot be in the past.";
        }
        if (BusData.getBusesByRoute(source, destination).isEmpty()) {
            return "No buses run from " + source + " to " + destination + ".";
        }
        return null;
    }
}
