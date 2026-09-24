-- Let the back office ask WHETHER a Bunny API key is set, without being handed the key.
--
-- The Organizations page loads `bunny_api_key` for every organisation in its grid. It does not
-- display it and the form never echoes it back into the input -- the value's only job is to decide
-- between a masked "••••••••••••••" placeholder and an empty one. So three live third-party API
-- keys (prod, 2026-09-24) are shipped to the browser of every back-office user who opens that page,
-- to render a boolean.
--
-- Denying the column outright would break the page, and denying it "except for an equality test"
-- -- the rule the three capability tokens use -- is worse than useless here: nothing looks an
-- organisation up BY its API key, and a testable key column is a per-character oracle on a secret.
--
-- Hence a generated column. The grid reads `bunny_api_key_set`, no KBS3 client reads the source
-- column any more, and the two cannot drift because Postgres computes one from the other. The
-- alternative -- a domain-model expression field `bunnyApiKey != null` -- would NOT work: it
-- compiles to SQL that still names `bunny_api_key`, which is exactly what the read fence refuses.
-- Being STORED rather than VIRTUAL also means the grid's read costs no more than reading the key
-- did.
--
-- This only removes the REASON to read the key. The deny declaration itself
-- (ClientReadSecrets) lands separately, once cached back-office bundles asking for the old field
-- have aged out -- a server rule that refuses what a stale client still sends breaks the page for
-- whoever is holding that bundle. That sequencing was learned the hard way on invitation.token.
--
-- KBS2 read the key too, from DbExplorerActivity's Organization node-fields, and the KBS3 deny
-- would not have reached it -- KBS2 runs its own server and bus. That field has now been dropped
-- from the list (the key is entered in the KBS3 back office, so KBS2 has no reason to show it),
-- which closes the path once a KBS2 backend deploy carries it. That deploy is NOT a precondition
-- for the deny here: the two servers are independent, and this list never bound KBS2 either way.
--
-- Note for staging: the GDPR refresh nulls bunny_api_key (10-anon-helpers.sql classifies it
-- 'anonymised'), so this column reads false for every organisation there. That is correct, not a
-- regression -- staging genuinely holds no keys.

-- BLANK IS NOT SET. `IS NOT NULL` would be the obvious expression and it would be wrong: the server
-- reads the key as `isBlank(apiKey) ? null : apiKey.trim()` (BunnyStreamRestService) and reports
-- `organization.bunnyApiKey` as MISSING for a blank one, and the client code this replaces required
-- `length > 0`. An empty string would otherwise light the "a key is set" hint on one screen while the
-- Medias tab said the key was missing on another. The definition here is the server's own.
--
-- LOCKS. Unlike a plain nullable ADD COLUMN, adding a STORED generated column REWRITES the table and
-- holds ACCESS EXCLUSIVE on `organization` for the duration. ~309 rows, so microseconds -- but anyone
-- copying this as a template for a generated column on a large table should not inherit the silence.
--
-- IF NOT EXISTS so a re-run cannot abort the boot migration, following V0103.

ALTER TABLE organization
    ADD COLUMN IF NOT EXISTS bunny_api_key_set boolean
        GENERATED ALWAYS AS (nullif(btrim(bunny_api_key), '') IS NOT NULL) STORED;

COMMENT ON COLUMN organization.bunny_api_key_set IS
    'Whether bunny_api_key holds a non-blank value. Generated, so it cannot drift from the source, and '
    'blank counts as unset exactly as the server does. Exists so clients can render the "a key is set" '
    'hint without the key ever leaving the server.';
