-- V0042: event 1883 (ITTP 2026) duplicated attendances — merge video flags, dedup.
--
-- 632 duplicated (document_line, date) attendance groups on the ITTP teaching
-- lines, from two KBS2-side incidents:
--
--   1. A bulk video-access grant on 2026-07-14 (13:40-19:30 UTC, no history
--      written): 210 bookings x the Tuesdays 6/30, 7/7, 7/14 got NEW rows
--      (present=t, charged=f, video_access_enabled=t) INSERTED instead of the
--      flag being set on the existing rows. charged=false => skipped by
--      compute_document_prices, so no price impact. In 362 groups the grant was
--      real (the original row lacked video access) — the flag must be copied to
--      the kept row before deleting, or the repair would silently revoke it.
--   2. Booking ref 87 (doc 312644): a KBS2 "Edited Teaching" on 6/30 double-added
--      7/16 and 7/17 with charged=true => the ITTP line priced 24 x £5/day
--      instead of 22 x £5 (£10 overcharge, paid; refund handled by the office).
--      The delete + the COMMIT-time deferred recompute corrects the price.
--
-- Repair, in order (targets fully derived — idempotent, no-op on an environment
-- already repaired manually; staging 2026-07-22: 362 flags copied, 632 rows
-- deleted, doc 312644 price_net 51740 -> 50740):
--   1. copy video_access_enabled=true onto the kept (lowest-id) row of any
--      duplicate group where any row has it;
--   2. delete every row but the lowest id per (document_line, date).
--
-- set_transaction_parameters(true): the attendance deletes cascade into the
-- deferred price-recompute/allocation triggers at COMMIT, which read the
-- transaction_parameter session temp table the KBS server normally provides.

DO $$ BEGIN PERFORM set_transaction_parameters(true); END $$;  -- DO/PERFORM: side-effect call, no result set

DO $$
DECLARE
    v_flags integer;
    v_del   integer;
BEGIN
    -- 1) Preserve granted video access on the kept row of each duplicate group
    WITH keepers AS (
        SELECT min(a.id) AS keep_id, bool_or(a.video_access_enabled) AS any_video
        FROM attendance a
        JOIN document_line dl ON dl.id = a.document_line_id
        JOIN document d ON d.id = dl.document_id
        WHERE d.event_id = 1883
        GROUP BY a.document_line_id, a.date
        HAVING count(*) > 1
    )
    UPDATE attendance a
    SET video_access_enabled = true
    FROM keepers k
    WHERE a.id = k.keep_id AND k.any_video AND NOT a.video_access_enabled;
    GET DIAGNOSTICS v_flags = ROW_COUNT;

    -- 2) Delete the surplus rows (keep the lowest id per line+date)
    DELETE FROM attendance
    WHERE id IN (
        SELECT id FROM (
            SELECT a.id,
                   row_number() OVER (PARTITION BY a.document_line_id, a.date ORDER BY a.id) AS rn
            FROM attendance a
            JOIN document_line dl ON dl.id = a.document_line_id
            JOIN document d ON d.id = dl.document_id
            WHERE d.event_id = 1883
        ) t
        WHERE rn > 1);
    GET DIAGNOSTICS v_del = ROW_COUNT;

    RAISE NOTICE 'V0042: copied video access to % kept rows, deleted % surplus ITTP attendances for event 1883', v_flags, v_del;
END $$;
