-- Back-office device authentication (see docs/design/bo-signing-key-plan.md). One row per
-- (username, device) records a KBS2 back-office install's PUBLIC signing key and its approval
-- status, so the server can enrol a new device as PENDING and verify known ones against their
-- stored, admin-APPROVED key. Public keys only — nothing here is secret, so a dump is harmless.
--
-- This lives in the shared database (KBS2 reaches it as data source 3), so it belongs in the
-- migration history rather than being created ad hoc by the KBS2 server at boot. The enrolment
-- context (enrol_ip, machine_hostname, os_user, os_name/version, java_version) is data-minimised:
-- it exists only to inform the admin's approve/reject decision and is nulled the moment that
-- decision is made (BoDeviceStore.approve/reject/revoke). There is deliberately no last_used_ip.
--
-- Transient by design: drop it with a later migration when KBS2 is decommissioned.

CREATE TABLE IF NOT EXISTS public.bo_device (
    id               bigserial    PRIMARY KEY,
    username         varchar(128) NOT NULL,
    device_id        varchar(64)  NOT NULL,
    public_key       text         NOT NULL,
    status           varchar(16)  NOT NULL DEFAULT 'PENDING',
    label            varchar(256),
    enrol_ip         varchar(64),
    enrol_country    varchar(8),
    machine_hostname varchar(256),
    os_user          varchar(128),
    os_name          varchar(64),
    os_version       varchar(64),
    java_version     varchar(32),
    app_version      varchar(32),
    enrolled_at      timestamp    NOT NULL DEFAULT now(),
    approved_by      varchar(128),
    approved_at      timestamp,
    reject_reason    varchar(256),
    last_used_at     timestamp,
    CONSTRAINT bo_device_user_device UNIQUE (username, device_id)
);

-- Ownership: a table created by the migration's connect role is unwritable by the other app roles
-- (KBS2 connects as kbs_server) — the "permission denied" trap V0064 exists to repair. Hand the
-- table AND its sequence to whoever owns public.person, so every app role writes it like any other.
DO $$
DECLARE app_owner name;
BEGIN
    SELECT pg_get_userbyid(c.relowner) INTO app_owner
      FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
     WHERE n.nspname = 'public' AND c.relname = 'person';

    IF app_owner IS NOT NULL THEN
        EXECUTE format('ALTER TABLE public.bo_device OWNER TO %I', app_owner);
        EXECUTE format('ALTER SEQUENCE public.bo_device_id_seq OWNER TO %I', app_owner);
    END IF;
END $$;
