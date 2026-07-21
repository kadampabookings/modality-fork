-- V0034: person.gender_changed_date — attention marker for single-sex dormitory allocation.
--
-- When a person's recorded gender is changed (person.male flipped), the back office sets this to
-- the date of the change. Dormitories are single-sex, so allocation staff want a discreet warning
-- on future bookings of a person whose gender was changed, to double-check the placement.
--
-- A single date is deliberately the whole design:
-- - warning condition is simply "not null";
-- - the previous value is derivable (gender is the boolean person.male, so previous = opposite);
-- - the date itself tells staff how recent the change is;
-- - a second change overwrites the date, which is fine — the warning fires either way.
--
-- Deliberately NOT copied onto document like the person_* snapshot fields: this is a live
-- attention marker, not a booking-time fact. A booking made BEFORE the change is exactly the one
-- that most needs the warning, and a snapshot taken at booking time would be null there. Allocation
-- UIs must read it through document.person_id. (KBS2's booking form would also never populate a
-- copy, making null ambiguous on KBS2-created bookings.)
--
-- Kept minimal on purpose (a date, no narrative): it effectively records gender-reassignment
-- status, which is sensitive personal data — back-office allocation context only.

ALTER TABLE person
    ADD COLUMN IF NOT EXISTS gender_changed_date date;

COMMENT ON COLUMN person.gender_changed_date IS
    'Date the person''s recorded gender (person.male) was last changed, null if never. Non-null '
    'triggers a back-office warning when allocating this person to a single-sex dormitory. Live '
    'attention marker — deliberately not snapshotted onto document; read via document.person_id.';
