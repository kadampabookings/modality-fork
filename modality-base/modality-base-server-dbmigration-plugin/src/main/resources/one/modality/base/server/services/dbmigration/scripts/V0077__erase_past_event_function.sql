-- V0077: install erase_past_event() — the GDPR per-event erasure as a guarded function.
--
-- WHAT THIS IS. The per-event erasure of special-needs/free-text data (GDPR Art. 9 /
-- Art. 5(1)(e)) that lived in the aggregate repo as
-- scripts/erase-past-event-special-needs.sql, frozen into a database function so the
-- event id is a real parameter instead of an edited line. The SQL here is a direct port
-- of that script as validated: full review, postgres:17 rehearsal, and a production dry
-- run (aggregate commits 51f427e + f0372b7 hold the standalone version and its
-- history). The aggregate script is now a thin driver that calls this.
--
-- WHAT IT DOES, in one paragraph. For every booking of ONE past event it REPLACES the
-- unsafe free-text content with a dated standard message — it never deletes a row.
-- Unsafe = document.special_needs/comment/request, document_line.comment,
-- history.request (+ the request value redacted INSIDE the history.changes JSON),
-- history.comment ONLY where the row's mail_id points at a letterless mail (guest-typed
-- subject lines), list_item.comment (booking-linked), money_transfer.comment,
-- letterless mail subject/content (transmitted rows only — rewriting a queued row
-- would send the tag), and chat messages (kind='text') of the event's conversations.
-- KEPT and asserted byte-identical: letter mails (letter_id set) and every other
-- history.comment — the action trail. All counts and both-direction before/after
-- invariants are checked before the function returns; any violation raises, which
-- aborts the (uncommitted) transaction. Full rationale, trigger analysis and accepted
-- residuals: docs/operations/gdpr-past-event-erasure-reference.md in the aggregate repo.
--
-- THE TAG CARRIES THE REMOVAL DATE:  [personal data removed (GDPR) on YYYY-MM-DD]
-- Because the date varies, "already erased" is recognised by PREFIX
-- ('[personal data removed (GDPR)' ...), not by equality — so a re-run never re-stamps:
-- each value keeps the date of its FIRST removal, and legacy undated tags
-- ('[personal data removed (GDPR)]') are recognised too. Accepted edge: a value that
-- genuinely begins with that prefix would be treated as already erased.
--
-- FOUR INTERLOCKS — a standing destructive routine must be hard to run by accident:
--   1. Statement of intent (same idiom as the staging anonymiser's kbs.anon_target):
--      the session must have run  SET LOCAL kbs.erase_confirm = 'ERASE EVENT <id>';
--      with the SAME id as the parameter — a deliberate double entry, like typing a
--      name to confirm a deletion. A bare SELECT erase_past_event(n) does nothing.
--   2. The transaction must be REPEATABLE READ (or SERIALIZABLE): the before/after
--      proof compares one pinned snapshot. The function refuses READ COMMITTED.
--   3. The call must be a LATER statement of an explicit transaction
--      (transaction_timestamp() <> statement_timestamp()): a single autocommit
--      statement — which would COMMIT the erasure unreviewed at statement end — is
--      refused even if the session's defaults satisfy interlocks 1 and 2. Corollary:
--      send statements separately (DBeaver Execute Script does); a whole file pasted
--      as ONE psql -c string shares one statement_timestamp and is refused.
--   4. EXECUTE is revoked from PUBLIC below; only the owner (the migration/admin role
--      that boots the server) and explicit grantees can call it. An operator seeing
--      "permission denied for function erase_past_event" is on the wrong role — the
--      fix is to connect as the intended role, never an ad-hoc GRANT.
-- The function never commits: the operator reads the returned grid, then commits or
-- rolls back — a rollback is a free dry run.
--
-- OUTPUT. The grid (step/metric/value rows: where, event, preflight, before,
-- updated-row counts, verify, status) is returned ONLY when every check passes — a
-- set-returning function buffers its result, so an abort discards it. What survives an
-- abort: a streaming RAISE NOTICE with db/event context emitted BEFORE any write, and
-- assertion messages that carry their own numbers.
--
-- WHY A FUNCTION, NOT A PROCEDURE. A procedure's only extra power is committing inside
-- itself — exactly what this must never do. A function returns the whole transcript.
--
-- Timezone is pinned per call (SET clause below): the date guards and the tag date
-- follow the business calendar, not the operator's laptop. The driver ALSO pins the
-- surrounding transaction (SET LOCAL TimeZone) so COMMIT-time deferred triggers match.
--
-- SHIPPED = FROZEN. Boot migrations are checksummed: never edit this file once applied
-- — scope changes ship as a new version (index.txt rules). A SUPERSEDING VERSION MUST:
--   * DROP FUNCTION public.erase_past_event(integer, boolean) first if it changes the
--     signature — CREATE OR REPLACE with a different signature creates an OVERLOAD,
--     making the driver's call ambiguous ("function is not unique");
--   * repeat the REVOKE ... FROM PUBLIC — a new signature is a new function and gets
--     PostgreSQL's default PUBLIC EXECUTE;
--   * update the KEEP-IN-SYNC copies in the aggregate repo's
--     scripts/probe-past-event-freetext.sql (tag prefix, bracket-pattern regex,
--     3-month line, letterless rules) — they name this migration as their authority.

CREATE OR REPLACE FUNCTION public.erase_past_event(p_event_id integer,
                                                   p_include_enqueued boolean DEFAULT false)
RETURNS TABLE(step text, metric text, value text)
LANGUAGE plpgsql
SET timezone TO 'Europe/London'
AS $erase$
DECLARE
    v_tag_prefix constant text := '[personal data removed (GDPR)';
    v_tag        varchar(255);
    v_confirm    text := nullif(current_setting('kbs.erase_confirm', true), '');
    v_bad_patterns text;
    v_bookings   bigint;
    v_enqueued_left bigint;
    v_q_before   bigint; v_q_after bigint;
    v_rows_before bigint; v_rows_after bigint;
    v_row_before text; v_row_after text;
    ev  record;
    r   record;
    bad text := '';
    n   bigint;
BEGIN
    v_tag := v_tag_prefix || ' on ' || current_date || ']';

    -- ================= GUARDS =================
    IF p_event_id IS NULL THEN
        RAISE EXCEPTION 'erase_past_event: the event id is NULL';
    END IF;

    IF current_setting('transaction_isolation') NOT IN ('repeatable read', 'serializable') THEN
        RAISE EXCEPTION 'erase_past_event must run in a REPEATABLE READ transaction — start with: BEGIN; SET TRANSACTION ISOLATION LEVEL REPEATABLE READ; (the before/after proof needs one pinned snapshot)';
    END IF;

    -- A single autocommit statement would COMMIT the erasure unreviewed at statement
    -- end; require the call to be a later statement of an explicit transaction.
    IF transaction_timestamp() = statement_timestamp() THEN
        RAISE EXCEPTION 'erase_past_event refuses to run as a single autocommit statement — it would commit with no review. Run the driver: BEGIN; SET TRANSACTION ISOLATION LEVEL REPEATABLE READ; SET LOCAL kbs.erase_confirm = ...; SELECT * FROM erase_past_event(...); then COMMIT or ROLLBACK, each statement sent separately';
    END IF;

    IF v_confirm IS DISTINCT FROM ('ERASE EVENT ' || p_event_id) THEN
        RAISE EXCEPTION 'erase_past_event: missing statement of intent — run  SET LOCAL kbs.erase_confirm = ''ERASE EVENT %'';  in this transaction first (found: %)',
                        p_event_id, coalesce(v_confirm, '<unset>');
    END IF;

    SELECT e.id, e.name, e.start_date, e.end_date, e.organization_id, e.send_history_emails
      INTO ev FROM event e WHERE e.id = p_event_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'event % does not exist', p_event_id;
    END IF;
    IF ev.end_date >= current_date THEN
        RAISE EXCEPTION 'event % (%) has not finished — end_date % is not in the past; this erases PAST events only',
                        ev.id, ev.name, ev.end_date;
    END IF;
    IF ev.end_date > (current_date - interval '3 months')::date THEN
        RAISE NOTICE 'event % (%) ended less than 3 months ago (end_date %) — the retention line is 3 months after event close; continue only if this early run is deliberate',
                     ev.id, ev.name, ev.end_date;
    END IF;

    -- Deferred triggers fired at COMMIT read this session temp table; a safety net.
    PERFORM set_transaction_parameters(true);

    -- The premise "letter mails are safe to keep" holds only while no bracket_pattern
    -- row mentions a free-text column: interpret_brackets executes replacement as raw
    -- SQL, and replacements can subquery any table, so the test is by COLUMN NAME as a
    -- whole word. Over-matching aborts and summons a human — the correct direction.
    -- KEEP IN SYNC with the aggregate's scripts/probe-past-event-freetext.sql §4.
    SELECT string_agg(format('#%s [%s] %s', bp.id, bp.lang, bp.pattern), ', ') INTO v_bad_patterns
      FROM bracket_pattern bp
     WHERE bp.replacement ~* '(special_needs|\mcomment\M|\mrequest\M)'
        OR bp.condition   ~* '(special_needs|\mcomment\M|\mrequest\M)';
    IF v_bad_patterns IS NOT NULL THEN
        RAISE EXCEPTION 'bracket_pattern mentions a free-text column (%) — kept letter mails could retain erased text: revisit scope', v_bad_patterns;
    END IF;

    SELECT count(*) INTO v_bookings FROM document d WHERE d.event_id = ev.id;

    -- Streams immediately (NOTICEs are not buffered), BEFORE any write — this context
    -- line survives any later abort, unlike the grid.
    RAISE NOTICE 'erase_past_event: db=%, event % (%, % .. %), % bookings, tag=%',
                 current_database(), ev.id, ev.name, ev.start_date, ev.end_date, v_bookings, v_tag;

    RETURN QUERY VALUES
        ('where',  'database',            current_database()::text),
        ('where',  'server',              coalesce(inet_server_addr()::text, 'local')),
        ('where',  'run_at',              now()::text),
        ('where',  'tag',                 v_tag::text),
        ('event',  'id',                  ev.id::text),
        ('event',  'name',                ev.name::text),
        ('event',  'start_date',          ev.start_date::text),
        ('event',  'end_date',            ev.end_date::text),
        ('event',  'organization',        (SELECT o.name FROM organization o WHERE o.id = ev.organization_id)::text),
        ('event',  'send_history_emails', ev.send_history_emails::text),
        ('event',  'bookings',            v_bookings::text);

    -- ================= TEMP OBJECTS (views before tables — same-session re-call) ====
    DROP VIEW IF EXISTS sn_hist_changes_todo;
    DROP VIEW IF EXISTS sn_outside_now;
    DROP VIEW IF EXISTS sn_kept_now;
    DROP TABLE IF EXISTS sn_event, sn_tag, sn_target_docs, sn_target_convs, sn_unsafe_mails,
                         sn_kept_mails, sn_unsafe_hist_comments, sn_hist_changes,
                         sn_before, sn_after, sn_kept_before, sn_kept_after;

    -- plpgsql cannot bind variables inside CREATE TABLE AS, so parameter-carrying temp
    -- tables are CREATE + INSERT. sn_tag carries the dated tag AND the prefix pattern:
    -- "already erased" is prefix-matched (col LIKE pattern), never equality-matched,
    -- so earlier runs' dates survive and legacy undated tags are recognised.
    CREATE TEMP TABLE sn_event (event_id integer PRIMARY KEY);
    INSERT INTO sn_event VALUES (p_event_id);
    CREATE TEMP TABLE sn_tag (tag varchar(255), pattern varchar(255));
    INSERT INTO sn_tag VALUES (v_tag, v_tag_prefix || '%');

    CREATE TEMP TABLE sn_target_docs (id integer PRIMARY KEY);
    INSERT INTO sn_target_docs (id)
    SELECT d.id FROM document d JOIN sn_event p ON p.event_id = d.event_id;
    ANALYZE sn_target_docs;

    -- Conversations reachable from the event — by booking OR by the event itself.
    CREATE TEMP TABLE sn_target_convs AS
    SELECT cv.id FROM conversation cv
     WHERE EXISTS (SELECT 1 FROM sn_target_docs t WHERE t.id = cv.document_id)
        OR cv.event_id = (SELECT se.event_id FROM sn_event se);
    ALTER TABLE sn_target_convs ADD PRIMARY KEY (id);
    ANALYZE sn_target_convs;

    -- The mails of targeted bookings, classified once into the two complementary
    -- classes: UNSAFE = letterless (guest-authored contact-us / refund messages and
    -- stranded notification mails) — content replaced; KEPT = letter mails — asserted
    -- byte-identical.
    CREATE TEMP TABLE sn_unsafe_mails AS
    SELECT m.id FROM mail m JOIN sn_target_docs t ON t.id = m.document_id
     WHERE m.letter_id IS NULL;
    ALTER TABLE sn_unsafe_mails ADD PRIMARY KEY (id);
    ANALYZE sn_unsafe_mails;

    CREATE TEMP TABLE sn_kept_mails AS
    SELECT m.id FROM mail m JOIN sn_target_docs t ON t.id = m.document_id
     WHERE m.letter_id IS NOT NULL;
    ALTER TABLE sn_kept_mails ADD PRIMARY KEY (id);
    ANALYZE sn_kept_mails;

    -- The UNSAFE history comments: rows whose mail_id points at a letterless mail —
    -- their comment quotes the guest's own typed subject line. The rule is the mail's
    -- letter-lessness, wherever that mail lives.
    CREATE TEMP TABLE sn_unsafe_hist_comments AS
    SELECT h.id FROM history h
      JOIN sn_target_docs t ON t.id = h.document_id
      JOIN mail m ON m.id = h.mail_id
     WHERE m.letter_id IS NULL AND h.comment IS NOT NULL;
    ALTER TABLE sn_unsafe_hist_comments ADD PRIMARY KEY (id);
    ANALYZE sn_unsafe_hist_comments;

    -- Which history rows need their `changes` touched, and how — defined ONCE as a
    -- view: the UPDATE reads a snapshot of it, the assertion re-evaluates it live.
    -- 'redact' = a JSON array holding an un-redacted AddRequestEvent.request;
    -- 'null' = guest text in a shape that cannot be edited in place (KBS2-era).
    CREATE TEMP VIEW sn_hist_changes_todo AS
    SELECT h.id,
           CASE WHEN pg_input_is_valid(h.changes, 'jsonb')
                 AND jsonb_typeof(h.changes::jsonb) = 'array' THEN 'redact' ELSE 'null' END AS action
      FROM history h
      JOIN sn_target_docs t ON t.id = h.document_id
      CROSS JOIN sn_tag st
     WHERE h.changes IS NOT NULL
       AND (
            (    pg_input_is_valid(h.changes, 'jsonb')
             AND jsonb_typeof(h.changes::jsonb) = 'array'
             AND EXISTS (SELECT 1 FROM jsonb_array_elements(h.changes::jsonb) e
                          WHERE e->>'$codec' = 'AddRequestEvent'
                            AND e->>'request' IS NOT NULL
                            AND e->>'request' NOT LIKE st.pattern))
         OR (    h.request IS NOT NULL
             AND NOT (pg_input_is_valid(h.changes, 'jsonb')
                      AND jsonb_typeof(h.changes::jsonb) = 'array'))
       );

    CREATE TEMP TABLE sn_hist_changes AS SELECT * FROM sn_hist_changes_todo;
    ALTER TABLE sn_hist_changes ADD PRIMARY KEY (id);
    ANALYZE sn_hist_changes;

    -- ================= PREFLIGHT =================
    SELECT count(*) FILTER (WHERE d.special_needs IS NOT NULL AND d.special_needs NOT LIKE st.pattern) AS special_needs_to_tag,
           count(*) FILTER (WHERE d.comment       IS NOT NULL AND d.comment       NOT LIKE st.pattern) AS comments_to_tag,
           count(*) FILTER (WHERE d.request       IS NOT NULL AND d.request       NOT LIKE st.pattern) AS requests_to_tag,
           count(*) FILTER (WHERE d.special_needs LIKE st.pattern)                                     AS special_needs_already_tagged,
           count(*) FILTER (WHERE d.comment       LIKE st.pattern)                                     AS comments_already_tagged
      INTO r
      FROM document d JOIN sn_target_docs t ON t.id = d.id CROSS JOIN sn_tag st;
    RETURN QUERY VALUES
        ('preflight', 'special_needs_to_tag',         r.special_needs_to_tag::text),
        ('preflight', 'doc_comments_to_tag',          r.comments_to_tag::text),
        ('preflight', 'doc_requests_to_tag',          r.requests_to_tag::text),
        ('preflight', 'special_needs_already_tagged', r.special_needs_already_tagged::text),
        ('preflight', 'doc_comments_already_tagged',  r.comments_already_tagged::text);

    SELECT count(*) FILTER (WHERE dl.comment IS NOT NULL AND dl.comment NOT LIKE st.pattern) AS line_comments_to_tag
      INTO r
      FROM document_line dl JOIN sn_target_docs t ON t.id = dl.document_id CROSS JOIN sn_tag st;
    RETURN QUERY VALUES ('preflight', 'line_comments_to_tag', r.line_comments_to_tag::text);

    SELECT count(*) FILTER (WHERE h.request IS NOT NULL AND h.request NOT LIKE st.pattern) AS hist_requests_to_tag,
           (SELECT count(*) FROM sn_hist_changes hc WHERE hc.action = 'redact')            AS hist_changes_to_redact,
           (SELECT count(*) FROM sn_hist_changes hc WHERE hc.action = 'null')              AS hist_changes_to_null,
           count(*) FILTER (WHERE h.comment IS NOT NULL AND h.comment NOT LIKE st.pattern
                              AND EXISTS (SELECT 1 FROM sn_unsafe_hist_comments u WHERE u.id = h.id)) AS hist_comments_to_tag,
           count(*) FILTER (WHERE h.comment IS NOT NULL
                              AND NOT EXISTS (SELECT 1 FROM sn_unsafe_hist_comments u WHERE u.id = h.id)) AS hist_comments_kept
      INTO r
      FROM history h JOIN sn_target_docs t ON t.id = h.document_id CROSS JOIN sn_tag st;
    RETURN QUERY VALUES
        ('preflight', 'hist_requests_to_tag',   r.hist_requests_to_tag::text),
        ('preflight', 'hist_changes_to_redact', r.hist_changes_to_redact::text),
        ('preflight', 'hist_changes_to_null',   r.hist_changes_to_null::text),
        ('preflight', 'hist_comments_to_tag',   r.hist_comments_to_tag::text),
        ('preflight', 'hist_comments_kept',     r.hist_comments_kept::text);

    SELECT count(*) FILTER (WHERE li.comment IS NOT NULL AND li.comment NOT LIKE st.pattern) AS list_comments_to_tag
      INTO r
      FROM list_item li JOIN sn_target_docs t ON t.id = li.document_id CROSS JOIN sn_tag st;
    RETURN QUERY VALUES ('preflight', 'list_comments_to_tag', r.list_comments_to_tag::text);

    SELECT count(*) FILTER (WHERE mt.comment IS NOT NULL AND mt.comment NOT LIKE st.pattern) AS mt_comments_to_tag
      INTO r
      FROM money_transfer mt JOIN sn_target_docs t ON t.id = mt.document_id CROSS JOIN sn_tag st;
    RETURN QUERY VALUES ('preflight', 'money_transfer_comments_to_tag', r.mt_comments_to_tag::text);

    -- mail: letterless queued should be 0 on an event that ended months ago; if not,
    -- that is a send queue to look at, not to rewrite.
    SELECT count(*) FILTER (WHERE u.id IS NOT NULL AND m.transmitted
                              AND (m.subject NOT LIKE st.pattern OR m.content NOT LIKE st.pattern)) AS mail_letterless_to_tag,
           count(*) FILTER (WHERE u.id IS NOT NULL AND NOT m.transmitted)                           AS letterless_queued_skipped,
           count(*) FILTER (WHERE k.id IS NOT NULL)                                                 AS letter_mails_kept,
           count(*) FILTER (WHERE k.id IS NOT NULL AND NOT m.transmitted)                           AS letter_mails_queued
      INTO r
      FROM mail m
      JOIN sn_target_docs t ON t.id = m.document_id
      LEFT JOIN sn_unsafe_mails u ON u.id = m.id
      LEFT JOIN sn_kept_mails  k ON k.id = m.id
      CROSS JOIN sn_tag st;
    RETURN QUERY VALUES
        ('preflight', 'mail_letterless_to_tag',    r.mail_letterless_to_tag::text),
        ('preflight', 'letterless_queued_skipped', r.letterless_queued_skipped::text),
        ('preflight', 'letter_mails_kept',         r.letter_mails_kept::text),
        ('preflight', 'letter_mails_queued',       r.letter_mails_queued::text);

    SELECT count(*) FILTER (WHERE cm.kind = 'text' AND cm.content NOT LIKE st.pattern) AS chat_messages_to_tag,
           count(*) FILTER (WHERE cm.kind <> 'text')                                   AS chat_system_lines_kept
      INTO r
      FROM chat_message cm JOIN sn_target_convs c ON c.id = cm.conversation_id CROSS JOIN sn_tag st;
    RETURN QUERY VALUES
        ('preflight', 'chat_messages_to_tag',   r.chat_messages_to_tag::text),
        ('preflight', 'chat_system_lines_kept', r.chat_system_lines_kept::text);

    -- enqueued_request (KBS2-era payload queue): counted always, rewritten only when
    -- p_include_enqueued. EXECUTED rows only — an un-executed row is replayable.
    SELECT count(*) FILTER (WHERE er.execution_date IS NOT NULL
                              AND (er.request_string NOT LIKE st.pattern
                                   OR (er.reply_string IS NOT NULL AND er.reply_string NOT LIKE st.pattern))) AS executed_payloads_to_tag,
           count(*) FILTER (WHERE er.execution_date IS NULL)                                                  AS unexecuted_kept
      INTO r
      FROM enqueued_request er JOIN sn_target_docs t ON t.id = er.document_id CROSS JOIN sn_tag st;
    RETURN QUERY VALUES
        ('preflight', 'executed_payloads_to_tag', r.executed_payloads_to_tag::text),
        ('preflight', 'unexecuted_kept',          r.unexecuted_kept::text),
        ('preflight', 'include_enqueued',         p_include_enqueued::text);

    -- ================= SNAPSHOTS (defined once, materialised before AND after) ======
    CREATE TEMP VIEW sn_outside_now AS
    SELECT dtab.*, ltab.*, htab.*, litab.*, mttab.*, mtab.*, ctab.*
      FROM
      (SELECT count(*) FILTER (WHERE NOT EXISTS (SELECT 1 FROM sn_target_docs t WHERE t.id = d.id)
                                 AND (d.special_needs IS NOT NULL OR d.comment IS NOT NULL OR d.request IS NOT NULL)) AS out_docs,
              count(*) FILTER (WHERE NOT EXISTS (SELECT 1 FROM sn_target_docs t WHERE t.id = d.id)
                                 AND (d.special_needs LIKE st.pattern OR d.comment LIKE st.pattern
                                      OR d.request LIKE st.pattern))                                                  AS out_docs_tagged
         FROM document d CROSS JOIN sn_tag st) dtab,
      (SELECT count(*) FILTER (WHERE NOT EXISTS (SELECT 1 FROM sn_target_docs t WHERE t.id = dl.document_id)
                                 AND dl.comment IS NOT NULL)                                                          AS out_line_comments,
              count(*) FILTER (WHERE NOT EXISTS (SELECT 1 FROM sn_target_docs t WHERE t.id = dl.document_id)
                                 AND dl.comment LIKE st.pattern)                                                      AS out_line_tagged
         FROM document_line dl CROSS JOIN sn_tag st) ltab,
      (SELECT count(*) FILTER (WHERE NOT EXISTS (SELECT 1 FROM sn_target_docs t WHERE t.id = h.document_id)
                                 AND (h.comment IS NOT NULL OR h.request IS NOT NULL OR h.changes IS NOT NULL))       AS out_history,
              count(*) FILTER (WHERE NOT EXISTS (SELECT 1 FROM sn_target_docs t WHERE t.id = h.document_id)
                                 AND (h.comment LIKE st.pattern OR h.request LIKE st.pattern))                        AS out_history_tagged
         FROM history h CROSS JOIN sn_tag st) htab,
      -- list_item: the tagged counter spans ALL rows outside the target set, INCLUDING
      -- person-only rows (document_id IS NULL) — a tag wrongly written there must be seen.
      (SELECT count(*) FILTER (WHERE li.document_id IS NOT NULL
                                 AND NOT EXISTS (SELECT 1 FROM sn_target_docs t WHERE t.id = li.document_id)
                                 AND li.comment IS NOT NULL)                                                          AS out_list_comments,
              count(*) FILTER (WHERE (li.document_id IS NULL
                                      OR NOT EXISTS (SELECT 1 FROM sn_target_docs t WHERE t.id = li.document_id))
                                 AND li.comment LIKE st.pattern)                                                      AS out_list_tagged
         FROM list_item li CROSS JOIN sn_tag st) litab,
      (SELECT count(*) FILTER (WHERE NOT EXISTS (SELECT 1 FROM sn_target_docs t WHERE t.id = mt.document_id)
                                 AND mt.comment IS NOT NULL)                                                          AS out_mt_comments,
              count(*) FILTER (WHERE NOT EXISTS (SELECT 1 FROM sn_target_docs t WHERE t.id = mt.document_id)
                                 AND mt.comment LIKE st.pattern)                                                      AS out_mt_tagged
         FROM money_transfer mt CROSS JOIN sn_tag st) mttab,
      (SELECT count(*)                                                                                                AS mail_rows,
              count(*) FILTER (WHERE (m.document_id IS NULL
                                      OR NOT EXISTS (SELECT 1 FROM sn_target_docs t WHERE t.id = m.document_id))
                                 AND (m.subject LIKE st.pattern OR m.content LIKE st.pattern))                        AS out_mail_tagged,
              count(*) FILTER (WHERE EXISTS (SELECT 1 FROM sn_target_docs t WHERE t.id = m.document_id)
                                 AND NOT m.transmitted)                                                               AS queued_mail
         FROM mail m CROSS JOIN sn_tag st) mtab,
      (SELECT count(*) FILTER (WHERE cm.content LIKE st.pattern
                                 AND NOT EXISTS (SELECT 1 FROM sn_target_convs c WHERE c.id = cm.conversation_id))    AS out_chat_tagged
         FROM chat_message cm CROSS JOIN sn_tag st) ctab;

    CREATE TEMP TABLE sn_before AS SELECT * FROM sn_outside_now;

    -- Digests of the kept records: per-row md5 of the two fields' md5s (an unambiguous
    -- pair), md5 of the ordered list on top; 'none' for the empty set.
    CREATE TEMP VIEW sn_kept_now AS
    SELECT (SELECT count(*) FROM sn_kept_mails)                                    AS letter_mails,
           (SELECT coalesce(md5(string_agg(md5(md5(m.subject) || md5(m.content)), ',' ORDER BY m.id)), 'none')
              FROM mail m JOIN sn_kept_mails k ON k.id = m.id)                     AS letter_mails_digest,
           (SELECT count(*) FROM history h JOIN sn_target_docs t ON t.id = h.document_id
             WHERE h.comment IS NOT NULL
               AND NOT EXISTS (SELECT 1 FROM sn_unsafe_hist_comments u WHERE u.id = h.id)) AS kept_comments,
           (SELECT coalesce(md5(string_agg(md5(h.comment), ',' ORDER BY h.id)), 'none')
              FROM history h JOIN sn_target_docs t ON t.id = h.document_id
             WHERE h.comment IS NOT NULL
               AND NOT EXISTS (SELECT 1 FROM sn_unsafe_hist_comments u WHERE u.id = h.id)) AS kept_comments_digest;

    CREATE TEMP TABLE sn_kept_before AS SELECT * FROM sn_kept_now;

    SELECT * INTO r FROM sn_before;
    RETURN QUERY VALUES
        ('before', 'out_docs',           r.out_docs::text),
        ('before', 'out_docs_tagged',    r.out_docs_tagged::text),
        ('before', 'out_line_comments',  r.out_line_comments::text),
        ('before', 'out_line_tagged',    r.out_line_tagged::text),
        ('before', 'out_history',        r.out_history::text),
        ('before', 'out_history_tagged', r.out_history_tagged::text),
        ('before', 'out_list_comments',  r.out_list_comments::text),
        ('before', 'out_list_tagged',    r.out_list_tagged::text),
        ('before', 'out_mt_comments',    r.out_mt_comments::text),
        ('before', 'out_mt_tagged',      r.out_mt_tagged::text),
        ('before', 'mail_rows',          r.mail_rows::text),
        ('before', 'out_mail_tagged',    r.out_mail_tagged::text),
        ('before', 'queued_mail',        r.queued_mail::text),
        ('before', 'out_chat_tagged',    r.out_chat_tagged::text);
    SELECT * INTO r FROM sn_kept_before;
    RETURN QUERY VALUES
        ('before', 'letter_mails',         r.letter_mails::text),
        ('before', 'letter_mails_digest',  r.letter_mails_digest::text),
        ('before', 'kept_comments',        r.kept_comments::text),
        ('before', 'kept_comments_digest', r.kept_comments_digest::text);

    -- ================= THE ERASURE — one UPDATE per table, one row version per row ==
    -- Predicates exclude NULL and already-tagged (prefix-matched) values: idempotent
    -- re-runs, first-removal dates preserved, sys_log rows only for genuine changes.
    -- The 'updated' grid rows record how many rows THIS run rewrote, per table.

    UPDATE document d
       SET special_needs = CASE WHEN d.special_needs IS NOT NULL AND d.special_needs NOT LIKE st.pattern
                                THEN st.tag ELSE d.special_needs END,
           comment       = CASE WHEN d.comment IS NOT NULL AND d.comment NOT LIKE st.pattern
                                THEN st.tag ELSE d.comment END,
           request       = CASE WHEN d.request IS NOT NULL AND d.request NOT LIKE st.pattern
                                THEN st.tag ELSE d.request END
      FROM sn_target_docs t, sn_tag st
     WHERE t.id = d.id
       AND (   (d.special_needs IS NOT NULL AND d.special_needs NOT LIKE st.pattern)
            OR (d.comment       IS NOT NULL AND d.comment       NOT LIKE st.pattern)
            OR (d.request       IS NOT NULL AND d.request       NOT LIKE st.pattern));
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('updated', 'document', n::text);

    UPDATE document_line dl
       SET comment = st.tag
      FROM sn_target_docs t, sn_tag st
     WHERE t.id = dl.document_id
       AND dl.comment IS NOT NULL AND dl.comment NOT LIKE st.pattern;
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('updated', 'document_line', n::text);

    -- history: request wholesale; comment only under the letterless-mail rule; the
    -- redacted-element condition matches the view's qualifying predicate exactly.
    UPDATE history h
       SET request = CASE WHEN h.request IS NOT NULL AND h.request NOT LIKE st.pattern
                          THEN st.tag ELSE h.request END,
           comment = CASE WHEN h.comment IS NOT NULL AND h.comment NOT LIKE st.pattern
                           AND EXISTS (SELECT 1 FROM sn_unsafe_hist_comments u WHERE u.id = h.id)
                          THEN st.tag ELSE h.comment END,
           changes = CASE (SELECT hc.action FROM sn_hist_changes hc WHERE hc.id = h.id)
                       WHEN 'redact' THEN
                            (SELECT jsonb_agg(
                                      CASE WHEN e->>'$codec' = 'AddRequestEvent'
                                            AND e->>'request' IS NOT NULL
                                            AND e->>'request' NOT LIKE st.pattern
                                           THEN jsonb_set(e, '{request}', to_jsonb(st.tag::text))
                                           ELSE e END ORDER BY o)::text
                               FROM jsonb_array_elements(h.changes::jsonb) WITH ORDINALITY AS a(e, o))
                       WHEN 'null' THEN NULL
                       ELSE h.changes END
      FROM sn_target_docs t, sn_tag st
     WHERE t.id = h.document_id
       AND (   (h.request IS NOT NULL AND h.request NOT LIKE st.pattern)
            OR (h.comment IS NOT NULL AND h.comment NOT LIKE st.pattern
                AND EXISTS (SELECT 1 FROM sn_unsafe_hist_comments u WHERE u.id = h.id))
            OR EXISTS (SELECT 1 FROM sn_hist_changes hc WHERE hc.id = h.id));
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('updated', 'history', n::text);

    UPDATE list_item li
       SET comment = st.tag
      FROM sn_target_docs t, sn_tag st
     WHERE t.id = li.document_id
       AND li.comment IS NOT NULL AND li.comment NOT LIKE st.pattern;
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('updated', 'list_item', n::text);

    UPDATE money_transfer mt
       SET comment = st.tag
      FROM sn_target_docs t, sn_tag st
     WHERE t.id = mt.document_id
       AND mt.comment IS NOT NULL AND mt.comment NOT LIKE st.pattern;
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('updated', 'money_transfer', n::text);

    -- LETTERLESS + TRANSMITTED ONLY. Never write transmitted (conditional self-DELETE
    -- trigger on auto_delete rows) or read (cascades into document.read).
    UPDATE mail m
       SET subject = st.tag,
           content = st.tag
      FROM sn_unsafe_mails u, sn_tag st
     WHERE u.id = m.id
       AND m.transmitted
       AND (m.subject NOT LIKE st.pattern OR m.content NOT LIKE st.pattern);
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('updated', 'mail', n::text);

    UPDATE chat_message cm
       SET content = st.tag
      FROM sn_tag st
     WHERE cm.kind = 'text'
       AND cm.content NOT LIKE st.pattern
       AND EXISTS (SELECT 1 FROM sn_target_convs c WHERE c.id = cm.conversation_id);
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('updated', 'chat_message', n::text);

    IF p_include_enqueued THEN
        UPDATE enqueued_request er
           SET request_string = CASE WHEN er.request_string NOT LIKE st.pattern THEN st.tag ELSE er.request_string END,
               reply_string   = CASE WHEN er.reply_string IS NOT NULL AND er.reply_string NOT LIKE st.pattern
                                     THEN st.tag ELSE er.reply_string END
          FROM sn_target_docs t, sn_tag st
         WHERE t.id = er.document_id
           AND er.execution_date IS NOT NULL
           AND (er.request_string NOT LIKE st.pattern
                OR (er.reply_string IS NOT NULL AND er.reply_string NOT LIKE st.pattern));
        GET DIAGNOSTICS n = ROW_COUNT;
        RETURN QUERY VALUES ('updated', 'enqueued_request', n::text);
    END IF;

    -- ================= ASSERTIONS (raise = the caller should roll back) =============
    CREATE TEMP TABLE sn_after AS SELECT * FROM sn_outside_now;
    CREATE TEMP TABLE sn_kept_after AS SELECT * FROM sn_kept_now;

    SELECT count(*) INTO n FROM document d JOIN sn_target_docs t ON t.id = d.id, sn_tag st
     WHERE (   (d.special_needs IS NOT NULL AND d.special_needs NOT LIKE st.pattern)
            OR (d.comment       IS NOT NULL AND d.comment       NOT LIKE st.pattern)
            OR (d.request       IS NOT NULL AND d.request       NOT LIKE st.pattern));
    IF n > 0 THEN bad := bad || format(E'\n  document: %s rows', n); END IF;

    SELECT count(*) INTO n FROM document_line dl
      JOIN sn_target_docs t ON t.id = dl.document_id, sn_tag st
     WHERE dl.comment IS NOT NULL AND dl.comment NOT LIKE st.pattern;
    IF n > 0 THEN bad := bad || format(E'\n  document_line: %s rows', n); END IF;

    SELECT count(*) INTO n FROM history h
      JOIN sn_target_docs t ON t.id = h.document_id, sn_tag st
     WHERE (   (h.request IS NOT NULL AND h.request NOT LIKE st.pattern)
            OR (h.comment IS NOT NULL AND h.comment NOT LIKE st.pattern
                AND EXISTS (SELECT 1 FROM sn_unsafe_hist_comments u WHERE u.id = h.id)));
    IF n > 0 THEN bad := bad || format(E'\n  history request/comment: %s rows', n); END IF;

    SELECT count(*) INTO n FROM sn_hist_changes_todo;
    IF n > 0 THEN bad := bad || format(E'\n  history.changes still holding a request: %s rows', n); END IF;

    SELECT count(*) INTO n FROM history h
      JOIN sn_hist_changes hc ON hc.id = h.id
     WHERE hc.action = 'redact'
       AND (h.changes IS NULL
            OR NOT pg_input_is_valid(h.changes, 'jsonb')
            OR jsonb_typeof(h.changes::jsonb) <> 'array');
    IF n > 0 THEN bad := bad || format(E'\n  history.changes we redacted is no longer a JSON array: %s rows', n); END IF;

    SELECT count(*) INTO n FROM list_item li
      JOIN sn_target_docs t ON t.id = li.document_id, sn_tag st
     WHERE li.comment IS NOT NULL AND li.comment NOT LIKE st.pattern;
    IF n > 0 THEN bad := bad || format(E'\n  list_item: %s rows', n); END IF;

    SELECT count(*) INTO n FROM money_transfer mt
      JOIN sn_target_docs t ON t.id = mt.document_id, sn_tag st
     WHERE mt.comment IS NOT NULL AND mt.comment NOT LIKE st.pattern;
    IF n > 0 THEN bad := bad || format(E'\n  money_transfer: %s rows', n); END IF;

    SELECT count(*) INTO n FROM mail m
      JOIN sn_unsafe_mails u ON u.id = m.id, sn_tag st
     WHERE m.transmitted AND (m.subject NOT LIKE st.pattern OR m.content NOT LIKE st.pattern);
    IF n > 0 THEN bad := bad || format(E'\n  mail (letterless, transmitted): %s rows', n); END IF;

    SELECT count(*) INTO n FROM chat_message cm, sn_tag st
     WHERE cm.kind = 'text' AND cm.content NOT LIKE st.pattern
       AND EXISTS (SELECT 1 FROM sn_target_convs c WHERE c.id = cm.conversation_id);
    IF n > 0 THEN bad := bad || format(E'\n  chat_message: %s rows', n); END IF;

    -- Computed once: the enqueued assertion (opt-in) and the verify row share it.
    SELECT count(*) INTO v_enqueued_left FROM enqueued_request er
      JOIN sn_target_docs t ON t.id = er.document_id, sn_tag st
     WHERE er.execution_date IS NOT NULL
       AND (er.request_string NOT LIKE st.pattern
            OR (er.reply_string IS NOT NULL AND er.reply_string NOT LIKE st.pattern));
    IF p_include_enqueued AND v_enqueued_left > 0 THEN
        bad := bad || format(E'\n  enqueued_request: %s rows', v_enqueued_left);
    END IF;

    IF bad <> '' THEN
        RAISE EXCEPTION 'Free text survives on targeted bookings [db=%, event=%]:% — ROLLBACK and investigate', current_database(), p_event_id, bad;
    END IF;

    SELECT k::text INTO v_row_before FROM sn_kept_before k;
    SELECT k::text INTO v_row_after  FROM sn_kept_after k;
    IF v_row_before IS DISTINCT FROM v_row_after THEN
        RAISE EXCEPTION 'A KEPT record changed — letter mails and kept history comments must be byte-identical (before=%, after=%): ROLLBACK', v_row_before, v_row_after;
    END IF;

    SELECT sb.queued_mail, sb.mail_rows INTO v_q_before, v_rows_before FROM sn_before sb;
    SELECT sa.queued_mail, sa.mail_rows INTO v_q_after,  v_rows_after  FROM sn_after sa;
    IF v_q_after <> v_q_before THEN
        RAISE EXCEPTION 'The untransmitted mail queue changed (% now, % before) — ROLLBACK', v_q_after, v_q_before;
    END IF;
    IF v_rows_after <> v_rows_before THEN
        RAISE EXCEPTION 'The mail table gained or lost rows (% now, % before) — a trigger fired: ROLLBACK', v_rows_after, v_rows_before;
    END IF;

    SELECT sb::text INTO v_row_before FROM sn_before sb;
    SELECT sa::text INTO v_row_after  FROM sn_after sa;
    IF v_row_before IS DISTINCT FROM v_row_after THEN
        RAISE EXCEPTION 'A count outside the target set changed — the target predicate is broken (before=%, after=%): ROLLBACK', v_row_before, v_row_after;
    END IF;

    -- ================= VERIFY (the transcript record) =================
    SELECT (SELECT count(*) FROM document d JOIN sn_target_docs t ON t.id = d.id, sn_tag st
             WHERE ((d.special_needs IS NOT NULL AND d.special_needs NOT LIKE st.pattern)
                 OR (d.comment IS NOT NULL AND d.comment NOT LIKE st.pattern)
                 OR (d.request IS NOT NULL AND d.request NOT LIKE st.pattern)))            AS doc_left,
           (SELECT count(*) FROM document d JOIN sn_target_docs t ON t.id = d.id, sn_tag st
             WHERE d.special_needs LIKE st.pattern)                                        AS special_needs_tagged,
           (SELECT count(*) FROM document d JOIN sn_target_docs t ON t.id = d.id, sn_tag st
             WHERE d.comment LIKE st.pattern)                                              AS doc_comments_tagged,
           (SELECT count(*) FROM document d JOIN sn_target_docs t ON t.id = d.id, sn_tag st
             WHERE d.request LIKE st.pattern)                                              AS doc_requests_tagged,
           (SELECT count(*) FROM history h JOIN sn_target_docs t ON t.id = h.document_id, sn_tag st
             WHERE h.request LIKE st.pattern)                                              AS hist_requests_tagged,
           (SELECT count(*) FROM history h JOIN sn_unsafe_hist_comments u ON u.id = h.id, sn_tag st
             WHERE h.comment LIKE st.pattern)                                              AS hist_comments_tagged,
           (SELECT count(*) FROM money_transfer mt JOIN sn_target_docs t ON t.id = mt.document_id, sn_tag st
             WHERE mt.comment LIKE st.pattern)                                             AS mt_comments_tagged,
           (SELECT count(*) FROM mail m JOIN sn_unsafe_mails u ON u.id = m.id, sn_tag st
             WHERE m.content LIKE st.pattern)                                              AS mail_letterless_tagged,
           (SELECT count(*) FROM sn_kept_mails)                                            AS letter_mails_kept,
           (SELECT count(*) FROM chat_message cm JOIN sn_target_convs c ON c.id = cm.conversation_id, sn_tag st
             WHERE cm.content LIKE st.pattern)                                             AS chat_tagged
      INTO r;
    RETURN QUERY VALUES
        ('verify', 'doc_left',               r.doc_left::text),
        ('verify', 'special_needs_tagged',   r.special_needs_tagged::text),
        ('verify', 'doc_comments_tagged',    r.doc_comments_tagged::text),
        ('verify', 'doc_requests_tagged',    r.doc_requests_tagged::text),
        ('verify', 'hist_requests_tagged',   r.hist_requests_tagged::text),
        ('verify', 'hist_comments_tagged',   r.hist_comments_tagged::text),
        ('verify', 'mt_comments_tagged',     r.mt_comments_tagged::text),
        ('verify', 'mail_letterless_tagged', r.mail_letterless_tagged::text),
        ('verify', 'letter_mails_kept',      r.letter_mails_kept::text),
        ('verify', 'chat_tagged',            r.chat_tagged::text),
        ('verify', 'enqueued_left',          v_enqueued_left::text);

    RAISE NOTICE 'erasure OK — event %: unsafe records replaced, kept records byte-identical, everything else untouched. NOTHING IS COMMITTED YET: read the grid, then COMMIT (real) or ROLLBACK (dry run).', p_event_id;
    RETURN QUERY VALUES
        ('status', 'result', 'erasure OK — uncommitted; COMMIT to apply, ROLLBACK for a free dry run');
END
$erase$;

-- A standing destructive routine: nobody gets it by default. The owner (the
-- migration/admin role that boots the server) keeps EXECUTE implicitly — that role is
-- the intended caller; grant explicitly and sparingly if another admin role needs it,
-- via a migration, never ad hoc.
REVOKE ALL ON FUNCTION public.erase_past_event(integer, boolean) FROM PUBLIC;

COMMENT ON FUNCTION public.erase_past_event(integer, boolean) IS
'GDPR per-event erasure (V0077): replaces unsafe free text of one past event''s bookings with [personal data removed (GDPR) on YYYY-MM-DD], keeping letter mails and the history action trail byte-identical. Requires an explicit REPEATABLE READ transaction and SET LOCAL kbs.erase_confirm = ''ERASE EVENT <id>''. Never commits — the caller reads the returned grid, then COMMIT or ROLLBACK. Driver + docs: aggregate repo, scripts/erase-past-event-special-needs.sql and docs/operations/gdpr-past-event-erasure-reference.md.';
