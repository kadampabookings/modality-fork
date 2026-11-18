-- V0169: remove the sensitive details of people who LEFT a continuous event — erase_departed_bookings().
--
-- WHY. A continuous event (event_type.ongoing: stays, volunteering, retreats run as one long event) never
-- ends the way erase_past_event() (V0077) assumes, so its bookings were never reached: health and
-- accessibility notes of guests who left years ago stayed for as long as the database exists (GDPR Art. 5(1)(e),
-- Art. 9). Each stay is now kept for the period the Data Protection Officer sets on the event
-- (retention_policy, V0168 — never inherited for a continuous event), counted from that person's DEPARTURE,
-- then has its free text removed exactly as a finished event's is.
--
-- WHAT THIS INSTALLS, four functions, all owner-only (EXECUTE revoked from PUBLIC):
--   * event_departed_bookings(event, departed_before) — the departure rule, read-only, used by the core and by
--     the back office's counts. DEPARTURE of a booking = the last attendance day on its non-cancelled lines;
--     else the last attendance day on any of its lines (everything cancelled: the stay it had booked); else, for
--     a cancelled booking, its cancellation date; else none — a booking with no attendance and no cancellation
--     is never targeted (it has not happened, or cannot be dated). Departed = departure < departed_before.
--   * erase_bookings_core(event, departed_before, include_enqueued) — V0077's body, generated from V0077's own
--     text with four substitutions only (see the generator note below): the target set (every booking of the
--     event, or the departed ones), conversations (the event's own conversations only for a whole-event run),
--     the NOTICE labels, and three 'scope' grid rows for a departed run. Everything else — the tag, the
--     letterless rules, the kept-records digests, the outside-the-set proof, the assertions — is V0077's,
--     byte for byte, so a departed run carries the same guarantees: what is outside the set (the event's
--     guests still staying, or who left within the period) is proven untouched before the function returns.
--     It repeats the interlocks itself, so it is never a way around them.
--   * erase_past_event(event, include_enqueued) — same signature, same guards, same grid; now a wrapper of the
--     core, plus ONE new refusal: a continuous event (use erase_departed_bookings). The back office already
--     refused those; the database now does too.
--   * erase_departed_bookings(event, departed_before) — the new entry point, with V0077's four interlocks:
--     statement of intent SET LOCAL kbs.erase_confirm = 'ERASE DEPARTED <id> <YYYY-MM-DD>' (the cutoff is part
--     of the phrase: what is confirmed is exactly who goes), REPEATABLE READ, a later statement of an explicit
--     transaction, EXECUTE revoked from PUBLIC. It refuses an event that is not continuous, and a cutoff after
--     today (it would reach people staying now). Never commits.
--
-- GENERATED IN PART. The core's DECLARE block and body, and the wrapper's guards, were copied from
-- V0077__erase_past_event_function.sql by a one-off script that asserted each substitution (the four listed
-- above, plus the continuous-event refusal in the wrapper), then rehearsed on postgres:17 against V0077 itself:
-- same grid and same rows written, for a finished event (aggregate repo,
-- scripts/event-erasure-departed-rehearsal.sql).
--
-- SUCCESSION (V0077's rules, still binding): erase_past_event keeps its signature, so CREATE OR REPLACE
-- replaces it — no overload; every REVOKE is repeated; the KEEP-IN-SYNC copies in the aggregate's
-- scripts/probe-past-event-freetext.sql still hold (tag prefix, bracket-pattern regex, letterless rules are
-- unchanged) and now name erase_bookings_core as where V0077's rules live.
--
-- SHIPPED = FROZEN. Never edit once applied: a change is a new version.

-- ===== 1. The departure rule =================================================================================

CREATE OR REPLACE FUNCTION public.event_departed_bookings(p_event_id integer, p_departed_before date)
RETURNS TABLE(document_id integer, departed_on date)
LANGUAGE sql
STABLE
AS $departed$
    SELECT d.id, dep.departed_on
      FROM document d
     CROSS JOIN LATERAL (
           SELECT coalesce(
                    (SELECT max(a.date) FROM document_line dl JOIN attendance a ON a.document_line_id = dl.id
                      WHERE dl.document_id = d.id AND NOT dl.cancelled),
                    (SELECT max(a.date) FROM document_line dl JOIN attendance a ON a.document_line_id = dl.id
                      WHERE dl.document_id = d.id),
                    CASE WHEN d.cancelled THEN d.cancellation_date::date END) AS departed_on) dep
     WHERE d.event_id = p_event_id
       AND dep.departed_on < p_departed_before
$departed$;

REVOKE ALL ON FUNCTION public.event_departed_bookings(integer, date) FROM PUBLIC;

COMMENT ON FUNCTION public.event_departed_bookings(integer, date) IS
'V0169: the bookings of an event whose person LEFT before the given date. Departure = last attendance day on the booking''s non-cancelled lines, else on any of its lines, else its cancellation date when cancelled; a booking with none of these is never returned. Read-only.';

-- ===== 2. The core: V0077's body, for a whole event or for its departed bookings ===============================

CREATE OR REPLACE FUNCTION public.erase_bookings_core(p_event_id integer,
                                                      p_departed_before date,
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
    v_label text := CASE WHEN p_departed_before IS NULL THEN 'erase_past_event' ELSE 'erase_departed_bookings' END;
BEGIN
    v_tag := v_tag_prefix || ' on ' || current_date || ']';

    -- ================= INTERLOCKS (repeated: the core is never a way around them) =================
    IF p_event_id IS NULL THEN
        RAISE EXCEPTION 'erase_bookings_core: the event id is NULL';
    END IF;
    IF current_setting('transaction_isolation') NOT IN ('repeatable read', 'serializable') THEN
        RAISE EXCEPTION 'erase_bookings_core must run in a REPEATABLE READ transaction (the before/after proof needs one pinned snapshot)';
    END IF;
    IF transaction_timestamp() = statement_timestamp() THEN
        RAISE EXCEPTION 'erase_bookings_core refuses to run as a single autocommit statement — it would commit with no review';
    END IF;
    IF v_confirm IS DISTINCT FROM (CASE WHEN p_departed_before IS NULL THEN 'ERASE EVENT ' || p_event_id
                                        ELSE 'ERASE DEPARTED ' || p_event_id || ' ' || to_char(p_departed_before, 'YYYY-MM-DD') END) THEN
        RAISE EXCEPTION 'erase_bookings_core: the statement of intent does not match this run (found: %)', coalesce(v_confirm, '<unset>');
    END IF;
    SELECT e.id, e.name, e.start_date, e.end_date, e.organization_id, e.send_history_emails
      INTO ev FROM event e WHERE e.id = p_event_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'event % does not exist', p_event_id;
    END IF;
    -- A whole-event run never reaches a continuous event's bookings: they are its current guests too.
    IF p_departed_before IS NULL
       AND EXISTS (SELECT 1 FROM event e JOIN event_type t ON t.id = e.type_id WHERE e.id = p_event_id AND t.ongoing) THEN
        RAISE EXCEPTION 'event % (%) is a continuous event — its bookings are removed per stay, by departure: use erase_departed_bookings()',
                        ev.id, ev.name;
    END IF;
    IF p_departed_before IS NOT NULL AND p_departed_before > current_date THEN
        RAISE EXCEPTION 'erase_bookings_core: the cutoff % is after today — it would reach people still staying', p_departed_before;
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
    RAISE NOTICE '%: db=%, event % (%, % .. %), % bookings, tag=%',
                 v_label, current_database(), ev.id, ev.name, ev.start_date, ev.end_date, v_bookings, v_tag;

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
    IF p_departed_before IS NULL THEN
        INSERT INTO sn_target_docs (id)
        SELECT d.id FROM document d JOIN sn_event p ON p.event_id = d.event_id;
    ELSE
        -- V0169: only the bookings of people who LEFT before the cutoff (event_departed_bookings); the event's
        -- other bookings are outside the set, so the before/after proof below covers them like any other event's.
        INSERT INTO sn_target_docs (id)
        SELECT b.document_id FROM event_departed_bookings(p_event_id, p_departed_before) b;
    END IF;
    ANALYZE sn_target_docs;

    IF p_departed_before IS NOT NULL THEN
        SELECT count(*) INTO n FROM sn_target_docs;
        RETURN QUERY VALUES
            ('scope', 'departed_before',   to_char(p_departed_before, 'YYYY-MM-DD')),
            ('scope', 'departed_bookings', n::text),
            ('scope', 'bookings_kept',     (v_bookings - n)::text);
    END IF;

    -- Conversations reachable from the event — by booking OR by the event itself.
    CREATE TEMP TABLE sn_target_convs (id integer PRIMARY KEY);
    INSERT INTO sn_target_convs (id)
    SELECT cv.id FROM conversation cv
     WHERE EXISTS (SELECT 1 FROM sn_target_docs t WHERE t.id = cv.document_id)
        OR (p_departed_before IS NULL AND cv.event_id = (SELECT se.event_id FROM sn_event se));
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

    RAISE NOTICE 'erasure OK — event % (%): unsafe records replaced, kept records byte-identical, everything else untouched. NOTHING IS COMMITTED YET: read the grid, then COMMIT (real) or ROLLBACK (dry run).', p_event_id,
                 coalesce('departed before ' || to_char(p_departed_before, 'YYYY-MM-DD'), 'every booking');
    RETURN QUERY VALUES
        ('status', 'result', 'erasure OK — uncommitted; COMMIT to apply, ROLLBACK for a free dry run');
END
$erase$;

REVOKE ALL ON FUNCTION public.erase_bookings_core(integer, date, boolean) FROM PUBLIC;

COMMENT ON FUNCTION public.erase_bookings_core(integer, date, boolean) IS
'V0169: V0077''s erasure body, shared by erase_past_event (departed_before NULL: every booking of a finished event) and erase_departed_bookings (the bookings whose person left before the date). Repeats the interlocks; call it through those two, never directly.';

-- ===== 3. erase_past_event: same signature, now a wrapper ======================================================

CREATE OR REPLACE FUNCTION public.erase_past_event(p_event_id integer,
                                                   p_include_enqueued boolean DEFAULT false)
RETURNS TABLE(step text, metric text, value text)
LANGUAGE plpgsql
SET timezone TO 'Europe/London'
AS $erase$
DECLARE
    v_confirm    text := nullif(current_setting('kbs.erase_confirm', true), '');
    ev  record;
BEGIN
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
    -- V0169: a continuous event never ends the way this assumes — erasing "the event" would reach the guests
    -- staying now. Its bookings go per stay, by departure: erase_departed_bookings().
    IF EXISTS (SELECT 1 FROM event e JOIN event_type t ON t.id = e.type_id WHERE e.id = p_event_id AND t.ongoing) THEN
        RAISE EXCEPTION 'event % (%) is a continuous event — its bookings are removed per stay, by departure: use erase_departed_bookings()',
                        ev.id, ev.name;
    END IF;
    IF ev.end_date > (current_date - interval '3 months')::date THEN
        RAISE NOTICE 'event % (%) ended less than 3 months ago (end_date %) — the retention line is 3 months after event close; continue only if this early run is deliberate',
                     ev.id, ev.name, ev.end_date;
    END IF;
    RETURN QUERY SELECT * FROM erase_bookings_core(p_event_id, NULL, p_include_enqueued);
END
$erase$;

REVOKE ALL ON FUNCTION public.erase_past_event(integer, boolean) FROM PUBLIC;

COMMENT ON FUNCTION public.erase_past_event(integer, boolean) IS
'GDPR per-event erasure (V0077; since V0169 a wrapper of erase_bookings_core): replaces unsafe free text of one past event''s bookings with [personal data removed (GDPR) on YYYY-MM-DD], keeping letter mails and the history action trail byte-identical. Refuses a continuous event (use erase_departed_bookings). Requires an explicit REPEATABLE READ transaction and SET LOCAL kbs.erase_confirm = ''ERASE EVENT <id>''. Never commits — the caller reads the returned grid, then COMMIT or ROLLBACK. Driver + docs: aggregate repo, scripts/erase-past-event-special-needs.sql and docs/operations/gdpr-past-event-erasure-reference.md.';

-- ===== 4. erase_departed_bookings: a continuous event's departed stays =========================================

CREATE OR REPLACE FUNCTION public.erase_departed_bookings(p_event_id integer, p_departed_before date)
RETURNS TABLE(step text, metric text, value text)
LANGUAGE plpgsql
SET timezone TO 'Europe/London'
AS $erase$
DECLARE
    v_confirm text := nullif(current_setting('kbs.erase_confirm', true), '');
    v_phrase  text;
    ev record;
BEGIN
    IF p_event_id IS NULL OR p_departed_before IS NULL THEN
        RAISE EXCEPTION 'erase_departed_bookings: the event id and the departure cutoff are both required';
    END IF;
    v_phrase := 'ERASE DEPARTED ' || p_event_id || ' ' || to_char(p_departed_before, 'YYYY-MM-DD');

    IF current_setting('transaction_isolation') NOT IN ('repeatable read', 'serializable') THEN
        RAISE EXCEPTION 'erase_departed_bookings must run in a REPEATABLE READ transaction — start with: BEGIN; SET TRANSACTION ISOLATION LEVEL REPEATABLE READ; (the before/after proof needs one pinned snapshot)';
    END IF;
    IF transaction_timestamp() = statement_timestamp() THEN
        RAISE EXCEPTION 'erase_departed_bookings refuses to run as a single autocommit statement — it would commit with no review. BEGIN; SET TRANSACTION ISOLATION LEVEL REPEATABLE READ; SET LOCAL kbs.erase_confirm = ...; SELECT * FROM erase_departed_bookings(...); then COMMIT or ROLLBACK, each statement sent separately';
    END IF;
    IF v_confirm IS DISTINCT FROM v_phrase THEN
        RAISE EXCEPTION 'erase_departed_bookings: missing statement of intent — run  SET LOCAL kbs.erase_confirm = ''%'';  in this transaction first (found: %)',
                        v_phrase, coalesce(v_confirm, '<unset>');
    END IF;

    SELECT e.id, e.name, coalesce(t.ongoing, false) AS ongoing
      INTO ev FROM event e LEFT JOIN event_type t ON t.id = e.type_id WHERE e.id = p_event_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'event % does not exist', p_event_id;
    END IF;
    IF NOT ev.ongoing THEN
        RAISE EXCEPTION 'event % (%) is not a continuous event — a finished event is removed whole: use erase_past_event()', ev.id, ev.name;
    END IF;
    -- A cutoff after today would reach people who leave later than today: those staying now.
    IF p_departed_before > current_date THEN
        RAISE EXCEPTION 'erase_departed_bookings: the cutoff % is after today — it would reach people still staying', p_departed_before;
    END IF;

    RETURN QUERY SELECT * FROM erase_bookings_core(p_event_id, p_departed_before, false);
END
$erase$;

REVOKE ALL ON FUNCTION public.erase_departed_bookings(integer, date) FROM PUBLIC;

COMMENT ON FUNCTION public.erase_departed_bookings(integer, date) IS
'V0169: GDPR removal of the sensitive details of a continuous event''s bookings whose person left before the cutoff (today minus the event''s retention period): the same free text, tag and proofs as erase_past_event, for those bookings only. Requires an explicit REPEATABLE READ transaction and SET LOCAL kbs.erase_confirm = ''ERASE DEPARTED <id> <YYYY-MM-DD>''. Never commits.';
