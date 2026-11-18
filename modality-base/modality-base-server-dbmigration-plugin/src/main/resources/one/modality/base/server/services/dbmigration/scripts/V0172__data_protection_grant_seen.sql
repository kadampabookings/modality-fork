-- V0172: data_protection_grant_seen — the DataProtection grants the server has already alerted about.
--
-- WHY. "Even a super administrator cannot act as the Data Protection Officer" holds only as "cannot without a visible
-- step": a platform super administrator can still assign the role, to somebody else or to themself, and so can anyone
-- who writes the database directly (KBS2, a hand-run script). Nothing told anybody. Now every new holder of
-- DataProtection is mailed to the platform super administrators and to the holder, and logged on a fixed line for a
-- CloudWatch filter (admin-tiers plan, D5).
--
-- HOW. The server compares, every two minutes, the people who hold DataProtection through an access row with the rows
-- of this table, and alerts on the difference. It compares the RESULT, not the writes, so every road to the grant is
-- seen: a new access row, an access row moved to another person or organization, the operation added to an existing
-- role (directly or through its group), or a write the application never saw. A row that no longer holds is deleted,
-- so a grant removed and given again is alerted again. The insert is ON CONFLICT DO NOTHING on the unique index below:
-- with several servers running, exactly one claims a new grant and sends its alert.
--
-- WHAT. One row per (access row, person, organization, event, event type) that holds DataProtection. Ids and the time
-- it was first seen — nothing else. No foreign key to person: creating one takes a lock on person that a busy
-- production can refuse within the migration's 5 s lock timeout. A row naming a person always names a current access
-- row of that person (the access row's foreign key below removes it with the row, and the comparison removes it within
-- two minutes if the row is moved), and a person merge already refuses a person who holds an access row;
-- PersonReferences lists the column all the same. Anonymiser: '*' not-pii, ids only.
--
-- SEEDED with today's holders, so the deploy alerts nobody: the alert is about grants given from now on.
--
-- DDL: a new table and its index (nothing else is locked but authorization_organization_user_access, a table of a few
-- dozen rows, for the foreign key). NULLS NOT DISTINCT needs PostgreSQL 15+ (production and staging run 17). Safe to
-- re-run.

CREATE TABLE IF NOT EXISTS public.data_protection_grant_seen (
    id              serial PRIMARY KEY,
    access_id       integer NOT NULL
                    REFERENCES public.authorization_organization_user_access (id) ON DELETE CASCADE,
    user_id         integer NOT NULL,
    organization_id integer,
    event_id        integer,
    event_type_id   integer,
    first_seen_at   timestamp with time zone NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS data_protection_grant_seen_holder_uq
    ON public.data_protection_grant_seen (access_id, user_id, organization_id, event_id, event_type_id) NULLS NOT DISTINCT;

-- Ownership, as V0146: hand the new table (and its serial sequence) to whoever owns public.person, so a pg_dump run as
-- that role (backups, the staging refresh) is not refused on it.
DO $$
DECLARE
    app_owner name;
BEGIN
    SELECT pg_get_userbyid(c.relowner) INTO app_owner
      FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
     WHERE n.nspname = 'public' AND c.relname = 'person';

    IF app_owner IS NOT NULL THEN
        BEGIN
            EXECUTE format('ALTER TABLE public.data_protection_grant_seen OWNER TO %I', app_owner);
        EXCEPTION WHEN OTHERS THEN
            RAISE WARNING 'V0172: could not realign the owner of public.data_protection_grant_seen to % (%)', app_owner, SQLERRM;
        END;
    END IF;
END $$;

COMMENT ON TABLE public.data_protection_grant_seen IS
    'DataProtection grants already alerted about (V0172): the server mails the platform super administrators and the holder for every holder not in this table, then adds it.';

-- Who holds DataProtection today — the same question the server asks (DataProtectionGrantAlert.HOLDERS_SQL): an access
-- row whose role grants the operation directly or through its group; a read-only access only if the operation is.
INSERT INTO public.data_protection_grant_seen (access_id, user_id, organization_id, event_id, event_type_id)
SELECT a.id, a.user_id, a.organization_id, a.event_id, a.event_type_id
  FROM public.authorization_organization_user_access a
  JOIN public.operation op ON op.operation_code = 'DataProtection'
 WHERE (NOT a.read_only OR op.read_only)
   AND EXISTS (SELECT 1 FROM public.authorization_role_operation ro
                WHERE ro.role_id = a.role_id AND (ro.operation_id = op.id OR ro.operation_group_id = op.group_id))
ON CONFLICT DO NOTHING;
