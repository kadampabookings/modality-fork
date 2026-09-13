-- Which accommodation a sharing option pairs with.
--
-- A "sharing" item (item.share_mate) means "I am joining a room/tent/campervan
-- somebody I know has already booked". A complex event offers one per shareable
-- accommodation type — Sharing a Twin Room Ensuite, Camping - Sharing a
-- pre-erected tent, Camping - Sharing a campervan space — and until now that
-- pairing existed ONLY in the item names and card descriptions. Nothing in the
-- schema said which sharing option belongs to which room, so no code could tell
-- "nobody has claimed the second bed" from "somebody claimed it with the wrong
-- sharing item", and the sharing cards had to be forced sold out by hand when
-- the beds ran out.
--
-- A set rather than a single FK, because the pairing genuinely is one: the child
-- option "Sharing parent tent or campervan" covers two accommodations, and a
-- generic "Sharing a room" is meant to cover every multi-bed room. Four slots is
-- ample; the same shape as item_family_policy.event_phase_coverage1..4.
--
-- All four NULL keeps today's behaviour and means "any shareable accommodation":
-- nothing is narrowed, nothing is validated, exactly as before this migration.
-- The columns live on item_policy rather than item so that the pairing is
-- scoped: an event type or a single event can pair the same generic sharing
-- item with different rooms, because those scopes are ranked narrower than the
-- organisation's (see policy_scope and PolicyAggregate's scope levels).
--
-- Site is NOT one of those. Scope resolution deliberately does not rank site
-- against the others -- it says which venue an item belongs to, not how narrowly
-- the policy applies -- so an org-wide row and an org+site row tie, and a tie
-- merges the two sets rather than one overriding the other. A per-site pairing
-- that must REPLACE the organisation's needs site ranking first, which is a
-- change to every field's resolution and not something this migration decides.
ALTER TABLE item_policy
    ADD COLUMN IF NOT EXISTS paired_item1_id integer REFERENCES item (id),
    ADD COLUMN IF NOT EXISTS paired_item2_id integer REFERENCES item (id),
    ADD COLUMN IF NOT EXISTS paired_item3_id integer REFERENCES item (id),
    ADD COLUMN IF NOT EXISTS paired_item4_id integer REFERENCES item (id);

COMMENT ON COLUMN item_policy.paired_item1_id IS
    'Accommodation this sharing item pairs with. The four paired_item columns are ONE value: '
    'a scope that sets any of them owns all four. All NULL means "any shareable accommodation".';

-- Only sharing items pair with anything; a room does not point back at its
-- sharer. Partial index so the lookup ("which sharing options pair with this
-- room?") stays cheap without carrying a row per policy.
CREATE INDEX IF NOT EXISTS item_policy_paired_item1_idx ON item_policy (paired_item1_id)
    WHERE paired_item1_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS item_policy_paired_item2_idx ON item_policy (paired_item2_id)
    WHERE paired_item2_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS item_policy_paired_item3_idx ON item_policy (paired_item3_id)
    WHERE paired_item3_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS item_policy_paired_item4_idx ON item_policy (paired_item4_id)
    WHERE paired_item4_id IS NOT NULL;

-- The matching FIELD rows (2224-2227, pairedItem1..4 on class 143 ItemPolicy,
-- foreign class 35 Item) were added to DomainModelSnapshot.json, the model the
-- server loads. Add them to the KBS2 HSQL metadata too, or the next model
-- re-import drops them and DQL stops resolving 'pairedItem1'..'pairedItem4'.
