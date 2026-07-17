-- V0028: minDay on ItemFamilyPolicy, and a wholeEvent flag on both policies.
--
-- Two ways to require a long stay, for the retreats that need one.
--
-- 1. min_day on item_family_policy. It already exists on item_policy, so a retreat needing a
--    29-night minimum had to duplicate every accommodation ItemPolicy at event scope just to set
--    it. Declared once on the family it applies to every accommodation option, and an item that
--    differs (a dormitory at 7 nights) still overrides it — 52 of 133 item policies set min_day
--    today, much of it that duplication. item_policy.min_day is already nullable with no default,
--    so "unset ⇒ ask the family" is expressible there without any change.
--
-- 2. whole_event on both. NOT a shorthand for min_day, because the two are measured against
--    DIFFERENT windows:
--      • min_day counts nights inside the MAIN EVENT period, which getEventDateRange EXTENDS to
--        cover every scheduled teaching. January Retreat 2027 (event 1933) runs 02/01–31/01 but
--        teaches until 28/02, so its main period is 57 nights and min_day=29 is satisfied by any
--        29-night window inside it — arriving on the 4th and leaving on 02/02 passes.
--      • whole_event is measured against the EVENT's own start/end dates: the stay must cover
--        02/01–31/01, with extra nights either side allowed. That is what the retreats actually
--        mean, and min_day cannot express it at all on an extendable event.
--    It is also the value that cannot go stale: min_day=29 is a copy of a derived fact, so shifting
--    the event by a day silently leaves it over- or under-constraining. whole_event re-reads the
--    dates every time.
--
-- Both nullable with NO default (the V0026 lesson: NOT NULL DEFAULT is what makes "unset"
-- unrepresentable and blocks the family fallback). null ⇒ unset ⇒ ask the family, then no
-- constraint. The two constraints are independent and both apply when both resolve, so an item
-- that opts out of its family's whole_event must say so: the dormitory carries whole_event=false
-- AND min_day=7.

ALTER TABLE item_family_policy
    ADD COLUMN IF NOT EXISTS min_day integer;

ALTER TABLE item_family_policy
    ADD COLUMN IF NOT EXISTS whole_event boolean;

ALTER TABLE item_policy
    ADD COLUMN IF NOT EXISTS whole_event boolean;

COMMENT ON COLUMN item_family_policy.min_day IS
    'Minimum nights inside the main event period for this family''s items. NULL = unset = no '
    'minimum. An item''s own min_day overrides it.';

COMMENT ON COLUMN item_family_policy.whole_event IS
    'When true, a booking must cover the event''s own start..end dates (extra nights either side '
    'allowed). Distinct from min_day, which counts nights inside the main event period — a period '
    'that extends to cover scheduled teachings and so can be far longer than the event. NULL = '
    'unset = ask nothing. An item''s own whole_event overrides it.';

COMMENT ON COLUMN item_policy.whole_event IS
    'Per-item override of the family''s whole_event. NULL = unset = take the family''s value.';
