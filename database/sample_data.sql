-- =====================================================================
-- Train Reservation System - SAMPLE / DEMONSTRATION DATA
--
--   mysql -u <dba-user> -p < database/sample_data.sql
--
-- READ THIS FIRST
-- --------------
-- Everything below is FICTIOUS DEMONSTRATION DATA. Station codes, train
-- numbers and train names resemble real services so the schema is easy to
-- picture, but the schedules, fares and rake compositions are invented
-- for this project. This is NOT live railway data, it is NOT scraped from
-- any railway operator, and it must never be presented as real
-- availability.
--
-- There is deliberately NO live-availability data here:
--   * journeys            - inserted 0 rows
--   * journey_coaches     - inserted 0 rows
--   * bookings            - inserted 0 rows
--   * passengers          - inserted 0 rows
--   * seat_reservations   - inserted 0 rows
--   * cancellations       - inserted 0 rows
-- A journey is created on demand by the application the first time a date
-- is searched or booked, so seeding fake confirmed reservations would be
-- exactly the "fake live availability" this project must not show.
-- DatabaseSelfCheck instead exercises the whole booking path inside a
-- transaction that it rolls back, proving the schema works without
-- leaving any reservation behind.
--
-- What IS seeded: stations, travel classes, trains, train stops, coaches,
-- berths (generated), fare rules, and two demo user accounts.
-- =====================================================================

USE railway;

SET @demo_today := '2026-01-01';

-- ---------------------------------------------------------------------
-- travel_classes
--
-- seats_per_coach is what sample data below uses to lay out each coach's
-- berths, so the demo rake always agrees with the class definitions.
-- These are DEMO rake sizes chosen to keep the seed small; a real rake
-- carries far more berths.
-- ---------------------------------------------------------------------
INSERT INTO travel_classes
    (class_code, class_name, class_order, seats_per_coach, is_ac, is_sleeper, has_pantry)
VALUES
    ('SL',  'Sleeper Class (SL)',            1, 36, FALSE, TRUE,  FALSE),
    ('CC',  'Chair Car (CC)',                2, 10, TRUE,  FALSE, FALSE),
    ('3A',  'AC 3 Tier (3A)',                3, 18, TRUE,  TRUE,  TRUE),
    ('2A',  'AC 2 Tier (2A)',                4,  8, TRUE,  TRUE,  TRUE),
    ('1A',  'AC First Class (1A)',           5, 20, TRUE,  TRUE,  TRUE);

-- ---------------------------------------------------------------------
-- stations
-- Six stations along one corridor, in running order:
--   Mumbai -> Kalyan -> Pune -> Nashik Road -> Kopargaon -> Surat
-- ---------------------------------------------------------------------
INSERT INTO stations (station_code, station_name, city, zone) VALUES
    ('CSTM', 'Chhatrapati Shivaji Maharaj Terminus', 'Mumbai',   'WR'),
    ('KYN',  'Kalyan Junction',                     'Kalyan',   'WR'),
    ('PUNE', 'Pune Junction',                        'Pune',     'PN'),
    ('NAS',  'Nashik Road',                          'Nashik',   'WR'),
    ('KOP',  'Kopargaon',                            'Kopargaon','WR'),
    ('ST',   'Surat',                                'Surat',    'WR');

-- ---------------------------------------------------------------------
-- trains
-- Four services. Two run overnight and cross midnight (their later stops
-- carry day_offset = 1); two run in daylight. They deliberately offer
-- DIFFERENT sets of classes, because a train does not serve every class.
-- ---------------------------------------------------------------------
INSERT INTO trains (train_no, train_name, train_type, runs_daily, distance_km) VALUES
    ('12951', 'Mumbai Rajdhani Demo Service',    'SUPERFAST', TRUE, 180),
    ('12952', 'Mumbai Surat Night Demo Service', 'SUPERFAST', TRUE, 285),
    ('12137', 'Pune Intercity Demo Service',     'EXPRESS',   TRUE, 135),
    ('11041', 'Mumbai Deccan Daytime Demo',      'EXPRESS',   TRUE, 285);

-- ---------------------------------------------------------------------
-- train_stops
-- stop_seq 1..n gives the boarding/destination ordering.
-- distance_km is cumulative from the train's origin.
-- arrival_time is NULL only at stop 1 (the originating stop).
-- day_offset > 1 would mean a journey lasting more than one night; these
-- demo services all cross midnight at most once.
-- ---------------------------------------------------------------------

-- 12951: CSTM -> PUNE -> NAS, departs 19:30, arrives Nashik 03:05 next day.
INSERT INTO train_stops
    (train_no, stop_seq, station_code, arrival_time, departure_time, day_offset, distance_km)
VALUES
    ('12951', 1, 'CSTM', NULL,    '19:30:00', 0,   0),
    ('12951', 2, 'PUNE', '23:15:00', '23:30:00', 0, 150),
    ('12951', 3, 'NAS',  '03:05:00', '03:15:00', 1, 180);

-- 12952: CSTM -> PUNE -> NAS -> KOP -> ST, night service crossing midnight.
INSERT INTO train_stops
    (train_no, stop_seq, station_code, arrival_time, departure_time, day_offset, distance_km)
VALUES
    ('12952', 1, 'CSTM', NULL,    '21:15:00', 0,   0),
    ('12952', 2, 'PUNE', '01:40:00', '01:55:00', 1, 150),
    ('12952', 3, 'NAS',  '04:20:00', '04:35:00', 1, 200),
    ('12952', 4, 'KOP',  '06:05:00', '06:10:00', 1, 238),
    ('12952', 5, 'ST',   '08:45:00', '08:55:00', 1, 285);

-- 12137: PUNE -> NAS -> KOP -> ST, overnight. Note this service starts at
-- Pune, so it offers different classes from the two Mumbai services.
INSERT INTO train_stops
    (train_no, stop_seq, station_code, arrival_time, departure_time, day_offset, distance_km)
VALUES
    ('12137', 1, 'PUNE', NULL,    '21:00:00', 0,   0),
    ('12137', 2, 'NAS',  '00:20:00', '00:35:00', 1,  50),
    ('12137', 3, 'KOP',  '02:05:00', '02:15:00', 1,  88),
    ('12137', 4, 'ST',   '04:40:00', '04:50:00', 1, 135);

-- 11041: CSTM -> PUNE -> NAS -> KOP -> ST, daytime, day_offset 0 throughout.
INSERT INTO train_stops
    (train_no, stop_seq, station_code, arrival_time, departure_time, day_offset, distance_km)
VALUES
    ('11041', 1, 'CSTM', NULL,    '06:35:00', 0,   0),
    ('11041', 2, 'PUNE', '10:05:00', '10:20:00', 0, 150),
    ('11041', 3, 'NAS',  '12:50:00', '12:55:00', 0, 200),
    ('11041', 4, 'KOP',  '14:30:00', '14:40:00', 0, 238),
    ('11041', 5, 'ST',   '17:10:00', '17:20:00', 0, 285);

-- ---------------------------------------------------------------------
-- coaches
-- The rake of each train. Which classes a train offers is exactly the
-- set of classes appearing here - there is no assumption that it runs
-- every class.
--
--   12951  AC1 + AC2 + 2x AC3            (no SL, no CC)
--   12952  AC2 + AC3 + 3x SL             (no CC)
--   12137  AC3 + SL + CC                 (Chair Car only on this one)
--   11041  3x SL + 2x AC3 + AC2
-- ---------------------------------------------------------------------
INSERT INTO coaches
    (train_no, coach_number, class_code, coach_type, position_in_train, has_toilet)
VALUES
    -- 12951
    ('12951', 'AC1', '1A', 'AC First Class (1A)',  1, TRUE),
    ('12951', 'AC2', '2A', 'AC 2 Tier',            2, TRUE),
    ('12951', 'AC3', '3A', 'AC 3 Tier',            3, FALSE),
    ('12951', 'AC4', '3A', 'AC 3 Tier',            4, FALSE),
    -- 12952
    ('12952', 'AC1', '2A', 'AC 2 Tier',            1, TRUE),
    ('12952', 'AC2', '3A', 'AC 3 Tier',            2, FALSE),
    ('12952', 'S1',  'SL', 'LHB Sleeper',          3, FALSE),
    ('12952', 'S2',  'SL', 'LHB Sleeper',          4, FALSE),
    ('12952', 'S3',  'SL', 'LHB Sleeper',          5, FALSE),
    -- 12137
    ('12137', 'AC1', '3A', 'AC 3 Tier',            1, FALSE),
    ('12137', 'S1',  'SL', 'LHB Sleeper',          2, FALSE),
    ('12137', 'CC1', 'CC', 'LHB Chair Car',        3, FALSE),
    -- 11041
    ('11041', 'S1',  'SL', 'LHB Sleeper',          1, FALSE),
    ('11041', 'S2',  'SL', 'LHB Sleeper',          2, FALSE),
    ('11041', 'S3',  'SL', 'LHB Sleeper',          3, FALSE),
    ('11041', 'AC1', '3A', 'AC 3 Tier',            4, FALSE),
    ('11041', 'AC2', '3A', 'AC 3 Tier',            5, FALSE),
    ('11041', 'AC3', '2A', 'AC 2 Tier',            6, TRUE);

-- ---------------------------------------------------------------------
-- berths
--
-- Generated, not hand-typed: each coach gets exactly seats_per_coach
-- berths for its class, numbered 1..n, with the position cycle that suits
-- that class. A temporary numbers table is used instead of a recursive
-- CTE so the script runs on any MySQL 8.x without version-specific
-- syntax.
--
-- Position cycles:
--   SL, 3A   LOWER / MIDDLE / UPPER   (sleeper classes)
--   2A, 1A   LOWER / UPPER            (two-tier AC)
--   CC       WINDOW / AISLE           (seating class)
-- ---------------------------------------------------------------------
DROP TEMPORARY TABLE IF EXISTS demo_numbers;
CREATE TEMPORARY TABLE demo_numbers (
    n SMALLINT NOT NULL PRIMARY KEY
) ENGINE=InnoDB;

INSERT INTO demo_numbers (n) VALUES
    (1),(2),(3),(4),(5),(6),(7),(8),(9),(10),(11),(12),(13),(14),(15),(16),
    (17),(18),(19),(20),(21),(22),(23),(24),(25),(26),(27),(28),(29),(30),
    (31),(32),(33),(34),(35),(36);

-- SL: three-tier sleeper.
INSERT INTO berths (coach_id, berth_number, berth_type, position)
SELECT c.coach_id, d.n, 'SLEEPER',
       ELT(MOD(d.n - 1, 3) + 1, 'LOWER', 'MIDDLE', 'UPPER')
FROM coaches c
JOIN travel_classes tc ON tc.class_code = c.class_code
JOIN demo_numbers d ON d.n <= tc.seats_per_coach
WHERE tc.class_code = 'SL';

-- 3A: three-tier AC berth.
INSERT INTO berths (coach_id, berth_number, berth_type, position)
SELECT c.coach_id, d.n, 'AC_BERTH',
       ELT(MOD(d.n - 1, 3) + 1, 'LOWER', 'MIDDLE', 'UPPER')
FROM coaches c
JOIN travel_classes tc ON tc.class_code = c.class_code
JOIN demo_numbers d ON d.n <= tc.seats_per_coach
WHERE tc.class_code = '3A';

-- 2A: two-tier AC berth.
INSERT INTO berths (coach_id, berth_number, berth_type, position)
SELECT c.coach_id, d.n, 'AC_BERTH',
       ELT(MOD(d.n - 1, 2) + 1, 'LOWER', 'UPPER')
FROM coaches c
JOIN travel_classes tc ON tc.class_code = c.class_code
JOIN demo_numbers d ON d.n <= tc.seats_per_coach
WHERE tc.class_code = '2A';

-- 1A: two-tier AC berth.
INSERT INTO berths (coach_id, berth_number, berth_type, position)
SELECT c.coach_id, d.n, 'AC_BERTH',
       ELT(MOD(d.n - 1, 2) + 1, 'LOWER', 'UPPER')
FROM coaches c
JOIN travel_classes tc ON tc.class_code = c.class_code
JOIN demo_numbers d ON d.n <= tc.seats_per_coach
WHERE tc.class_code = '1A';

-- CC: seating class, two seats per bay.
INSERT INTO berths (coach_id, berth_number, berth_type, position)
SELECT c.coach_id, d.n, 'SEAT',
       ELT(MOD(d.n - 1, 2) + 1, 'WINDOW', 'AISLE')
FROM coaches c
JOIN travel_classes tc ON tc.class_code = c.class_code
JOIN demo_numbers d ON d.n <= tc.seats_per_coach
WHERE tc.class_code = 'CC';

DROP TEMPORARY TABLE demo_numbers;

-- ---------------------------------------------------------------------
-- fare_rules
--
-- Two levels, resolved most-specific-wins by the service layer:
--   1. class defaults   (origin NULL, destination NULL)
--   2. route overrides  (origin and destination both set)
--
-- base_fare is the class reservation charge, per_km_fare is added per km
-- of the travelled segment. quota_seats is reserved quota per coach.
-- tatkal_quota_seats <= quota_seats is enforced by a CHECK constraint.
--
-- These numbers are invented for demonstration.
-- ---------------------------------------------------------------------
INSERT INTO fare_rules
    (class_code, origin_station_code, destination_station_code, distance_km,
     base_fare, per_km_fare, quota_seats, tatkal_quota_seats, valid_from, valid_to)
VALUES
    -- class defaults
    ('SL', NULL, NULL, NULL,  25.00, 1.15, 0, 0, @demo_today, NULL),
    ('CC', NULL, NULL, NULL,  40.00, 2.00, 0, 0, @demo_today, NULL),
    ('3A', NULL, NULL, NULL,  50.00, 2.40, 4, 2, @demo_today, NULL),
    ('2A', NULL, NULL, NULL,  70.00, 3.60, 2, 1, @demo_today, NULL),
    ('1A', NULL, NULL, NULL,  90.00, 4.80, 1, 1, @demo_today, NULL),
    -- route override: the Mumbai-Pune pair is priced above the class default
    ('SL', 'CSTM', 'PUNE', 150,  30.00, 1.30, 0, 0, @demo_today, NULL),
    ('3A', 'CSTM', 'PUNE', 150,  60.00, 2.60, 6, 3, @demo_today, NULL),
    ('2A', 'CSTM', 'PUNE', 150,  85.00, 3.90, 3, 1, @demo_today, NULL);

-- ---------------------------------------------------------------------
-- users
--
-- Demo accounts only. password_hash is LEFT NULL on purpose: NULL means
-- "no password set yet" and login must refuse it. This way not a single
-- byte of credential material is committed to source control, and no
-- default password exists for anyone to log in with by accident.
--
-- Set a real password locally with your own hashing code, e.g.
--   UPDATE users SET password_hash = '<bcrypt or argon2 hash>'
--   WHERE username = 'demo_user';
-- ---------------------------------------------------------------------
INSERT INTO users (username, full_name, email, mobile, password_hash, role) VALUES
    ('demo_admin', 'Demo Administrator', 'admin.demo@example.invalid', '9000000001', NULL, 'ADMIN'),
    ('demo_user',  'Demo Passenger',     'user.demo@example.invalid',  '9000000002', NULL, 'CUSTOMER');

-- =====================================================================
-- End of sample_data.sql
--
-- Seeded: 5 classes, 6 stations, 4 trains, 17 stops, 18 coaches,
--         1 fare-rule set (5 class defaults + 3 route overrides),
--         2 demo users, 0 journeys, 0 reservations.
-- =====================================================================