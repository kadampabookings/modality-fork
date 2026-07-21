-- V0035: trigger that stamps person.gender_changed_date whenever person.male flips.
--
-- V0034 added the column; this makes it self-maintaining. The marker is a safety net for
-- single-sex dormitory allocation, so it must be set no matter which door the gender change
-- comes through: the KBS3 back office, the customer's own front-office profile edit, KBS2
-- (which does not know the column exists), or ad-hoc SQL. Only a database trigger covers all
-- of those; client-side logic would cover exactly one.
--
-- Two guards:
-- 1. Only a real flip of a KNOWN gender counts: OLD.male and NEW.male both non-null and
--    different. Setting gender for the first time on a profile that had none is initial data
--    entry, not a change - no marker.
-- 2. Never clobber an UPDATE that explicitly sets (or clears) gender_changed_date itself.
--    This keeps the column manually settable, and above all CLEARABLE: staff reviewing a
--    typo correction (wrong gender entered at signup, fixed later - indistinguishable from a
--    real change at this level) can clear the marker from the back office and it sticks.
--
-- The stamp is CURRENT_DATE (the date the recorded gender changed), matching the column's
-- documented meaning. A second change simply overwrites - the warning fires either way.

CREATE OR REPLACE FUNCTION public.trigger_person_gender_changed() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
	IF OLD.male IS NOT NULL AND NEW.male IS NOT NULL AND OLD.male <> NEW.male
	   AND NEW.gender_changed_date IS NOT DISTINCT FROM OLD.gender_changed_date THEN
		NEW.gender_changed_date := CURRENT_DATE;
	END IF;
	RETURN NEW;
END $$;

DROP TRIGGER IF EXISTS person_gender_changed ON person;
CREATE TRIGGER person_gender_changed
    BEFORE UPDATE OF male ON person
    FOR EACH ROW
    EXECUTE FUNCTION public.trigger_person_gender_changed();
