-- V0064: give objects created by migrations the same owner as the rest of the schema.
--
-- Every table in this schema is owned by `kbs`, and the application roles (kbs_server,
-- kbs3_*_server, the dev and DBeaver logins) reach them as MEMBERS of that role rather
-- than through explicit grants — relacl is null on all 168 tables. But a migration runs
-- as whatever role the server connects with, so anything it CREATEs is owned by that role
-- instead, and is then unreachable by every other role. dev_issue (V0060) hit this and was
-- repaired by hand on prod with `ALTER TABLE public.dev_issue OWNER TO kbs`.
--
-- V0063's person_account_move made the consequence severe rather than merely awkward,
-- because its audit INSERT happens inside a trigger on person: the mis-owned table did not
-- just hide itself, it made every account move fail for every role except the migration's:
--
--     ERROR: permission denied for table person_account_move
--     CONTEXT: PL/pgSQL function trigger_person_audit_account_move() line 6
--
-- KBS2 (kbs_server) writes to person, so account moves would have broken there too.
-- Verified on a staging copy of prod, 2026-08-10.
--
-- This realigns EVERY table, sequence, view and materialised view in `public` whose owner
-- differs from the owner of `person` — repairing person_account_move now and anything a
-- previous migration left behind. On prod, V0063 and V0064 apply inside the same batch
-- transaction, so the table is created and realigned before anything can touch it.
--
-- The reference is taken from `person` rather than naming `kbs`, so this stays correct
-- wherever the schema is owned differently, and is a no-op where the server already
-- connects as the schema owner.
--
-- It warns instead of failing: an ownership tidy-up must never abort the boot migration
-- chain, and the trigger it fixes must never be the reason a legitimate write fails.
--
-- NOTE — this repairs, it does not prevent. Every future migration that creates an object
-- reintroduces the problem. The durable fix belongs in DbMigrationRunner, which already
-- injects `SET LOCAL` statements into the migration batch: a configurable role would let
-- it add `SET LOCAL ROLE <schema owner>` so migration-created objects are owned correctly
-- from the start. Until then, new migrations that CREATE objects should re-run a block
-- like this one.

DO $$
DECLARE
    target_owner name;
    obj          record;
    realigned    int := 0;
    skipped      int := 0;
BEGIN
    SELECT pg_get_userbyid(relowner) INTO target_owner
      FROM pg_class WHERE oid = 'public.person'::regclass;

    FOR obj IN
        SELECT c.relname, c.relkind
          FROM pg_class c
          JOIN pg_namespace n ON n.oid = c.relnamespace
         WHERE n.nspname = 'public'
           AND c.relkind IN ('r', 'S', 'v', 'm')
           AND pg_get_userbyid(c.relowner) <> target_owner
           -- Serial/identity sequences are OWNED BY a table column and Postgres refuses to
           -- reassign them on their own ("cannot change owner of sequence", SQLSTATE 0A000)
           -- — their owner must match the table they are linked to, and ALTER TABLE moves
           -- them along with it. Reassigning the table is therefore both necessary and
           -- sufficient; naming the sequence separately is an error.
           AND NOT (c.relkind = 'S' AND EXISTS (
                    SELECT 1 FROM pg_depend d
                     WHERE d.classid = 'pg_class'::regclass AND d.objid = c.oid
                       AND d.refclassid = 'pg_class'::regclass AND d.deptype IN ('a', 'i')))
         -- Tables before views, so a view never outlives its table's ownership change.
         ORDER BY CASE c.relkind WHEN 'r' THEN 0 WHEN 'S' THEN 1 ELSE 2 END, c.relname
    LOOP
        -- Per-object exception handling, NOT one handler around the loop: this schema also
        -- carries tables created by maintenance scripts run from a psql login
        -- (member_email_nullify_backup), which the migration role cannot reassign either.
        -- With a single outer handler the first such object ends the loop, and since it
        -- sorts before person_account_move the audit table would silently stay broken.
        BEGIN
            EXECUTE format('ALTER %s public.%I OWNER TO %I',
                           CASE obj.relkind
                               WHEN 'S' THEN 'SEQUENCE'
                               WHEN 'v' THEN 'VIEW'
                               WHEN 'm' THEN 'MATERIALIZED VIEW'
                               ELSE 'TABLE'
                           END,
                           obj.relname, target_owner);
            realigned := realigned + 1;
            RAISE NOTICE 'V0064: % realigned to %', obj.relname, target_owner;
        EXCEPTION
            -- Deliberately WHEN OTHERS. A tidy-up must never abort the boot migration
            -- chain, and narrowing this to insufficient_privilege was not enough: the
            -- first version died on SQLSTATE 0A000 from a linked sequence and the batch
            -- was retried on every boot, blocking this and every later migration.
            WHEN OTHERS THEN
                skipped := skipped + 1;
                RAISE WARNING 'V0064: could not realign % (% / %) — roles other than its owner cannot use it', obj.relname, SQLSTATE, SQLERRM;
        END;
    END LOOP;

    IF realigned = 0 AND skipped = 0 THEN
        RAISE NOTICE 'V0064: every object in public is already owned by % — nothing to do', target_owner;
    ELSE
        RAISE NOTICE 'V0064: % object(s) realigned to %, % skipped', realigned, target_owner, skipped;
    END IF;
END $$;
