-- Two columns on event_selection, both about how a selection is OFFERED rather
-- than what it covers.
--
-- fixed — the selection is a single-session PRODUCT, bought whole: it books
-- exactly the ScheduledItems its parts anchor and carries no stay. Manjushri's
-- Christmas lunch is the first: one meal, one sitting, no room, no dates and no
-- meal choice, so the booking form asks only for a dietary preference. Without
-- the flag the front office had to INFER which selections were products from the
-- shape of their date ranges, and that inference was wrong twice — it needed an
-- option-count cap, and a one-day product breaks its "all start together" test.
-- The organizer knows which selections are products; this records it.
--
-- ord — the order the selections are offered in. Until now the booking form
-- listed them by id, i.e. the order they happened to be created in, so putting
-- "Whole event" above "Christmas Eve & day" meant renumbering rows. Null keeps
-- today's behaviour: unordered selections follow the ordered ones, by id.
ALTER TABLE event_selection
    ADD COLUMN IF NOT EXISTS fixed boolean DEFAULT false NOT NULL,
    ADD COLUMN IF NOT EXISTS ord integer;

COMMENT ON COLUMN event_selection.fixed IS
    'True when this selection is a fixed single-session product (books exactly the '
    'sessions its parts anchor: no stay, no accommodation, no meal choice).';

COMMENT ON COLUMN event_selection.ord IS
    'Display order on the booking form. Null sorts after the ordered rows, by id.';

-- The matching FIELD rows (2222 fixed, 2223 ord on class 146) were added to the
-- KBS2 HSQL metadata and to DomainModelSnapshot.json, the model the server loads.
-- Add them to any other copy of that metadata, or the next model re-import drops
-- them and DQL stops resolving 'fixed' and 'ord'.
