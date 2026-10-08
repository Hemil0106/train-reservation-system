-- =====================================================================
-- Train Reservation System - database schema
-- Target: MySQL 8.x (8.0.16+ for enforced CHECK constraints), InnoDB
--
-- Usage (run as a DBA account, NOT as the application's own account):
--   mysql -u <dba-user> -p < database/schema.sql
--
-- This script drops and recreates ONLY the single `railway` database.
-- It never touches any other schema on the server.
--
-- Design notes
-- ------------
-- * A Journey is one train on one calendar date, and is the unit that
--   availability is counted against. This mirrors the legacy bus app,
--   which keyed availability by "BUS03|2026-10-05". Journeys are created
--   on demand, the first time a date is searched or booked.
-- * A Coach belongs to a Train (a fixed rake), not to a Journey. Coaches
--   that do not run on a given journey are marked DETACHED in
--   journey_coaches rather than deleted, so history survives.
-- * A Berth belongs to a Coach. Its physical attributes never change, so
--   booking-time values are copied into seat_reservations as an immutable
--   snapshot for the ticket.
-- * Nothing in the booking path is ever hard-deleted. Cancellations write
--   a row to `cancellations` and flip statuses, which is why the booking
--   tables use ON DELETE RESTRICT rather than CASCADE. The only CASCADEs
--   are for child rows with no history of their own (train_stops,
--   berths, journey_coaches).
-- * Money is DECIMAL everywhere. No FLOAT or DOUBLE touches a fare.
-- =====================================================================

DROP DATABASE IF EXISTS railway;
CREATE DATABASE railway
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_0900_ai_ci;

USE railway;

-- =====================================================================
-- 1. users
--    Application logins.
--
--    password_hash is deliberately NULLABLE. The demo accounts in
--    sample_data.sql ship with no password set at all, so not a single
--    byte of credential material is committed to source control. A NULL
--    hash means "no password set yet" and must be refused at login.
-- =====================================================================
CREATE TABLE users (
    user_id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    username        VARCHAR(50)     NOT NULL,
    full_name       VARCHAR(100)    NOT NULL,
    email           VARCHAR(254)    NULL,
    mobile          VARCHAR(15)     NULL,
    password_hash   VARCHAR(255)    NULL COMMENT 'NULL = no password set; never store plaintext',
    role            ENUM('CUSTOMER','ADMIN') NOT NULL DEFAULT 'CUSTOMER',
    is_active       BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP
                                    ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id),
    UNIQUE KEY uq_users_username (username),
    UNIQUE KEY uq_users_email (email),
    KEY ix_users_mobile (mobile),
    CONSTRAINT ck_users_email_format
        CHECK (email IS NULL OR email LIKE '%_@_%._%')
) ENGINE=InnoDB;

-- =====================================================================
-- 2. travel_classes
--    What each configured class means. A train does NOT offer every
--    class: a train offers exactly the classes that appear in its
--    `coaches` rows, so "does this train run 2A" is answered by the
--    coaches table and never assumed.
--
--    seats_per_coach is the berth count of ONE demo coach of this class.
--    sample_data.sql uses it to lay out berths, which keeps the demo rake
--    consistent with the class definitions instead of hard-coding counts.
-- =====================================================================
CREATE TABLE travel_classes (
    class_code       VARCHAR(5)   NOT NULL COMMENT 'SL, 3A, 2A, 1A, CC',
    class_name       VARCHAR(60)  NOT NULL,
    class_order      SMALLINT     NOT NULL COMMENT 'lower sorts first: general before AC',
    seats_per_coach  SMALLINT     NOT NULL,
    is_ac            BOOLEAN      NOT NULL DEFAULT FALSE,
    is_sleeper       BOOLEAN      NOT NULL DEFAULT FALSE COMMENT 'has LOWER/MIDDLE/UPPER',
    has_pantry       BOOLEAN      NOT NULL DEFAULT FALSE,
    PRIMARY KEY (class_code),
    UNIQUE KEY uq_classes_order (class_order)
) ENGINE=InnoDB;

-- =====================================================================
-- 3. stations
-- =====================================================================
CREATE TABLE stations (
station_code     VARCHAR(4)  NOT NULL COMMENT 'unique station code, 2-4 uppercase letters',
    station_name     VARCHAR(80)  NOT NULL,
    city             VARCHAR(60)  NOT NULL,
    zone             VARCHAR(20)  NULL,
    is_active        BOOLEAN      NOT NULL DEFAULT TRUE,
    PRIMARY KEY (station_code),
    UNIQUE KEY uq_stations_name (station_name),
    KEY ix_stations_city (city),
    KEY ix_stations_name_prefix (station_name(10)),
    -- Real codes vary in length (ST, KYN, PUNE, CSTM), so 2-4 uppercase letters.
    CONSTRAINT ck_stations_code CHECK (
        CHAR_LENGTH(station_code) BETWEEN 2 AND 4
        AND station_code = UPPER(station_code)
    )
) ENGINE=InnoDB;

-- =====================================================================
-- 4. trains
-- =====================================================================
CREATE TABLE trains (
    train_no         VARCHAR(10)  NOT NULL COMMENT 'e.g. 12951',
    train_name       VARCHAR(100) NOT NULL,
    train_type       ENUM('SUPERFAST','EXPRESS','PASSENGER','SUBURBAN')
                                   NOT NULL DEFAULT 'EXPRESS',
    runs_daily       BOOLEAN      NOT NULL DEFAULT TRUE,
    distance_km      INT          NULL COMMENT 'total route length',
    is_active        BOOLEAN      NOT NULL DEFAULT TRUE,
    PRIMARY KEY (train_no),
    UNIQUE KEY uq_trains_name (train_name),
    KEY ix_trains_type (train_type)
) ENGINE=InnoDB;

-- =====================================================================
-- 5. train_stops
--    The ordered route of a train, and what makes boarding and destination
--    selectable: a booking must name two stops of the SAME train, and the
--    boarding stop must come before the destination stop.
--
--    day_offset supports journeys passing midnight. A train leaving 21:00
--    and arriving 01:00 next day has arrival day_offset = 1. Such a train
--    is attributed to its DEPARTURE date for availability.
--
--    distance_km is cumulative from the train's origin, so the distance
--    between any two stops is a subtraction.
-- =====================================================================
CREATE TABLE train_stops (
    train_no         VARCHAR(10) NOT NULL,
    stop_seq         SMALLINT    NOT NULL COMMENT '1-based position along the route',
    station_code     VARCHAR(4)     NOT NULL,
    arrival_time     TIME        NULL COMMENT 'NULL at the originating stop',
    departure_time   TIME        NOT NULL,
    day_offset       SMALLINT    NOT NULL DEFAULT 0 COMMENT 'days after the departure date',
    distance_km      INT         NOT NULL COMMENT 'cumulative distance from origin',
    PRIMARY KEY (train_no, stop_seq),
    UNIQUE KEY uq_stop_station (train_no, station_code)
                     COMMENT 'a train does not visit the same station twice',
    KEY ix_stops_station (station_code, stop_seq),
    CONSTRAINT fk_stops_train FOREIGN KEY (train_no)
        REFERENCES trains (train_no) ON DELETE CASCADE,
    CONSTRAINT fk_stops_station FOREIGN KEY (station_code)
        REFERENCES stations (station_code) ON DELETE RESTRICT,
    CONSTRAINT ck_stops_seq_positive CHECK (stop_seq >= 1),
    CONSTRAINT ck_stops_day_offset CHECK (day_offset >= 0),
    CONSTRAINT ck_stops_distance CHECK (distance_km >= 0),
    -- Only the originating stop may omit an arrival time.
    CONSTRAINT ck_stops_arrival CHECK (
        stop_seq = 1 OR arrival_time IS NOT NULL
    )
) ENGINE=InnoDB;

-- =====================================================================
-- 6. coaches
--    Part of a train's fixed rake. class_code is a foreign key, so the set
--    of classes a train offers is exactly the set its coaches declare.
-- =====================================================================
CREATE TABLE coaches (
    coach_id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    train_no          VARCHAR(10)   NOT NULL,
    coach_number      VARCHAR(6)    NOT NULL COMMENT 'e.g. C1, B2, AC1',
    class_code        VARCHAR(5)    NOT NULL,
    coach_type        VARCHAR(30)   NOT NULL COMMENT 'e.g. AC-3 Tier, AC-2 Tier, LHB Seating',
    position_in_train SMALLINT     NOT NULL COMMENT 'ordering along the rake',
    has_toilet        BOOLEAN       NOT NULL DEFAULT FALSE,
    PRIMARY KEY (coach_id),
    UNIQUE KEY uq_coach_number (train_no, coach_number),
    KEY ix_coaches_class (class_code),
    CONSTRAINT fk_coaches_train FOREIGN KEY (train_no)
        REFERENCES trains (train_no) ON DELETE CASCADE,
    CONSTRAINT fk_coaches_class FOREIGN KEY (class_code)
        REFERENCES travel_classes (class_code) ON DELETE RESTRICT,
    CONSTRAINT ck_coaches_position CHECK (position_in_train >= 1)
) ENGINE=InnoDB;

-- =====================================================================
-- 7. berths
--    Physical layout of one coach. Berths are 1-based and never
--    renumbered.
--
--      position   LOWER / MIDDLE / UPPER in a sleeper class,
--                 WINDOW / AISLE / SIDE in a seating class.
--      berth_type SEAT / SLEEPER / AC_BERTH, i.e. the coach layout
--                 category, independent of class naming.
--
--    A berth's position must suit its class: a 3A berth cannot be LOWER
--    while a CC seat cannot be UPPER. MySQL forbids subqueries in CHECK
--    constraints, so this rule cannot be a constraint here. It is
--    enforced by (a) sample_data.sql generating positions from
--    travel_classes.is_sleeper, and (b) DatabaseSelfCheck asserting no
--    mismatched rows exist.
-- =====================================================================
CREATE TABLE berths (
    berth_id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    coach_id         BIGINT UNSIGNED NOT NULL,
    berth_number     SMALLINT    NOT NULL COMMENT '1-based, unique within the coach',
    berth_type       ENUM('SEAT','SLEEPER','AC_BERTH') NOT NULL,
    position         ENUM('LOWER','MIDDLE','UPPER','WINDOW','AISLE','SIDE') NOT NULL,
    PRIMARY KEY (berth_id),
    UNIQUE KEY uq_berth_number (coach_id, berth_number),
    KEY ix_berths_position (coach_id, position),
    KEY ix_berths_type (coach_id, berth_type),
    CONSTRAINT fk_berths_coach FOREIGN KEY (coach_id)
        REFERENCES coaches (coach_id) ON DELETE CASCADE,
    CONSTRAINT ck_berths_number CHECK (berth_number >= 1)
) ENGINE=InnoDB;

-- =====================================================================
-- 8. journeys  (journey-date-specific availability anchor)
--    One row per train per calendar date, created on demand. Everything
--    downstream joins exactly this one table rather than pairing a train
--    number with a date string.
-- =====================================================================
CREATE TABLE journeys (
    journey_id       BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    train_no         VARCHAR(10)  NOT NULL,
    journey_date     DATE         NOT NULL COMMENT 'always the DEPARTURE date',
    service_status   ENUM('SCHEDULED','CANCELLED','FULL') NOT NULL DEFAULT 'SCHEDULED',
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (journey_id),
    UNIQUE KEY uq_journey_train_date (train_no, journey_date)
                     COMMENT 'a train runs at most one journey per date',
    UNIQUE KEY uq_journey_train_id (train_no, journey_id)
                     COMMENT 'lets bookings prove their denormalised train_no agrees',
    KEY ix_journeys_date (journey_date),
    CONSTRAINT fk_journeys_train FOREIGN KEY (train_no)
        REFERENCES trains (train_no) ON DELETE RESTRICT
) ENGINE=InnoDB;

-- =====================================================================
-- 9. journey_coaches  (per-journey rake, i.e. "journey inventory")
--    Which of the train's coaches actually run on this journey. A coach
--    withdrawn for one date is DETACHED, never deleted, so historical
--    bookings keep referring to a real coach row.
-- =====================================================================
CREATE TABLE journey_coaches (
    journey_id       BIGINT UNSIGNED NOT NULL,
    coach_id         BIGINT UNSIGNED NOT NULL,
    coach_status     ENUM('ATTACHED','DETACHED') NOT NULL DEFAULT 'ATTACHED',
    PRIMARY KEY (journey_id, coach_id),
    KEY ix_journey_coaches_coach (coach_id),
    CONSTRAINT fk_jc_journey FOREIGN KEY (journey_id)
        REFERENCES journeys (journey_id) ON DELETE CASCADE,
    CONSTRAINT fk_jc_coach FOREIGN KEY (coach_id)
        REFERENCES coaches (coach_id) ON DELETE RESTRICT
) ENGINE=InnoDB;

-- =====================================================================
-- 10. fare_rules
--     Resolution is most-specific-wins:
--       exact route (origin + destination + distance)
--         > route pair (origin + destination)
--         > class default (origin NULL, destination NULL)
--     A class default supplies base_fare + per_km_fare; a route-specific
--     rule overrides it. valid_from / valid_to allow a fare revision to
--     apply only from a given date without touching past bookings.
--
--     quota_seats is the reserved quota PER COACH of the class.
-- =====================================================================
CREATE TABLE fare_rules (
    fare_rule_id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    class_code              VARCHAR(5)    NOT NULL,
    origin_station_code     VARCHAR(4)      NULL COMMENT 'NULL = any origin',
    destination_station_code VARCHAR(4)     NULL COMMENT 'NULL = any destination',
    distance_km             INT          NULL COMMENT 'NULL = derive from train_stops',
    base_fare               DECIMAL(10,2) NOT NULL,
    per_km_fare             DECIMAL(10,2) NOT NULL,
    quota_seats             SMALLINT     NOT NULL DEFAULT 0 COMMENT 'reserved quota per coach',
    tatkal_quota_seats      SMALLINT     NOT NULL DEFAULT 0,
    valid_from              DATE         NOT NULL,
    valid_to                DATE         NULL COMMENT 'NULL = open-ended',
    PRIMARY KEY (fare_rule_id),
    KEY ix_fare_lookup (class_code, origin_station_code, destination_station_code, valid_from),
    CONSTRAINT fk_fare_class FOREIGN KEY (class_code)
        REFERENCES travel_classes (class_code) ON DELETE RESTRICT,
    CONSTRAINT fk_fare_origin FOREIGN KEY (origin_station_code)
        REFERENCES stations (station_code) ON DELETE RESTRICT,
    CONSTRAINT fk_fare_destination FOREIGN KEY (destination_station_code)
        REFERENCES stations (station_code) ON DELETE RESTRICT,
    CONSTRAINT ck_fare_non_negative CHECK (
        base_fare >= 0 AND per_km_fare >= 0
        AND quota_seats >= 0 AND tatkal_quota_seats >= 0
    ),
    CONSTRAINT ck_fare_quota CHECK (tatkal_quota_seats <= quota_seats),
    CONSTRAINT ck_fare_dates CHECK (valid_to IS NULL OR valid_to >= valid_from),
    -- A destination-specific fare always needs an origin, so the pair can never
    -- be half-filled. (The first version of this check had the two columns the
    -- other way round, which forbade a route from A to "anywhere" and allowed a
    -- fare priced for "anywhere to B" - the opposite of the rule.)
    CONSTRAINT ck_fare_specificity CHECK (
        destination_station_code IS NULL OR origin_station_code IS NOT NULL
    )
) ENGINE=InnoDB;

-- =====================================================================
-- 11. bookings
--     One row per booking, holding one or more passengers.
--
--     PNR: the primary key is an AUTO_INCREMENT surrogate, so a PNR cannot
--     collide even under concurrent transactions. The legacy app's static
--     nextBookingNumber counter could not guarantee that.
--
--     There is deliberately NO stored or generated 10-character PNR column.
--     MySQL refuses a generated column that refers to an AUTO_INCREMENT
--     column, and a second copy of the number would only be able to drift
--     from the first. The 10-digit display form is produced in Java with
--     String.format("%010d", pnr), so there is exactly one source of truth.
--
--     destination_stop_seq > boarding_stop_seq is the database-level
--     guarantee that a passenger travels forwards along the route.
--
--     train_no is denormalised from journeys for the common
--     "trains on date X" query. The composite foreign key proves it agrees
--     with the journey; only journey_date is denormalised without a
--     constraint, and journeys.journey_date via journey_id is
--     authoritative.
-- =====================================================================
CREATE TABLE bookings (
    -- The PNR: ten digits, generated collision-resistant in Java rather than
    -- handed out as a sequence. AUTO_INCREMENT stays on the column so the
    -- database can still step past an explicitly supplied value, but the
    -- booking engine always supplies its own (see PnrGenerator). PRIMARY KEY
    -- is the database-enforced uniqueness guarantee; a collision is caught as
    -- a duplicate-key error and retried, never assumed away.
    pnr                      BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    booking_date             DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    journey_id               BIGINT UNSIGNED NOT NULL,
    train_no                 VARCHAR(10) NOT NULL,
    journey_date             DATE        NOT NULL COMMENT 'denormalised from journeys',
    boarding_stop_seq        SMALLINT    NOT NULL,
    boarding_station_code    VARCHAR(4)     NOT NULL,
    destination_stop_seq     SMALLINT    NOT NULL,
    destination_station_code VARCHAR(4)     NOT NULL,
    travel_class_code        VARCHAR(5)  NOT NULL,
    total_fare               DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    passenger_count          SMALLINT    NOT NULL DEFAULT 1,
    status                   ENUM('CONFIRMED','PARTIALLY_CANCELLED','CANCELLED','WAITLISTED')
                                        NOT NULL DEFAULT 'CONFIRMED',
    contact_name             VARCHAR(100) NOT NULL,
    contact_mobile           VARCHAR(15)  NOT NULL,
    contact_email            VARCHAR(254) NULL,
    user_id                  BIGINT UNSIGNED NULL COMMENT 'NULL = guest booking',
    created_at               DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at               DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP
                                         ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (pnr),
    KEY ix_bookings_journey (journey_id),
    KEY ix_bookings_train_date (train_no, journey_date),
    KEY ix_bookings_user (user_id),
    KEY ix_bookings_status (status),
    CONSTRAINT fk_bookings_journey FOREIGN KEY (journey_id)
        REFERENCES journeys (journey_id) ON DELETE RESTRICT,
    -- Proves the denormalised train_no matches the journey it points at.
    CONSTRAINT fk_bookings_train_agrees FOREIGN KEY (train_no, journey_id)
        REFERENCES journeys (train_no, journey_id) ON DELETE RESTRICT,
    CONSTRAINT fk_bookings_boarding_stop FOREIGN KEY (train_no, boarding_stop_seq)
        REFERENCES train_stops (train_no, stop_seq) ON DELETE RESTRICT,
    CONSTRAINT fk_bookings_destination_stop FOREIGN KEY (train_no, destination_stop_seq)
        REFERENCES train_stops (train_no, stop_seq) ON DELETE RESTRICT,
    CONSTRAINT fk_bookings_class FOREIGN KEY (travel_class_code)
        REFERENCES travel_classes (class_code) ON DELETE RESTRICT,
    CONSTRAINT fk_bookings_user FOREIGN KEY (user_id)
        REFERENCES users (user_id) ON DELETE RESTRICT,
    CONSTRAINT ck_bookings_segment CHECK (destination_stop_seq > boarding_stop_seq),
    CONSTRAINT ck_bookings_fare CHECK (total_fare >= 0),
    CONSTRAINT ck_bookings_passengers CHECK (passenger_count BETWEEN 1 AND 6),
    CONSTRAINT ck_bookings_email CHECK (
        contact_email IS NULL OR contact_email LIKE '%_@_%._%'
    )
) ENGINE=InnoDB;

-- =====================================================================
-- 12. passengers
--     The people on a booking. The berth a passenger REQUESTED is stored
--     here as berth_preference; the berth they were actually GIVEN lives
--     in seat_reservations. Keeping the two apart means a preference
--     survives as evidence after allocation settles on something else,
--     and a passenger who has no berth yet still has a row.
--
--     A waitlisted passenger therefore exists here with a preference and
--     simply has no seat_reservations row until a berth is allocated.
-- =====================================================================
CREATE TABLE passengers (
    passenger_id     BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    pnr              BIGINT UNSIGNED NOT NULL,
    full_name        VARCHAR(100) NOT NULL,
    age              TINYINT UNSIGNED NOT NULL,
    gender           ENUM('MALE','FEMALE','OTHER') NOT NULL DEFAULT 'OTHER',
    berth_preference ENUM('LOWER','MIDDLE','UPPER','WINDOW','AISLE','SIDE','NO_PREFERENCE')
                                   NOT NULL DEFAULT 'NO_PREFERENCE',
    is_primary       BOOLEAN      NOT NULL DEFAULT FALSE COMMENT 'the paying contact',
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (passenger_id),
    KEY ix_passengers_pnr (pnr),
    CONSTRAINT fk_passengers_booking FOREIGN KEY (pnr)
        REFERENCES bookings (pnr) ON DELETE CASCADE,
    CONSTRAINT ck_passengers_age CHECK (age <= 120),
    CONSTRAINT ck_passengers_name CHECK (CHAR_LENGTH(TRIM(full_name)) > 0)
) ENGINE=InnoDB;

-- =====================================================================
-- 13. seat_reservations
--     The final berth allocation: one row per passenger per booking.
--
--     SEGMENT OVERLAP
--     --------------
--     A berth may legitimately be sold twice on one journey when the two
--     bookings do not overlap in time: CSTM->PUNE (stops 1..4) and
--     PUNE->BZA (stops 4..7) never share the seat. MySQL cannot express
--     "these half-open intervals must not intersect" with a unique index,
--     so each reservation stores its own segment as stop positions and
--     the booking transaction rejects an overlap with this range test:
--
--       r.from_stop_seq < :toStopSeq AND r.to_stop_seq > :fromStopSeq
--
--     ix_seat_reservations_overlap (journey_id, berth_id, status) makes
--     that an index lookup on the equality prefix.
--
--     berth_number / berth_type / position / travel_class_code are copied
--     here as an immutable snapshot: if the rake is reorganised next
--     season, an old ticket still shows the berth actually sold.
--
--     There is no WAITLISTED status here on purpose. A passenger with no
--     berth has no seat_reservations row at all; see the passengers table.
--     RAC means a berth is held but the passenger is expected to travel
--     standing, and counts as occupying the berth for overlap purposes.
-- =====================================================================
CREATE TABLE seat_reservations (
    reservation_id    BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    pnr               BIGINT UNSIGNED NOT NULL,
    passenger_id      BIGINT UNSIGNED NOT NULL,
    journey_id        BIGINT UNSIGNED NOT NULL,
    berth_id          BIGINT UNSIGNED NOT NULL,
    coach_id          BIGINT UNSIGNED NOT NULL COMMENT 'denormalised from berth',
    travel_class_code VARCHAR(5)   NOT NULL COMMENT 'snapshot at booking time',
    from_stop_seq     SMALLINT    NOT NULL,
    to_stop_seq       SMALLINT    NOT NULL,
    from_station_code VARCHAR(4)     NOT NULL,
    to_station_code   VARCHAR(4)     NOT NULL,
    berth_number      SMALLINT    NOT NULL COMMENT 'snapshot at booking time',
    berth_type        ENUM('SEAT','SLEEPER','AC_BERTH') NOT NULL,
    position          ENUM('LOWER','MIDDLE','UPPER','WINDOW','AISLE','SIDE') NOT NULL,
    amount            DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    status            ENUM('CONFIRMED','CANCELLED','RAC') NOT NULL DEFAULT 'CONFIRMED',
    created_at        DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (reservation_id),
    UNIQUE KEY uq_reservation_passenger (pnr, passenger_id)
                      COMMENT 'one passenger gets at most one berth per booking',
    KEY ix_seat_reservations_overlap (journey_id, berth_id, status),
    KEY ix_seat_reservations_pnr (pnr),
    KEY ix_seat_reservations_coach (journey_id, coach_id, status),
    CONSTRAINT fk_res_booking FOREIGN KEY (pnr)
        REFERENCES bookings (pnr) ON DELETE RESTRICT,
    CONSTRAINT fk_res_passenger FOREIGN KEY (passenger_id)
        REFERENCES passengers (passenger_id) ON DELETE RESTRICT,
    CONSTRAINT fk_res_journey FOREIGN KEY (journey_id)
        REFERENCES journeys (journey_id) ON DELETE RESTRICT,
    CONSTRAINT fk_res_berth FOREIGN KEY (berth_id)
        REFERENCES berths (berth_id) ON DELETE RESTRICT,
    CONSTRAINT fk_res_coach FOREIGN KEY (coach_id)
        REFERENCES coaches (coach_id) ON DELETE RESTRICT,
    CONSTRAINT ck_res_segment CHECK (to_stop_seq > from_stop_seq),
    CONSTRAINT ck_res_amount CHECK (amount >= 0)
) ENGINE=InnoDB;

-- =====================================================================
-- 14. cancellations
--     Cancellation is recorded, never deleted. The booking row and its
--     seat_reservations rows survive with status = 'CANCELLED', so what
--     was sold and then refunded is always readable afterwards.
--
--     passenger_id / reservation_id are NULL for a whole-booking
--     cancellation and set for a single-passenger cancellation.
-- =====================================================================
CREATE TABLE cancellations (
    cancellation_id      BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    pnr                  BIGINT UNSIGNED NOT NULL,
    passenger_id         BIGINT UNSIGNED NULL COMMENT 'NULL = whole booking',
    reservation_id       BIGINT UNSIGNED NULL COMMENT 'NULL = whole booking',
    cancelled_at         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    cancelled_by_user_id BIGINT UNSIGNED NULL,
    reason               VARCHAR(200) NULL,
    is_refundable        BOOLEAN      NOT NULL DEFAULT TRUE,
    refund_amount        DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    cancellation_charges DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    refund_status        ENUM('PENDING','PROCESSED','FAILED','NOT_APPLICABLE')
                                    NOT NULL DEFAULT 'PENDING',
    PRIMARY KEY (cancellation_id),
    KEY ix_cancellations_pnr (pnr),
    KEY ix_cancellations_date (cancelled_at),
    KEY ix_cancellations_passenger (passenger_id),
    CONSTRAINT fk_cancel_booking FOREIGN KEY (pnr)
        REFERENCES bookings (pnr) ON DELETE RESTRICT,
    CONSTRAINT fk_cancel_passenger FOREIGN KEY (passenger_id)
        REFERENCES passengers (passenger_id) ON DELETE RESTRICT,
    CONSTRAINT fk_cancel_reservation FOREIGN KEY (reservation_id)
        REFERENCES seat_reservations (reservation_id) ON DELETE RESTRICT,
    CONSTRAINT fk_cancel_user FOREIGN KEY (cancelled_by_user_id)
        REFERENCES users (user_id) ON DELETE RESTRICT,
    CONSTRAINT ck_cancel_amounts CHECK (
        refund_amount >= 0 AND cancellation_charges >= 0
    ),
    -- Naming a reservation without a passenger is not meaningful.
    CONSTRAINT ck_cancel_target CHECK (
        passenger_id IS NOT NULL OR reservation_id IS NULL
    )
) ENGINE=InnoDB;

-- =====================================================================
-- View: berths present on a journey, with how many live holds they carry.
--
-- This is deliberately NOT called "available". Whether a berth is
-- available depends on the travel segment being asked about, and a view
-- cannot take a parameter, so the overlap test stays in the caller. This
-- view answers the parameter-free half of the question - which berths run
-- on this journey, and how many are currently held - which is what
-- Milestone 3 loads before applying:
--
--   AND r.from_stop_seq < :toStopSeq
--   AND r.to_stop_seq   > :fromStopSeq
--
-- confirmed_count > 0 does not by itself mean the berth is unavailable;
-- it means at least one hold exists and the segment must be compared.
-- =====================================================================
CREATE OR REPLACE VIEW v_journey_berth_occupancy AS
SELECT
    j.journey_id,
    j.train_no,
    j.journey_date,
    c.coach_id,
    c.coach_number,
    c.class_code,
    b.berth_id,
    b.berth_number,
    b.berth_type,
    b.position,
    COALESCE(occ.hold_count, 0) AS hold_count
FROM journeys j
JOIN journey_coaches jc
    ON jc.journey_id = j.journey_id
   AND jc.coach_status = 'ATTACHED'
JOIN coaches c
    ON c.coach_id = jc.coach_id
JOIN berths b
    ON b.coach_id = c.coach_id
LEFT JOIN (
    SELECT journey_id, berth_id, COUNT(*) AS hold_count
    FROM seat_reservations
    WHERE status IN ('CONFIRMED', 'RAC')
    GROUP BY journey_id, berth_id
) occ
    ON occ.journey_id = j.journey_id
   AND occ.berth_id = b.berth_id
WHERE j.service_status = 'SCHEDULED';

-- =====================================================================
-- End of schema.sql - 14 tables, 1 view, 0 stored procedures
-- =====================================================================