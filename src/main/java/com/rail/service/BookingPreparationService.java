package com.rail.service;

import com.rail.db.ConnectionSource;
import com.rail.db.DatabaseConnection;
import com.rail.db.DbConfig;
import com.rail.db.TrainDAO;
import com.rail.db.TrainDAO.FareRule;
import com.rail.model.BerthPreference;
import com.rail.model.BookingContact;
import com.rail.model.BookingDraft;
import com.rail.model.ClassProfile;
import com.rail.model.FareEstimate;
import com.rail.model.Gender;
import com.rail.model.PassengerDraft;
import com.rail.model.PassengerInput;
import com.rail.model.TrainSelection;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.SQLException;
import java.util.List;

/**
 * Turns typed passenger details into a validated {@link BookingDraft}, and prices
 * that draft.
 *
 * <p>Every rule the passenger form enforces lives here, not in the form. The
 * screen's buttons stay presentation; a request that reaches this class is
 * checked whether it came from a button, a test, or anywhere else. That is also
 * why nothing here touches Swing.
 *
 * <p><strong>This service writes nothing.</strong> It reads the class
 * configuration and the fare rules, and it keeps the draft in memory. There is no
 * code path from here to {@code bookings} or {@code seat_reservations}: a
 * prepared draft is not a ticket, and this milestone does not let it quietly
 * become one.
 *
 * <p>Concessions and special assistance are deliberately not collected. Neither
 * has a column in the schema and neither can be priced correctly from the fare
 * rules this project has, so offering the fields would mean collecting an answer
 * the application could not honour. See {@code README.md} section 12.
 *
 * <p>Callers must invoke this off the event dispatch thread where it touches the
 * database.
 */
public class BookingPreparationService {

    private final TrainDAO trains;
    private final FareService fares;
    private final int maxPassengers;
    private final int minAge;
    private final int maxAge;

    public BookingPreparationService() {
        this(DatabaseConnection::getConnection);
    }

    public BookingPreparationService(ConnectionSource connections) {
        this(connections, DatabaseConnection.config());
    }

    public BookingPreparationService(ConnectionSource connections, DbConfig config) {
        this.trains = new TrainDAO(connections);
        this.fares = new FareService(trains);
        this.maxPassengers = Math.max(1, config.maxPassengers());
        this.minAge = config.minPassengerAge();
        this.maxAge = config.maxPassengerAge();
    }

    /** Test seam: lets a check pin the limits instead of reading configuration. */
    public BookingPreparationService(ConnectionSource connections, int maxPassengers,
                                     int minAge, int maxAge) {
        this.trains = new TrainDAO(connections);
        this.fares = new FareService(trains);
        this.maxPassengers = Math.max(1, maxPassengers);
        this.minAge = minAge;
        this.maxAge = maxAge;
    }

    public int maxPassengers() {
        return maxPassengers;
    }

    public int minAge() {
        return minAge;
    }

    public int maxAge() {
        return maxAge;
    }

    // ---------------------------------------------------------------- class profile

    /**
     * What the selected class can offer.
     *
     * @throws ValidationException when the class code matches nothing, which
     *     means the selection came from nowhere valid
     */
    public ClassProfile profileOf(String classCode) throws ValidationException, SQLException {
        return trains.classProfile(classCode)
                .orElseThrow(() -> new ValidationException("class",
                        "That travel class is not configured."));
    }

    /**
     * The berth preferences to offer for a class.
     *
     * <p>Derived from the coach configuration rather than a hand-written list, so
     * a class with no middle berths is never offered one.
     */
    public List<BerthPreference> preferencesFor(String classCode)
            throws ValidationException, SQLException {
        return profileOf(classCode).supportedPreferences();
    }

    // ---------------------------------------------------------------- passengers

    /**
     * Validates one typed row and appends it to the draft.
     *
     * <p>The profile is passed in rather than looked up, so adding a passenger
     * touches no database and the caller may safely run it on the event dispatch
     * thread. The screen loads the profile once when the form opens.
     *
     * <p>The draft is left untouched when validation fails, so a rejected row
     * never half-joins the party.
     *
     * @throws ValidationException naming the field at fault
     */
    public PassengerDraft addPassenger(BookingDraft draft, ClassProfile profile,
                                       PassengerInput input)
            throws ValidationException {
        String fullName = ContactRules.trimToNull(input.fullName());
        if (fullName == null) {
            throw new ValidationException("fullName", "Enter the passenger's full name.");
        }

        int age = parseAge(input.age());
        Gender gender = parseGender(input.gender());
        BerthPreference berth = parseBerthPreference(input.berthPreference(), profile);
        String meal = parseMealPreference(input.mealPreference(), profile);

        requireRoomFor(draft);

        PassengerDraft passenger = new PassengerDraft(fullName, age, gender, berth, meal);
        draft.addPassenger(passenger);
        return passenger;
    }

/**
     * Re-checks every passenger already in the draft, against the class as the
     * database describes it now.
     *
     * <p>Needed because the passenger form validated these rows when they were
     * typed, and time has passed since. The age range is configurable and the
     * class's coaches could have changed, so a row that was valid on the form
     * may not be valid at the moment of booking. The booking engine calls this
     * before it locks anything, so a stale draft is refused in seconds rather
     * than after a berth has been held.
     *
     * <p>Reuses the same bounds and the same {@link ClassProfile#supports} check
     * as {@link #addPassenger} rather than restating them. The two do differ in
     * one way, deliberately: this takes already-parsed values, so it checks what
     * is stored instead of re-parsing what was typed. Re-parsing would only
     * prove the enum can still spell its own name.
     *
     * @throws ValidationException naming the passenger at fault
     */
    public void revalidatePassengers(BookingDraft draft, ClassProfile profile)
            throws ValidationException {
        List<PassengerDraft> passengers = draft.passengers();
        for (int index = 0; index < passengers.size(); index++) {
            PassengerDraft passenger = passengers.get(index);
            String who = "Passenger " + (index + 1)
                    + (passenger.fullName() == null ? "" : " (" + passenger.fullName() + ")");

            if (ContactRules.trimToNull(passenger.fullName()) == null) {
                throw new ValidationException("passengers", who + " has no name.");
            }
            if (passenger.age() < minAge || passenger.age() > maxAge) {
                throw new ValidationException("passengers",
                        who + " is " + passenger.age() + " years old, outside the "
                                + minAge + "-" + maxAge + " range now in force.");
            }
            if (passenger.gender() == null) {
                throw new ValidationException("passengers", who + " has no gender.");
            }
            BerthPreference wanted = passenger.berthPreference();
            if (wanted != null && !profile.supports(wanted)) {
                throw new ValidationException("passengers",
                        who + " asked for " + wanted.label() + ", which "
                                + profile.className() + " no longer offers.");
            }
            String meal = ContactRules.trimToNull(passenger.mealPreference());
            if (meal != null && !profile.hasPantry()) {
                throw new ValidationException("passengers",
                        who + " chose a meal, but " + profile.className()
                                + " has no pantry car.");
            }
        }
    }

    /**
     * Removes one passenger.
     *
     * @throws ValidationException when there is no passenger at that position, so
     *     a stale click cannot quietly delete the wrong person
     */
    public void removePassenger(BookingDraft draft, int index) throws ValidationException {
        if (index < 0 || index >= draft.passengerCount()) {
            throw new ValidationException("passengers", "That passenger is no longer listed.");
        }
        draft.removePassenger(index);
    }

    /**
     * Whether one more passenger may be added.
     *
     * <p>The party size was fixed on the search screen, so the limit here is the
     * smaller of that number and the configured cap. The search screen could not
     * have offered more than the cap, so in practice the search figure wins.
     */
    public boolean canAddPassenger(BookingDraft draft) {
        return draft.passengerCount() < allowedPassengers(draft)
                && draft.passengerCount() < maxPassengers;
    }

    private void requireRoomFor(BookingDraft draft) throws ValidationException {
        int allowed = allowedPassengers(draft);
        if (draft.passengerCount() >= allowed) {
            throw new ValidationException("passengers",
                    "This booking is for " + allowed + " passenger"
                            + (allowed == 1 ? "" : "s") + "; remove one before adding another.");
        }
    }

    private int allowedPassengers(BookingDraft draft) {
        return Math.min(draft.selection().passengers(), maxPassengers);
    }

    private int parseAge(String typed) throws ValidationException {
        String trimmed = ContactRules.trimToNull(typed);
        if (trimmed == null) {
            throw new ValidationException("age", "Enter the passenger's age in years.");
        }
        int age;
        try {
            age = Integer.parseInt(trimmed);
        } catch (NumberFormatException notANumber) {
            throw new ValidationException("age", "Age must be a whole number of years.");
        }
        if (age < minAge || age > maxAge) {
            throw new ValidationException("age",
                    "Age must be between " + minAge + " and " + maxAge + " years.");
        }
        return age;
    }

    private Gender parseGender(String typed) throws ValidationException {
        String trimmed = ContactRules.trimToNull(typed);
        if (trimmed == null) {
            throw new ValidationException("gender", "Choose a gender.");
        }
        for (Gender candidate : Gender.values()) {
            if (candidate.name().equalsIgnoreCase(trimmed) || candidate.label().equalsIgnoreCase(trimmed)) {
                return candidate;
            }
        }
        throw new ValidationException("gender", "Choose a gender from the list.");
    }

    /**
     * Resolves a preference against what the class can actually offer.
     *
     * <p>A preference the class has no berths for is refused rather than stored,
     * because accepting it would promise something allocation could not deliver.
     */
    private BerthPreference parseBerthPreference(String typed, ClassProfile profile)
            throws ValidationException {
        String trimmed = ContactRules.trimToNull(typed);
        if (trimmed == null) {
            return BerthPreference.NO_PREFERENCE;
        }
        for (BerthPreference candidate : BerthPreference.values()) {
            boolean matches = candidate.name().equalsIgnoreCase(trimmed)
                    || candidate.label().equalsIgnoreCase(trimmed);
            if (matches) {
                if (profile.supports(candidate)) {
                    return candidate;
                }
                throw new ValidationException("berthPreference", unsupportedMessage(profile, candidate));
            }
        }
        throw new ValidationException("berthPreference", "Choose a berth preference from the list.");
    }

    private String unsupportedMessage(ClassProfile profile, BerthPreference preference) {
        if (!profile.isSleeper() && preference == BerthPreference.LOWER) {
            return profile.className() + " is a seated class, so it has no lower berth.";
        }
        return profile.className() + " does not have " + preference.label().toLowerCase() + " berths.";
    }

    /**
     * A meal is only meaningful where the train has a pantry.
     *
     * <p>A meal typed for a class without catering is refused instead of dropped:
     * silently discarding something the passenger deliberately chose is worse
     * than telling them the train cannot serve it.
     */
    private String parseMealPreference(String typed, ClassProfile profile) throws ValidationException {
        String meal = ContactRules.trimToNull(typed);
        if (meal == null) {
            return null;
        }
        if (!profile.hasPantry()) {
            throw new ValidationException("mealPreference",
                    profile.className() + " has no pantry, so no meal can be requested.");
        }
        return meal;
    }

    // ---------------------------------------------------------------- contact

    /**
     * Validates and stores who to contact about this booking.
     *
     * <p>Kept apart from passenger rows on purpose: the contact is one person for
     * the whole booking, and folding it into each passenger would repeat one
     * person's number across the party.
     *
     * @throws ValidationException naming the field at fault
     */
    public void setContact(BookingDraft draft, String mobile, String email)
            throws ValidationException {
        String cleanMobile = ContactRules.normaliseMobile(mobile);
        if (cleanMobile == null) {
            throw new ValidationException("mobile", "Enter a 10-digit contact mobile number.");
        }
        String cleanEmail = ContactRules.trimToNull(email);
        if (cleanEmail != null && !ContactRules.isValidEmail(cleanEmail)) {
            throw new ValidationException("email", "That does not look like a valid email address.");
        }
        draft.setContact(new BookingContact(cleanMobile, cleanEmail));
    }

    // ---------------------------------------------------------------- readiness and fare

    /**
     * Whether the draft has everything needed to go to review.
     *
     * <p>True is not "the form looks filled in": the passenger count must match
     * the party the traveller asked for on the search screen, and contact details
     * must have passed {@link #setContact}.
     */
    public boolean isReadyToContinue(BookingDraft draft) {
        return draft.passengerCount() == allowedPassengers(draft)
                && draft.passengerCount() > 0
                && draft.contact() != null;
    }

    /**
     * Explains why the draft is not ready, for the Continue button's tooltip and
     * its error message.
     *
     * @return {@code null} when the draft is ready
     */
    public String readinessProblem(BookingDraft draft) {
        if (draft.passengerCount() < allowedPassengers(draft)) {
            int missing = allowedPassengers(draft) - draft.passengerCount();
            return "Add details for " + missing + " more passenger"
                    + (missing == 1 ? "" : "s") + " before continuing.";
        }
        if (draft.contact() == null) {
            return "Enter a contact mobile number before continuing.";
        }
        return null;
    }

    /**
     * The fare rule behind a selection, loaded once per screen.
     *
     * <p>Kept separate from {@link #estimate(FareRule, int)} so the summary can
     * re-price the party on every edit without a query per edit.
     */
public FareRule fareRuleFor(TrainSelection selection) throws ValidationException, SQLException {
        return fares.requireRuleFor(selection);
    }

/**
     * Prices a party from an already-loaded rule. Pure, so it can run on the
     * event dispatch thread.
     *
     * <p>Each component is rounded once at scale two, so the displayed base and
     * distance amounts add up to the displayed per-passenger fare, and the
     * per-passenger figure multiplies up to the displayed total. Rounding only
     * at the end would let the summary show figures that do not add up.
     *
     * <p>An estimate, not a quote: no concession, catering, quota or refund is
     * included, because this project cannot price any of them. The same
     * arithmetic is what {@link FareService} uses to produce the amount that
     * actually gets written to {@code bookings.total_fare}.
     */
    public FareEstimate estimate(FareRule rule, int passengers) {
        return fares.quote(rule, passengers);
    }

    /**
     * Convenience for callers that want the estimate without managing a rule.
     */
    public FareEstimate estimate(BookingDraft draft) throws ValidationException, SQLException {
        return estimate(fareRuleFor(draft.selection()), draft.passengerCount());
    }
}