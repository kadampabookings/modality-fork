-- V0029: early/late accommodation flags on ItemFamilyPolicy, and made unsettable on ItemPolicy.
--
-- Declared on the family, "no early arrival" is stated once for a retreat instead of on every room.
-- Same story as V0028's min_day: the January retreat used to carry these on a duplicated ItemPolicy
-- per room at event scope, which is exactly the duplication the wider scopes exist to remove.
--
-- Both item columns were NOT NULL DEFAULT false — the V0026 trap again, and the reason this needs a
-- migration rather than just a new column: every existing row carries an explicit value, so the
-- family would never be consulted. Dropping NOT NULL and the DEFAULT makes "unset" expressible.
-- The reader already treats null as "allowed" (`lookupBoolean(...) ?? true`), so the terminal
-- default needs no code change and no existing row changes behaviour.
--
-- Note the item columns' DEFAULT was false while the code's default for an absent value is true.
-- That mismatch is why the columns had to be explicitly set on every row to mean "allowed", and it
-- disappears with the default: an unset column now reads as allowed, matching the code.

ALTER TABLE item_family_policy
    ADD COLUMN IF NOT EXISTS early_accommodation_allowed boolean;

ALTER TABLE item_family_policy
    ADD COLUMN IF NOT EXISTS late_accommodation_allowed boolean;

ALTER TABLE item_policy ALTER COLUMN early_accommodation_allowed DROP NOT NULL;
ALTER TABLE item_policy ALTER COLUMN early_accommodation_allowed DROP DEFAULT;

ALTER TABLE item_policy ALTER COLUMN late_accommodation_allowed DROP NOT NULL;
ALTER TABLE item_policy ALTER COLUMN late_accommodation_allowed DROP DEFAULT;

COMMENT ON COLUMN item_family_policy.early_accommodation_allowed IS
    'Whether this family''s items may be booked before the event starts. NULL = unset = ask a wider '
    'scope, then allowed.';

COMMENT ON COLUMN item_family_policy.late_accommodation_allowed IS
    'Whether this family''s items may be booked after the event ends. NULL = unset = ask a wider '
    'scope, then allowed.';

COMMENT ON COLUMN item_policy.early_accommodation_allowed IS
    'NULL = unset: take the value from the narrowest scope that sets one, item before family within '
    'a scope. Then allowed.';

COMMENT ON COLUMN item_policy.late_accommodation_allowed IS
    'NULL = unset: take the value from the narrowest scope that sets one, item before family within '
    'a scope. Then allowed.';
