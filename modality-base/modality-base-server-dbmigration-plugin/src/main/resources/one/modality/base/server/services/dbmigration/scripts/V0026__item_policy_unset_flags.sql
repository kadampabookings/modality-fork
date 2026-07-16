-- V0026: let item_policy express "unset" for the flags ItemFamilyPolicy also declares,
-- so a family can supply the default and an item need only state what it overrides.
--
-- Five fields are declared on BOTH ItemPolicy and ItemFamilyPolicy — applicable_to_in_person,
-- applicable_to_online, child_allowed, young_adult_allowed, adult_allowed — but the item's value
-- was the only one ever read, and there was no way to say "I don't care, ask the family".
--
-- The obstacle was NOT nullability, which is the surprising part: child_allowed & co were already
-- nullable. It was the column DEFAULT. Compare, before this script:
--
--   item_policy.child_allowed         nullable, DEFAULT true  →   0 of 133 rows null
--   item_family_policy.child_allowed  nullable, no default    →  17 of  17 rows null
--
-- Same intent, opposite outcome, one word apart: DEFAULT true writes an explicit value on every
-- insert, so "unset" was never representable and the family could never be consulted. The family
-- column, having no default, works exactly as designed. This script gives the item columns the
-- same shape.
--
-- Deliberately NOT touched:
--   • item_family_policy.applicable_to_* stay NOT NULL DEFAULT true. They are the end of the
--     chain, so "unset" there would only mean "fall through to the code default (applicable)",
--     which is what an explicit true already means.
--   • the item-only fields (min_day, min_occupancy, force_sold_out, "default", gender_info_required)
--     keep their defaults — they have no family counterpart to inherit from, so null there would
--     mean something else entirely.
--
-- This script changes no data and no behaviour on its own: every existing row keeps its explicit
-- value, so the chain still stops at the item. It only makes "unset" expressible from now on.
-- Activating it for existing rows is a separate data decision, because a default-written `true`
-- cannot be told apart from an admin who deliberately ticked the box.

ALTER TABLE item_policy ALTER COLUMN child_allowed DROP DEFAULT;
ALTER TABLE item_policy ALTER COLUMN young_adult_allowed DROP DEFAULT;
ALTER TABLE item_policy ALTER COLUMN adult_allowed DROP DEFAULT;

ALTER TABLE item_policy ALTER COLUMN applicable_to_in_person DROP NOT NULL;
ALTER TABLE item_policy ALTER COLUMN applicable_to_in_person DROP DEFAULT;

ALTER TABLE item_policy ALTER COLUMN applicable_to_online DROP NOT NULL;
ALTER TABLE item_policy ALTER COLUMN applicable_to_online DROP DEFAULT;

COMMENT ON COLUMN item_policy.applicable_to_in_person IS
    'NULL = unset: take the value from the item''s ItemFamilyPolicy, else applicable. '
    'An explicit value overrides the family.';

COMMENT ON COLUMN item_policy.applicable_to_online IS
    'NULL = unset: take the value from the item''s ItemFamilyPolicy, else applicable. '
    'An explicit value overrides the family.';

COMMENT ON COLUMN item_policy.child_allowed IS
    'NULL = unset: take the value from the item''s ItemFamilyPolicy, else the family''s hardcoded '
    'age default, else allowed. An explicit value overrides the family.';
