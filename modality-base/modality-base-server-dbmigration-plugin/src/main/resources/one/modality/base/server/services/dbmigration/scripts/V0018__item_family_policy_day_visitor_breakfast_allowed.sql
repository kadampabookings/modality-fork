-- V0018: add item_family_policy.day_visitor_breakfast_allowed.
--
-- Companion flag to day_visitor_dinner_allowed, read on the MEALS
-- ItemFamilyPolicy by the booking form: whether day visitors (bookers without
-- accommodation) may book breakfast. The price algorithm already charges a
-- day-visitor breakfast (no free accommodation-night credit applies — see
-- docs/breakfast-pricing.md in the aggregate repo); this flag only opens the
-- front-office UI gate, which was previously hardcoded to "accommodation only".
--
-- Default FALSE — the opposite of the dinner flag's default — so every existing
-- event keeps its current behaviour (day visitors cannot book breakfast) until
-- an organization explicitly opts in on its MEALS policy.

ALTER TABLE item_family_policy
    ADD COLUMN IF NOT EXISTS day_visitor_breakfast_allowed boolean NOT NULL DEFAULT false;
