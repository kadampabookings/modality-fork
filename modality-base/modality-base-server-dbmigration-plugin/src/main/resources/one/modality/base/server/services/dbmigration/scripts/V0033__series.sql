-- V0033: Series — shared content + theme for a batch of sibling events.
--
-- GP classes run as term batches: one main Temple class plus ~7 branch classes over the
-- same weeks, all sharing the same public title, description, cover image and colour
-- theme. Today that content is entered (and each term re-entered) on every event, and
-- the theme colours are set per event through the KBS2 DbExplorer.
--
-- A series carries the shared values once; events reference it and their own fields act
-- as overrides. Resolution is field-level, event first (same pattern as the existing
-- event → event_theme fallback, and the policy scope resolution of V0025–V0029):
--
--     event.theme_base_color → event_theme.base_color → series.theme_base_color → default
--     event.label_id         → series.label_id
--     (image: CDN probe events/event-<id> → series/series-<id> → default picture;
--      no image column — cover images are a Bunny naming convention, not a DB field.)
--
-- The theme columns live directly on series (not via an event_theme FK): in practice
-- each series gets a one-off palette derived from its cover image, so routing it
-- through event_theme would fill that table with single-use rows.
--
-- NOTE (ownership, see db_migration precedent): the migration engine creates this table
-- as the app role. Run `ALTER TABLE public.series OWNER TO kbs;` (as an owner-capable
-- role) after deploy on each DB, or pg_dump/backups will fail on the new table.

-- ── The series table ────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS public.series (
    id                             serial PRIMARY KEY,
    -- Internal admin name ("GP Classes Sep–Oct 2026"); public title is label_id.
    name                           character varying(256) NOT NULL,
    -- Series are org-scoped, like the labels they reference.
    organization_id                integer NOT NULL REFERENCES public.organization(id),
    -- Public-facing shared content: title + short/long descriptions (multi-language
    -- Label rows, same fields the event carries).
    label_id                       integer REFERENCES public.label(id),
    short_description_label_id     integer REFERENCES public.label(id),
    long_description_label_id      integer REFERENCES public.label(id),
    -- Shared booking-form palette (types mirror the event columns exactly).
    theme_base_color               character varying(16),
    theme_accent_color             character varying(16),
    theme_border_color             character varying(16),
    theme_surface_color            text,
    -- Nullable, no default: NULL = unset (fall through to the CSS default), the
    -- shape event.theme_strong_background is relaxed to below.
    theme_strong_background        boolean
);

-- ── Event membership ────────────────────────────────────────────────────

ALTER TABLE event
    ADD COLUMN IF NOT EXISTS series_id integer REFERENCES public.series(id);

-- Backoffice groups events under their series ("events of series X").
CREATE INDEX IF NOT EXISTS event_series_idx ON public.event (series_id);

-- ── Let event.theme_strong_background express "unset" ──────────────────
--
-- The column is NOT NULL DEFAULT false, so every event carries an explicit value and
-- a series (or event_theme) strong-background could never be consulted — the same
-- DEFAULT trap V0026 documented on item_policy. Relax it so NULL = "defer".

ALTER TABLE event ALTER COLUMN theme_strong_background DROP NOT NULL;
ALTER TABLE event ALTER COLUMN theme_strong_background DROP DEFAULT;

-- Data conversion, deliberately narrow (V0026 kept all data; here a subset is provably
-- safe): for events with NO event_theme, false and NULL resolve identically today
-- (nothing to defer to, series_id is NULL for every row at this point), so the
-- default-written false can be erased to make those events series-ready. Events WITH
-- a theme keep their explicit value — flipping them to NULL would suddenly let
-- event_theme.strong_background=true through and change live pages.

UPDATE event
   SET theme_strong_background = NULL
 WHERE theme_strong_background = false
   AND theme_id IS NULL;
