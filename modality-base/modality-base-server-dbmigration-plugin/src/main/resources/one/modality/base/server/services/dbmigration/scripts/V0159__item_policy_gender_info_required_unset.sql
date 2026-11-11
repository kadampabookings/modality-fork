-- V0159: let item_policy.gender_info_required say "unset", so a narrower scope inherits it.
--
-- V0026 gave the family-defaultable flags an "unset" (null) but kept the item-only fields' defaults,
-- reasoning that with no family counterpart "null there would mean something else entirely". It does
-- not: the policy resolver (resolveItemAcrossScopes → fillFromWiderScopes) fills EVERY null field of
-- the narrowest item policy from the next wider scope that sets it. So null already means "take it
-- from the wider scope" — the column simply could not hold one.
--
-- What that cost: NOT NULL DEFAULT false wrote "gender not required" into every item policy created
-- for any other reason. An event-type row added to tune, say, a minimum stay silently overrode the
-- organisation's "gender required", and the booking form stopped asking for it — four such rows on
-- Manjushri's Dormitory and Standard Shared (event types 25, 34, 61). With single-gender beds, a
-- booking without a gender was then refused by the allocation as "sold out".
--
-- This script changes no data: every existing row keeps its explicit value, so behaviour is unchanged
-- until a row is cleared. A stored false cannot be told apart from one the default wrote (V0026's
-- caveat), so which rows to clear is a data decision, made in the policy editor rather than here.
--
-- Readers are null-safe: the Java booking page tests Boolean.TRUE.equals(...), the React policy
-- helpers read `?? false` after resolution, and the allocation never reads this column.

-- One statement, so the table's lock is taken once (both are catalog-only: no rewrite, no scan).
ALTER TABLE item_policy
    ALTER COLUMN gender_info_required DROP NOT NULL,
    ALTER COLUMN gender_info_required DROP DEFAULT;

COMMENT ON COLUMN item_policy.gender_info_required IS
    'NULL = unset: take the value from a wider scope''s item policy, else not required. '
    'An explicit value overrides wider scopes.';
