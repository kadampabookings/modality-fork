-- V0158: account_settings — the server half of the front-office user settings.
--
-- Settings follow a person across devices once they have an account; before that they live in the
-- browser's localStorage only. First use: the experimental menus (/news, /podcasts, /books) that a
-- hidden /user-settings page switches on. Plan: docs/user-settings-plan.md in the aggregate repo.
--
-- WHY A SIDE TABLE AND NOT A COLUMN ON frontend_account. That table holds the password hash, salt and
-- reset token, and is the most tightly guarded table in the read/write authorization rules. A settings
-- column would give the front office a routine reason to read and write it. A 1:1 table keeps
-- credentials out of the settings path entirely.
--
-- WHY IT IS NOT IN THE DOMAIN MODEL. Same choice as account_sign_in_restriction (V0096),
-- webauthn_credential (V0088) and totp_credential (V0091): generic client DQL cannot name this table,
-- so it needs no read or write deny rule. The only way in is the userSettings get/patch bus endpoints,
-- which take the account from the session and never from the client.
--
-- WHY ONE jsonb DOCUMENT. Settings are small flags that come and go (an experimental menu this month,
-- gone next quarter); a migration per flag would be overkill. Keys are flat and dotted
-- ("experimental.news") so the endpoint can merge a patch atomically with `||`: two devices changing
-- different keys at the same moment both keep their change. The endpoint admits only booleans,
-- integers and short tokens as values, which keeps free text — and with it personal data — out.
--
-- The two CHECKs are the last line of defence behind that validation: the document is always a JSON
-- object, and it stays small. 8 KB is far above what flags need and far below anything worth storing
-- here by mistake.
--
-- ON DELETE CASCADE is load-bearing, as in V0091 and V0096: account merges hard-DELETE the emptied
-- frontend_account row, and a plain FK would break them. The merged-away account's settings are lost
-- with it, which was accepted for UI flags (2026-11-10).
--
-- No index beyond the primary key: every read and write names exactly one account.
--
-- Idempotent throughout. No BEGIN/COMMIT: the runner executes one statement at a time.

create table if not exists public.account_settings (
    account_id integer     primary key references frontend_account(id) on delete cascade,
    settings   jsonb       not null default '{}'::jsonb,
    updated_at timestamptz not null default now(),
    constraint account_settings_is_object check (jsonb_typeof(settings) = 'object'),
    constraint account_settings_size      check (octet_length(settings::text) <= 8192)
);

comment on table public.account_settings is
    'Per-account front-office settings (UI flags), one jsonb document per account. Reached only through the userSettings bus endpoints; kept out of the domain model on purpose (V0158).';

-- Ownership: a table created by the migration's connect role is unwritable by the other app roles —
-- the "permission denied" trap V0064 repaired and V0090 made a standing instruction. Hand the table to
-- whoever owns public.person, so every app role reaches it like any other table. There is no sequence:
-- the primary key is the account id. V0090's generic sweep is recorded as applied and will not re-run
-- for a table created after it.
DO $$
DECLARE app_owner name;
BEGIN
    SELECT pg_get_userbyid(c.relowner) INTO app_owner
      FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
     WHERE n.nspname = 'public' AND c.relname = 'person';

    IF app_owner IS NOT NULL THEN
        EXECUTE format('ALTER TABLE public.account_settings OWNER TO %I', app_owner);
    END IF;
END $$;
