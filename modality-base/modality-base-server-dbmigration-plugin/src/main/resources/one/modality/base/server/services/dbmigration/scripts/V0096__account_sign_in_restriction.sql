-- V0096: "Stop my password working" — the Security page's calm answer to a password that may be known.
--
-- The everyday incident is not a stolen laptop, it is a password somebody typed into a convincing
-- fake or reused on a site that has since been breached. Until now the only answers were changing it
-- (which does nothing if the thief changes it first) or disabling the whole account (which locks its
-- owner out and needs a super administrator to undo). This is the one in between: the password stops
-- working, the passkey still does, and nobody is locked out.
--
-- WHY A TABLE AND NOT A COLUMN ON frontend_account. That table is in the domain model generated from
-- the KBS2 HSQLDB script, so a column there means a DomainModel regeneration and a KBS2-side change
-- for a KBS3-only feature. Same reasoning, and the same shape, as webauthn_credential (V0088),
-- totp_credential (V0091) and bo_device (V0078): generic client DQL cannot reach this table, so
-- ownership is enforced solely by the gateway code, every statement of which names the single account
-- it is allowed to touch.
--
-- WHY NOT frontend_account.disabled, WHICH ALREADY EXISTS. Because it is the other control. `disabled`
-- is honoured by every gateway — password, passkey and magic link alike — so it is exactly what the
-- panic button needs and exactly what this must NOT do: a restriction that took the passkey with it
-- would leave its owner with no way back in, which is the cost this control exists to avoid. The two
-- compose the way you would expect: `disabled` is total, a row here is narrow, and a disabled account
-- is refused whatever this table says.
--
-- WHAT "password" WILL MEAN HERE, AND IT IS WIDER THAN LOGGING IN. A restriction is to close password
-- sign-in AND password recovery. Leaving recovery open would make the control mostly decorative: a
-- thief who cannot sign in with the password simply asks for a reset. That does need the mailbox as
-- well — so it is a real step up either way — but the control says "stop my password working", and a
-- password that can be replaced from an email has not stopped working. Decided with David, 2026-09-18.
--
-- AS OF THIS SCRIPT ONLY THE SIGN-IN HALF IS ENFORCED: the single reader is the password gateway. The
-- recovery half, the writer, and the passkey precondition below are the next increments of the same
-- arc, and until they land this table has no writer and is inert. Stated in the future tense on
-- purpose — see V0091's dormant `status` column for the same convention.
--
-- THE CONSEQUENCE OF THAT DECISION, WHICH THE WRITER MUST HONOUR WHEN IT ARRIVES: with recovery closed,
-- the only ways back are a passkey or a super administrator. So the control may only be offered to an
-- account that HAS a usable passkey, and that precondition belongs server-side, beside the writer —
-- not hidden in the client, and not in this table, which records decisions rather than vetting them.
--
-- METHOD is kept as a column rather than assumed, with a CHECK that admits one value today — the same
-- choice V0091 made for totp_credential.status. If a second sign-in route ever needs closing on its
-- own, that is a plugin change and a widened CHECK rather than a migration against a boolean.
--
-- LIFTED RATHER THAN DELETED: a security control that leaves no trace of having been used is worth
-- less than one that does. A row is the restriction; `lifted_at` non-null is its history. The partial
-- unique index below is what keeps "in force" single-valued.
--
-- ON DELETE CASCADE is load-bearing, for the reason V0091 records: account merges hard-DELETE the
-- emptied frontend_account row, and a plain FK would break them.
--
-- Idempotent throughout. No BEGIN/COMMIT: the runner executes one statement at a time.

create table if not exists public.account_sign_in_restriction (
    id                   serial       primary key,
    frontend_account_id  integer      not null references frontend_account(id) on delete cascade,
    -- Which way in is closed. 'password' also closes password recovery — see the header.
    method               varchar(32)  not null,
    created_at           timestamptz  not null default now(),
    -- Who closed it: the owner for a self-service restriction, a super administrator where one acted
    -- on somebody's behalf. Nullable because a row created by a future automated path has no person.
    -- NO FK to person, following V0089 and V0091, which each recorded the reason twice over: it would
    -- take SHARE ROW EXCLUSIVE on the busiest table in the database at boot, inside the single
    -- transaction the migration runner uses, for an audit pointer this whole-table-truncated row does
    -- not need kept consistent — and a decision by a since-erased person should survive as "decided"
    -- rather than block that person's erasure.
    created_by_person_id integer,
    -- Null while the restriction is in force. Set, never deleted, so the table records what was done.
    lifted_at            timestamptz,
    lifted_by_person_id  integer,
    -- The owner's own words, optional. Never logged. Capped like its neighbours
    -- (second_factor_reset.note, totp_credential.label): this is a sentence of context, and an
    -- unbounded client-supplied text column is a place to put something that is not one.
    note                 varchar(256)
);

do $$
begin
    -- Qualified by the table as well as the name: a same-named constraint anywhere else in the
    -- database would otherwise make this guard skip a constraint that was never added.
    if not exists (select 1 from pg_constraint
                    where conname = 'account_sign_in_restriction_method_check'
                      and conrelid = 'public.account_sign_in_restriction'::regclass) then
        alter table public.account_sign_in_restriction
            add constraint account_sign_in_restriction_method_check check (method in ('password'));
    end if;
end $$;

-- One live restriction per account per method, and the read on the login path both. Without the
-- uniqueness, two clicks a second apart leave two rows in force and lifting one would look like it had
-- worked. Carrying `method` as well as the account is what lets "is this account's password closed?"
-- be answered index-only, which is what makes it affordable on every password sign-in.
--
-- Deliberately the ONLY index here. A second one on (frontend_account_id) alone would be a strict
-- prefix of this with the same predicate, so the planner would prefer it for being smaller and then
-- have to visit the heap to check `method` — a narrower index that makes the query slower.
create unique index if not exists account_sign_in_restriction_live
    on public.account_sign_in_restriction (frontend_account_id, method)
    where lifted_at is null;

-- Ownership: a table created by the migration's connect role is unwritable by the other app roles —
-- the "permission denied" trap V0064 repaired and V0090 made a standing instruction after V0087
-- shipped without it. Hand the table AND its sequence to whoever owns public.person, so every app
-- role reaches them like any other table (V0078/V0088/V0091 pattern).
--
-- It matters more here than for most tables, because of how this one is read: the login path treats
-- an unreadable restriction as no restriction, deliberately, so that a blip cannot refuse everybody.
-- A mis-owned table therefore does not fail loudly — it makes the control silently do nothing while
-- telling its owner it is on. V0090's generic sweep is already recorded as applied and will not
-- re-run for a table created after it.
DO $$
DECLARE app_owner name;
BEGIN
    SELECT pg_get_userbyid(c.relowner) INTO app_owner
      FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
     WHERE n.nspname = 'public' AND c.relname = 'person';

    IF app_owner IS NOT NULL THEN
        EXECUTE format('ALTER TABLE public.account_sign_in_restriction OWNER TO %I', app_owner);
        EXECUTE format('ALTER SEQUENCE public.account_sign_in_restriction_id_seq OWNER TO %I', app_owner);
    END IF;
END $$;
