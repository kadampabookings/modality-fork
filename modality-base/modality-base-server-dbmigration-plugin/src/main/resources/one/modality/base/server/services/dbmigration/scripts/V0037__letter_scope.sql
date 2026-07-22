-- V0037: letter scope columns (expand-only — NO behavior change).
--
-- Letters are bound to a single event (letter.event_id), so duplicating an event
-- duplicates its letters: both copy_event() overloads clone every letter of the source
-- event, and GP-class term rollovers therefore grow an ever-larger pile of identical
-- letters that drift apart. This migration widens Letter so ONE letter can live at a
-- wider scope and be resolved per booking context, narrowest scope wins:
--
--     event → (site, eventType) → eventType → (site, organization) → organization
--       0            1                2               3                    4
--
-- Same scope-first philosophy as the policy resolution arc (V0025–V0029), inline
-- columns instead of the policies' policy_scope table (letters already carry
-- organization_id and event_id inline; inline keeps every plpgsql consumer and KBS2's
-- generated actor SQL join-free). Full design + consumer inventory:
-- docs/letter-scope-plan.md; prod recon: scripts/letter-scope-phase0-recon-prod.txt.
--
-- organization_id already exists NOT NULL and stays the ownership boundary (every
-- scope level is org-owned). event_id stays as the narrowest scope. Existing rows are
-- untouched: they keep event_id set and the new columns NULL, which the rank function
-- below ranks exactly as today's lookups. NOTHING reads the new columns yet — the
-- consumers (booking triggers, KBS2 AutomaticLettersActor, BO pickers) convert in
-- later migrations/deploys, provably identical while only event-scoped letters exist.

-- ── 1. New nullable scope FKs ───────────────────────────────────────────

ALTER TABLE letter
    ADD COLUMN IF NOT EXISTS site_id       integer REFERENCES public.site(id),
    ADD COLUMN IF NOT EXISTS event_type_id integer REFERENCES public.event_type(id);

COMMENT ON COLUMN letter.site_id IS
    'Scope: venue/site the letter applies to (matched against event.venue_id). NULL = not site-scoped.';
COMMENT ON COLUMN letter.event_type_id IS
    'Scope: event type the letter applies to (matched against event.type_id). NULL = not type-scoped.';

-- ── 2. Canonical-scope guard ────────────────────────────────────────────
--
-- An event-scoped letter must not also carry wider scope columns: event is the
-- narrowest scope, so anything else on the row would be dead weight at best and
-- ambiguous at worst. (No IF NOT EXISTS for ADD CONSTRAINT — migration scripts run
-- exactly once, tracked in db_migration.)

ALTER TABLE letter ADD CONSTRAINT letter_scope_canonical_chk
    CHECK (event_id IS NULL OR (site_id IS NULL AND event_type_id IS NULL));

-- ── 3. Supporting indexes ───────────────────────────────────────────────
--
-- Wide-scoped letters will be few (currently zero); partial indexes keep them cheap.
-- letter_event_id_idx already exists for the event branch. The org index is full —
-- it also serves the org-scoped fallback branches already live in the cart/order and
-- password triggers, which today run unindexed (letter is small, so this is hygiene).

CREATE INDEX IF NOT EXISTS letter_event_type_id_idx   ON letter (event_type_id) WHERE event_type_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS letter_site_id_idx         ON letter (site_id)       WHERE site_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS letter_organization_id_idx ON letter (organization_id);

-- ── 4. Override-vs-additive allowlist on the TYPE ───────────────────────
--
-- Two semantic classes (docs/letter-scope-plan.md, rule 2), EXPLICIT per type, never
-- inferred (the FAMILY_DEFAULTABLE_FIELDS precedent):
--   override (true): a narrower-scoped letter of this type REPLACES wider-scoped ones,
--     per attendance-mode slot (applicable_to_in_person / applicable_to_online) —
--     the automatic/special types: cart, order, no-deposit ladder, cancellation,
--     confirmation, send-password, no-shuttle-time, audio, magic-link, terms.
--   additive (false): letters of this type UNION across scopes — the general-usage
--     "Event letter" (event flag) and "Newsletter" (news flag). Prod recon confirms
--     org 151 already stacks org-scoped + event-scoped Newsletters.
-- The flags-based UPDATE flips exactly types 3–19 on prod data (every type carries
-- exactly one flag; only news and event stay additive) — verified 2026-07-22,
-- scripts/letter-scope-phase0-recon-prod.txt §1.

ALTER TABLE letter_type
    ADD COLUMN IF NOT EXISTS scope_override boolean NOT NULL DEFAULT false;

COMMENT ON COLUMN letter_type.scope_override IS
    'true = a narrower-scoped letter of this type REPLACES wider-scoped ones (per attendance-mode slot). '
    'false = letters of this type are additive across scopes (Event letter, Newsletter).';

UPDATE letter_type SET scope_override = true
 WHERE cart OR "order" OR no_deposit1 OR no_deposit2 OR no_deposit3 OR cancellation
    OR confirmation OR send_password OR no_shuttle_time OR audio OR magic_link OR terms;

-- ── 5. Scope-rank helper ────────────────────────────────────────────────
--
-- The ONE place the scope ordering is spelled out; every consumer conversion ranks
-- with this function (smaller = narrower = wins; NULL = letter not applicable in this
-- context). Org matching (l.organization_id = context org) deliberately stays in the
-- CALLERS: the password-reset resolver layers its own NKT-global (org 1) fallback on
-- top and must not be constrained here.

CREATE OR REPLACE FUNCTION letter_scope_rank(
    l_event_id int, l_site_id int, l_event_type_id int,
    d_event_id int, d_venue_id int, d_event_type_id int
) RETURNS int LANGUAGE sql IMMUTABLE AS $$
    SELECT CASE
        WHEN l_event_id IS NOT NULL
            THEN CASE WHEN l_event_id = d_event_id THEN 0 END
        WHEN l_site_id IS NOT NULL AND l_event_type_id IS NOT NULL
            THEN CASE WHEN l_site_id = d_venue_id AND l_event_type_id = d_event_type_id THEN 1 END
        WHEN l_event_type_id IS NOT NULL
            THEN CASE WHEN l_event_type_id = d_event_type_id THEN 2 END
        WHEN l_site_id IS NOT NULL
            THEN CASE WHEN l_site_id = d_venue_id THEN 3 END
        ELSE 4
    END
$$;

COMMENT ON FUNCTION letter_scope_rank(int, int, int, int, int, int) IS
    'Letter scope resolution rank for a document/event context (docs/letter-scope-plan.md). '
    'Args: letter''s event_id/site_id/event_type_id, then the context''s event id, venue (site) id, '
    'event type id. Returns 0 (event) … 4 (organization); smaller = narrower = wins; '
    'NULL = letter not applicable in this context. Callers must still match organization_id.';
