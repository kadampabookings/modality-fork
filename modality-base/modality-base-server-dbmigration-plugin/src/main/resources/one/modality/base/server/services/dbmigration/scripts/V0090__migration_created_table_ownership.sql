-- V0090: realign the ownership of objects that recent migrations created.
--
-- WHY THIS EXISTS AGAIN, twenty-six migrations after V0064. A migration runs as whatever role the
-- server connects with, so anything it CREATEs is owned by THAT role. Every application role
-- (kbs_server, kbs3_*_server, kbs3_staging_server_dev, the DBeaver login) reaches tables as a
-- MEMBER of the schema owner rather than through explicit grants — relacl is null across the
-- schema — so a migration-created table is simply unreachable for all of them:
--
--     ERROR: permission denied for table mate_invite_token (42501)
--
-- which is what V0087's mate_invite_token produced on a server connecting as
-- kbs3_staging_server_dev. V0083 avoided this by realigning its own table inline; V0087 did not.
--
-- V0087 CANNOT BE EDITED TO FIX IT. DbMigrationRunner records a checksum per applied version and
-- refuses to boot when a bundled script no longer matches the one that was applied ("has been
-- modified since it was applied to this database"). V0087 is already applied, so the repair has to
-- arrive as its own version. That is this script.
--
-- GENERIC RATHER THAN NAMING ONE TABLE. V0064 carries the full reasoning and ends with the standing
-- instruction that every migration creating an object must re-run a block like it; this is that
-- block, not a new idea. Written generically it is a no-op wherever owners already match, and it
-- also collects anything else a recent migration left behind — including the webauthn tables
-- (V0088/V0089) should they have been created by a connect role rather than the schema owner.
--
-- This GRANTS NOTHING NEW. It restores the schema's single-owner convention, which is what every
-- role's existing access already rests on; it cannot widen access beyond what membership of that
-- owner already implies.
--
-- It warns instead of failing: an ownership tidy-up must never abort the boot migration chain, and
-- must never be the reason a legitimate write fails.
--
-- ⚠ WHAT THIS CANNOT DO, AND WHY STAGING MAY NEED A HAND. `ALTER TABLE ... OWNER TO` requires the
-- role running it to OWN the object (membership of the target role is not enough). So this repairs
-- only what the migrating role can give away — which is the normal case, where the same role
-- created the object and still owns it. It is NOT the case on a database shared by two server
-- roles: staging's mate_invite_token was created by the deployed kbs3_staging_server, so a
-- developer's server connecting as kbs3_staging_server_dev cannot hand it over. It will warn,
-- change nothing — and this version is still recorded as applied, so it will not run again.
--
-- Therefore, where a shared database is already mis-owned, repair it once by hand as an
-- owner-capable role before relying on this script:
--
--     ALTER TABLE public.mate_invite_token OWNER TO kbs;
--
-- This script's job is to keep it from recurring in environments built from scratch (prod, a fresh
-- staging), where the creating role and the migrating role are the same.

DO $$
DECLARE
    target_owner name;
    obj          record;
    realigned    int := 0;
BEGIN
    -- Taken from `person` rather than hardcoding `kbs`, so this stays correct wherever the schema
    -- is owned differently, and is a no-op where the server already connects as the schema owner.
    SELECT pg_get_userbyid(relowner) INTO target_owner
      FROM pg_class WHERE oid = 'public.person'::regclass;

    IF target_owner IS NULL THEN
        RAISE WARNING 'V0090: could not read the schema owner from public.person; nothing realigned';
        RETURN;
    END IF;

    FOR obj IN
        SELECT c.relname, c.relkind
          FROM pg_class c
          JOIN pg_namespace n ON n.oid = c.relnamespace
         WHERE n.nspname = 'public'
           -- Tables, views and materialised views only. An identity/serial sequence is OWNED BY its
           -- table's column, and Postgres refuses to reassign one on its own ("cannot change owner
           -- of sequence", SQLSTATE 0A000) — ALTER TABLE carries it across with the table, so
           -- reassigning the table is both necessary and sufficient, and naming the sequence
           -- separately is an error.
           AND c.relkind IN ('r', 'v', 'm')
           AND pg_get_userbyid(c.relowner) <> target_owner
    LOOP
        BEGIN
            EXECUTE format(
                CASE obj.relkind
                    WHEN 'r' THEN 'ALTER TABLE public.%I OWNER TO %I'
                    WHEN 'v' THEN 'ALTER VIEW public.%I OWNER TO %I'
                    WHEN 'm' THEN 'ALTER MATERIALIZED VIEW public.%I OWNER TO %I'
                END, obj.relname, target_owner);
            realigned := realigned + 1;
            RAISE NOTICE 'V0090: realigned public.% to %', obj.relname, target_owner;
        EXCEPTION WHEN OTHERS THEN
            -- Most likely the migrating role does not own the object and cannot give it away. Say
            -- so and carry on: the boot chain matters more than the tidy-up.
            RAISE WARNING 'V0090: could not realign public.% to % (%)', obj.relname, target_owner, SQLERRM;
        END;
    END LOOP;

    RAISE NOTICE 'V0090: % object(s) realigned to owner %', realigned, target_owner;
END $$;
