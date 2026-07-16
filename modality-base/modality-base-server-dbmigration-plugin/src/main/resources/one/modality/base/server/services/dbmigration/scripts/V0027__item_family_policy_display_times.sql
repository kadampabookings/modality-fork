-- V0027: item_family_policy.display_times — let an organization hide a family's session times.
--
-- Driven by a real case: NKT festivals are hosted at another centre's venue, and meals belong to
-- the VENUE, not the event — meal ScheduledItems carry event=null and are picked up by any event
-- whose venue is that site, for its dates (see ServerPolicyServiceProvider query 2). So a festival
-- inherits the host centre's normal breakfast/lunch/dinner times, which don't describe how the
-- festival actually serves them. The host's own events should keep showing them.
--
-- Named `display_times`, not `display_meals_times`: the row already carries its item_family, so a
-- "meals" prefix would restate the row's own key and read as nonsense on a teaching row. Teachings,
-- ceremonies and meals all have session times, so this is reusable as-is. (The meals-specific names
-- in this table — day_visitor_breakfast_allowed, ask_diet_for_breakfast — earn their prefix by
-- encoding a distinction WITHIN the family, breakfast vs dinner, that the column has to carry.
-- This one has none.)
--
-- Nullable with NO default, deliberately. NOT NULL DEFAULT true is exactly what made the five
-- dual-declared flags unable to express "unset" and forced V0026 to undo it one migration ago. Here
-- it costs nothing to get right: readers treat null as "show" (`!== false`), so behaviour is
-- identical to DEFAULT true, but "unset" stays representable — which is what any future
-- fall-back-to-a-wider-scope resolution would need.
--
-- Scoping note for whoever configures this: an ORG-scoped row will not do what you expect. Policies
-- match on `organization = event.organization OR organization = event.venue.organization`, so for a
-- hosted festival the host centre's general policy matches too — at the same scope level — and the
-- tie is broken by primary key. Scope it at eventType (which outranks any general policy), and
-- remember an existing event-scoped family policy still wins wholesale over that.

ALTER TABLE item_family_policy
    ADD COLUMN IF NOT EXISTS display_times boolean;

COMMENT ON COLUMN item_family_policy.display_times IS
    'Whether to show this family''s session times on the booking form (meal serving times, etc). '
    'NULL = unset = show. Only an explicit false hides them.';
