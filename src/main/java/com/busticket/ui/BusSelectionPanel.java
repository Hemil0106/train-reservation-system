package com.busticket.ui;

import com.busticket.data.BusData;
import com.busticket.model.Bus;
import com.busticket.service.BookingService;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.time.LocalDate;
import java.util.List;

import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;

/** Search result cards, built from the actual buses on the chosen route. */
public class BusSelectionPanel extends JPanel {

    private final MainFrame frame;
    private final JLabel routeLabel = Style.fieldValue("");
    private final JLabel countLabel = Style.muted("");
    private final JPanel results = new JPanel();

    public BusSelectionPanel(MainFrame frame) {
        this.frame = frame;
        setLayout(new BorderLayout());
        Style.prepare(this);
        results.setOpaque(false);
        results.setLayout(new BoxLayout(results, BoxLayout.Y_AXIS));
        JPanel content = new JPanel(new BorderLayout(0, 16));
        content.setOpaque(false);
        content.add(buildJourneyBar(), BorderLayout.NORTH);
        content.add(results, BorderLayout.CENTER);
        add(Style.scroll(content), BorderLayout.CENTER);
    }

    private JPanel buildJourneyBar() {
        Style.RoundedPanel journey = Style.card();
        journey.setLayout(new BorderLayout(16, 0));
        journey.setBorder(javax.swing.BorderFactory.createEmptyBorder(16, 18, 16, 18));
        JPanel copy = new JPanel(new GridLayout(2, 1, 0, 4));
        copy.setOpaque(false);
        copy.add(routeLabel);
        copy.add(countLabel);
        journey.add(copy, BorderLayout.CENTER);
        JButton edit = Style.secondary("Edit Search");
        edit.addActionListener(event -> frame.showRouteSelection());
        journey.add(edit, BorderLayout.EAST);
        return journey;
    }

    public void display(String source, String destination, LocalDate journeyDate) {
        List<Bus> buses = BusData.getBusesByRoute(source, destination);
        routeLabel.setText(source + "  ->  " + destination + "    |    "
                + journeyDate.format(RoutePanel.DATE_FORMAT));
        countLabel.setText(buses.size() + (buses.size() == 1 ? " matching bus" : " matching buses"));
        results.removeAll();
        if (buses.isEmpty()) {
            results.add(emptyState());
        } else {
            for (Bus bus : buses) {
                results.add(busCard(bus, journeyDate));
                results.add(spacer(12));
            }
        }
        results.revalidate();
        results.repaint();
    }

    private JPanel busCard(Bus bus, LocalDate journeyDate) {
        Style.RoundedPanel card = Style.card();
        card.setLayout(new BorderLayout(20, 0));
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, 132));
        card.setPreferredSize(new Dimension(760, 124));
        card.setBorder(javax.swing.BorderFactory.createEmptyBorder(18, 20, 18, 20));

        JPanel identity = new JPanel(new GridLayout(3, 1, 0, 4));
        identity.setOpaque(false);
        JLabel name = Style.sectionHeading(bus.getBusName());
        JLabel id = Style.muted(bus.getBusId() + " | " + bus.getRoute());
        JLabel travel = Style.fieldValue(bus.getDepartureTime() + "  ->  " + bus.getArrivalTime());
        identity.add(name);
        identity.add(id);
        identity.add(travel);
        card.add(identity, BorderLayout.WEST);

        int free = BookingService.countAvailableSeats(bus, journeyDate);
        JPanel metrics = new JPanel(new GridLayout(2, 2, 18, 7));
        metrics.setOpaque(false);
        metrics.add(metric("Fare per seat", "Rs " + bus.getFarePerSeat()));
        metrics.add(metric("Available", free + " of " + Bus.SEAT_COUNT));
        metrics.add(metric("Departure", bus.getDepartureTime().toString()));
        metrics.add(metric("Arrival", bus.getArrivalTime().toString()));
        card.add(metrics, BorderLayout.CENTER);

        JButton select = Style.primary(free == 0 ? "Full" : "Select Seats");
        select.setEnabled(free > 0);
        select.addActionListener(event -> frame.showSeatSelection(bus, journeyDate));
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 28));
        actions.setOpaque(false);
        actions.add(select);
        card.add(actions, BorderLayout.EAST);
        return card;
    }

    private JPanel metric(String label, String value) {
        JPanel metric = new JPanel(new GridLayout(2, 1, 0, 2));
        metric.setOpaque(false);
        metric.add(Style.fieldLabel(label));
        metric.add(Style.fieldValue(value));
        return metric;
    }

    private JPanel emptyState() {
        Style.RoundedPanel empty = Style.card();
        empty.setLayout(new FlowLayout(FlowLayout.CENTER, 0, 70));
        empty.setMaximumSize(new Dimension(Integer.MAX_VALUE, 200));
        JLabel text = new JLabel("No buses match this route yet. Try another journey.");
        text.setFont(Style.font(Font.PLAIN, 15));
        text.setForeground(Style.MUTED);
        empty.add(text);
        return empty;
    }

    private JPanel spacer(int height) {
        JPanel spacer = new JPanel();
        spacer.setOpaque(false);
        spacer.setPreferredSize(new Dimension(1, height));
        spacer.setMaximumSize(new Dimension(Integer.MAX_VALUE, height));
        return spacer;
    }
}
