-- Speeds up account-scoped queries (anything gated by accountCanAccessPersonMedias), most visibly
-- the front-office video/streaming "my attendances" query — 61 ms because it could not start from
-- the account.
--
-- That query filters attendances by the booking account, reached via
-- documentLine.document.person. With no index on person.frontend_account_id the planner cannot
-- find "the account's persons" directly, so it SEQUENTIALLY SCANS the whole person table — twice,
-- once for the person and once for its account_person — then hash-joins against the event's
-- documents and applies the account filter last (EXPLAIN ANALYZE via the /monitor Analyze tool):
--   Seq Scan on person j4  (rows=58901)
--   Seq Scan on person j5  (rows=58901)
--   Filter: (frontend_account_id = 15)  Rows Removed by Filter: 5258
--
-- frontend_account_id lets the planner jump straight to the account's few persons; account_person_id
-- covers the linked family-member branch of accountCanAccessPersonMedias
-- (person.accountPerson.frontendAccount = account). Together they let the query run account-first
-- (few rows) instead of scanning 58 901 persons. Both help every account-scoped query, not just video.
--
-- Idempotent via IF NOT EXISTS — a no-op where the same-named indexes were already created manually
-- (live validation via scripts/create-person-account-access-indexes.sql, CONCURRENTLY), and applied
-- elsewhere at the next deploy. Plain CREATE INDEX (CONCURRENTLY cannot run inside the migration
-- transaction); each build takes a second or two, during which concurrent writes to person block
-- (reads are unaffected).

CREATE INDEX IF NOT EXISTS person_frontend_account_idx ON public.person (frontend_account_id);
CREATE INDEX IF NOT EXISTS person_account_person_idx ON public.person (account_person_id);
