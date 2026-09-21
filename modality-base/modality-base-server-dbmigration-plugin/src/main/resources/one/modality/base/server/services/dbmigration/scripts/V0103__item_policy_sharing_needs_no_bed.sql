-- A sharing option whose sharer brings the space: no bed to count, so none to run out.
--
-- `item.share_mate` means "I am joining something somebody I know has already booked", and step 6
-- of the room-mate arc derives such an option's availability from the beds still free in this
-- event's booked rooms of the types it pairs with. That holds for a twin room or a pre-erected
-- tent, where the event allocated a room with more than one bed in it.
--
-- It cannot hold for camping. A family books a pitch -- Fall Festival 2026's "Camping" and
-- "Campervan space" both carry capacity 1 -- and the child sleeping in the parent's own tent or
-- campervan takes no bed the event ever counted. A capacity-1 item has no second bed by
-- construction, so the derived figure is zero whatever the option is paired with, and the
-- children-only "Sharing parent tent or campervan" was sold out permanently, while a freed bed in
-- an unrelated twin room would have offered it for the wrong reason.
--
-- This column says so: the option's places are not drawn from anyone's room, so the bed
-- arithmetic does not apply and nothing sells it out on a bed count. Age rules and force_sold_out
-- still apply, and so does the sharer still having to be linked to whoever they are joining.
--
-- On item_policy rather than item, like the paired_item columns and for the same reason: whether
-- the event counts beds inside a thing is a property of how THIS event sells it. One venue's
-- campervan is the guest's own vehicle; another's could be an event-owned 4-berth van whose
-- second bed genuinely is on offer.
--
-- NULLABLE with no default, like child_allowed and the other inherited flags, NOT `NOT NULL
-- DEFAULT false` like force_sold_out. Scope resolution reads null as "inherit": a non-null value
-- means this scope OWNS the answer. A NOT NULL column is set on every row, so the narrowest scope
-- would always win, and an event row added for some unrelated reason (a title label, say) would
-- silently override an organisation's `true` back to false.
ALTER TABLE item_policy
    ADD COLUMN IF NOT EXISTS sharing_needs_no_bed boolean;

COMMENT ON COLUMN item_policy.sharing_needs_no_bed IS
    'Sharing option whose sharer joins a space the event does not count (a family tent, a campervan): '
    'its availability is not derived from free beds. Only meaningful when item.share_mate is true. '
    'NULL means inherit from a wider scope, then no.';
