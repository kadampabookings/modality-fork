-- V0061: mark a person's address / Kadampa centre as no longer trustworthy, and
-- record when their details were last confirmed.
--
-- Most bookers never visit /members, so the booking flow is the only reliable
-- opportunity to keep the customer database current (see the aggregate repo's
-- docs/member-details-in-booking-plan.md, phase 2). Three nullable dates on person:
--
--   address_deprecated_date       staff assert the postal address is now wrong
--   organization_deprecated_date  staff assert the Kadampa centre is now wrong
--   details_confirmed_date        the person last confirmed their details
--   details_edited_date           the details actually CHANGED (trigger-stamped)
--
-- Dates rather than booleans, mirroring the existing genderChangedDate marker: they
-- record WHEN the assertion was made, read naturally in the UI, and an explicit null
-- write clears them.
--
-- How they are meant to be used (front office, phase 3):
--   * deprecated (non-null)  => the booking flow REQUIRES a fresh value before
--                               continuing, and clears the marker once supplied;
--   * merely missing         => prompted but skippable. Neither field can block on
--                               absence: 33% of account owners have no centre at all
--                               (staging, 2026-08-09), and the address is statistical
--                               (city geolocation) rather than operational;
--   * details_confirmed_date => lets the review be SKIPPED for someone who confirmed
--                               recently, instead of re-interviewing them every time.
--
-- details_edited_date is DIAGNOSTIC, not decision-driving: the skip logic keys only on
-- details_confirmed_date. Because editing implies confirming, confirmed >= edited
-- always, and the GAP between them is the point — it is the only way to see the most
-- likely failure of this feature, people confirming without ever changing anything
-- while their details are in fact wrong. Nothing else records it: sys_log is an
-- UNLOGGED change-notification queue with no timestamp, drained continuously.
--
-- Why an empty centre still matters enough to prompt: the security team asks each
-- centre to confirm the bookings affiliated to it (setting document.person_unknown /
-- person_known / person_verified), so a booking with no centre falls outside that
-- check entirely.
--
-- No backfill: every column starts null, meaning "nothing asserted, never confirmed",
-- which is the correct reading for existing rows.

ALTER TABLE public.person
    ADD COLUMN IF NOT EXISTS address_deprecated_date      date,
    ADD COLUMN IF NOT EXISTS organization_deprecated_date date,
    ADD COLUMN IF NOT EXISTS details_confirmed_date       date,
    ADD COLUMN IF NOT EXISTS details_edited_date          date;

-- Stamp details_edited_date whenever a tracked detail actually changes. A TRIGGER
-- rather than an application write, mirroring person_gender_changed (V0035): the
-- booking review, the profile page, the back-office customer tab, imports and ad-hoc
-- scripts all edit these columns, and only the database sees every one of them.
--
-- `UPDATE OF` fires when a column is in the SET list, not necessarily changed, so the
-- body compares values with IS DISTINCT FROM (null-safe). The final guard lets an
-- explicit write to details_edited_date stick, so a correction or a backfill is not
-- overwritten by the trigger — same escape hatch as the gender marker.
CREATE OR REPLACE FUNCTION public.trigger_person_details_edited() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF (NEW.street          IS DISTINCT FROM OLD.street
     OR NEW.post_code       IS DISTINCT FROM OLD.post_code
     OR NEW.city_name       IS DISTINCT FROM OLD.city_name
     OR NEW.country_id      IS DISTINCT FROM OLD.country_id
     OR NEW.organization_id IS DISTINCT FROM OLD.organization_id
     OR NEW.phone           IS DISTINCT FROM OLD.phone)
     AND NEW.details_edited_date IS NOT DISTINCT FROM OLD.details_edited_date THEN
        NEW.details_edited_date := CURRENT_DATE;
    END IF;
    RETURN NEW;
END $$;

DROP TRIGGER IF EXISTS person_details_edited ON public.person;
CREATE TRIGGER person_details_edited
    BEFORE UPDATE OF street, post_code, city_name, country_id, organization_id, phone
    ON public.person
    FOR EACH ROW
    EXECUTE FUNCTION public.trigger_person_details_edited();

COMMENT ON COLUMN public.person.address_deprecated_date IS
    'Set by staff when the postal address is known to be out of date; the front-office booking flow then requires a fresh address. Null = nothing asserted.';
COMMENT ON COLUMN public.person.organization_deprecated_date IS
    'Set by staff when the Kadampa centre is known to be out of date (e.g. the person moved out); the front-office booking flow then requires a fresh centre. Null = nothing asserted.';
COMMENT ON COLUMN public.person.details_confirmed_date IS
    'When the person last confirmed their personal details (booking-flow review or profile save). Null = never confirmed.';
