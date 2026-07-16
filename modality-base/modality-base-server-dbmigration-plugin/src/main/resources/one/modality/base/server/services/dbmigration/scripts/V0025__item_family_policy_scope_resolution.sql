-- V0025: give ItemFamilyPolicy the two flags that make cross-scope policy
-- resolution expressible.
--
-- Policies hang off a PolicyScope that may be general (organization, optionally
-- narrowed to a site), eventType-scoped or event-scoped. The server's scope
-- predicate (ServerPolicyServiceProvider queries 7 & 8) is null-permissive, so
-- every matching scope's rows come back unioned together, and nothing until now
-- could say what a narrower scope *means*. Two questions were unanswerable:
--
--   1. "For this event type I offer a different accommodation list" — a set
--      question. ItemPolicy is per-item, so no single row can express that the
--      wider list should stop applying. replaces_wider_scopes answers it at the
--      family level, which is the granularity the question is actually asked at.
--
--   2. "This advanced retreat shouldn't show the discovery options at all" — a
--      visibility question. It was tempting to abuse applicable_to_in_person /
--      applicable_to_online for this, but those mean "doesn't apply to this
--      attendance mode", a different axis; setting both false reads as
--      "applies to nothing" and is indistinguishable from a misconfiguration.
--      disabled says what is meant.
--
-- Both default FALSE = today's behaviour (union across scopes, nothing hidden),
-- so no existing event changes until an admin opts in.
--
-- Note on why these live on item_family_policy and not item_policy: resolution
-- of a *single* row (which policy governs item A / family F) is always
-- "narrowest scope wins" and needs no flag. Only the *set* question needs one,
-- and a set belongs to a family. Field-level inheritance across scopes is
-- deliberately not attempted: applicable_to_in_person & co are NOT NULL DEFAULT
-- true, so "inherit from wider scope" is indistinguishable from "explicitly
-- true" and the narrowest row must win wholesale.

ALTER TABLE item_family_policy
    ADD COLUMN IF NOT EXISTS disabled boolean NOT NULL DEFAULT false;

ALTER TABLE item_family_policy
    ADD COLUMN IF NOT EXISTS replaces_wider_scopes boolean NOT NULL DEFAULT false;

COMMENT ON COLUMN item_family_policy.disabled IS
    'When true at the winning (narrowest) scope for this family, the whole family '
    'is withdrawn for the event: its item policies are dropped and no ItemPolicy '
    'flag can reintroduce them.';

COMMENT ON COLUMN item_family_policy.replaces_wider_scopes IS
    'When true, this scope''s ItemPolicy set for the family replaces the set '
    'declared at any wider scope, instead of adding to it. False (default) = the '
    'sets merge, with per-item attributes still overridden by the narrowest scope.';
