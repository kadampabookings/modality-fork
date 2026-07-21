-- V0036: let a series adopt a predefined theme (series.theme_id → event_theme).
--
-- V0033 gave series its own five colour columns for the common case (a one-off
-- palette derived from the term's cover image), but a series had no way to use
-- one of the reusable named palettes from event_theme. This adds the same FK
-- the event already has, extending the booking-form colour resolution to:
--
--     event.theme_xxx → event_theme (event's) → series.theme_xxx
--       → event_theme (series') → CSS default
--
-- i.e. within each level the explicit columns override the linked theme, and
-- the event level as a whole overrides the series level — unchanged semantics,
-- one extra fallback step.

ALTER TABLE series
    ADD COLUMN IF NOT EXISTS theme_id integer REFERENCES public.event_theme(id);
