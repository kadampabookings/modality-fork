-- Passkey (WebAuthn) credentials for frontend_account logins. One row per registered passkey
-- records the credential's PUBLIC key material — nothing here can sign anything, so a dump is
-- harmless — plus the metadata the management UI shows (label, transports, aaguid, timestamps).
--
-- public_key_cose holds the webauthn4j AttestedCredentialData serialization (aaguid +
-- credential id + COSE public key), base64url text, round-tripped losslessly at assertion time.
-- user_handle is 32 random bytes (base64url), minted at first registration and shared by every
-- passkey of the same account: it is what the authenticator returns to identify the account in
-- the discoverable-credential login flow, and is deliberately random rather than derived from
-- any identity (WebAuthn requires it to carry no PII).
--
-- Deliberately NOT in the domain model (like bo_device/V0078 and person_account_move/V0063):
-- generic client DQL cannot reach it, so ownership is enforced solely by the webauthn gateway,
-- and no KBS2-side DomainModel snapshot regeneration is needed.
--
-- ON DELETE CASCADE is load-bearing twice over: account merges hard-DELETE the emptied
-- frontend_account row today (a plain FK would break them), and any future account-erasure
-- path must take the passkeys with it.

CREATE TABLE IF NOT EXISTS public.webauthn_credential (
    id                  bigserial    PRIMARY KEY,
    frontend_account_id integer      NOT NULL REFERENCES public.frontend_account(id) ON DELETE CASCADE,
    credential_id       text         NOT NULL,
    public_key_cose     text         NOT NULL,
    sign_count          bigint       NOT NULL DEFAULT 0,
    user_handle         text         NOT NULL,
    transports          varchar(128),
    aaguid              varchar(36),
    label               varchar(64),
    created_at          timestamp    NOT NULL DEFAULT now(),
    last_used_at        timestamp,
    CONSTRAINT webauthn_credential_credential_id UNIQUE (credential_id)
);

CREATE INDEX IF NOT EXISTS webauthn_credential_account_idx ON public.webauthn_credential (frontend_account_id);

-- Ownership: a table created by the migration's connect role is unwritable by the other app roles
-- — the "permission denied" trap V0064 exists to repair. Hand the table AND its sequence to
-- whoever owns public.person, so every app role writes it like any other (V0078 pattern).
DO $$
DECLARE app_owner name;
BEGIN
    SELECT pg_get_userbyid(c.relowner) INTO app_owner
      FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
     WHERE n.nspname = 'public' AND c.relname = 'person';

    IF app_owner IS NOT NULL THEN
        EXECUTE format('ALTER TABLE public.webauthn_credential OWNER TO %I', app_owner);
        EXECUTE format('ALTER SEQUENCE public.webauthn_credential_id_seq OWNER TO %I', app_owner);
    END IF;
END $$;
