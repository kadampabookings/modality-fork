-- V0167: authorization_super_admin.organization_id — a super administrator scoped to an organization.
--
-- WHY. A super administrator could reach every organization's data. Super administrators are mostly developers who
-- need real data to test with, so a compromised developer account was a compromise of everybody's personal data
-- (Bruno, 2026-11-17). A super administrator is now scoped to the organizations their rows name; they keep more
-- FEATURES than an organization administrator, but only over those organizations' data. Plan: the aggregate repo's
-- docs/security/admin-tiers-and-explicit-operations-plan.md, Phase 2.
--
-- WHAT. One row per (person, organization). A row with NO organization is a PLATFORM super administrator: global, as
-- every row was until now — they alone create organizations, assign super administrators and Data Protection
-- Officers, and keep the system-wide actions. Every existing row keeps a null organization, so nothing changes at
-- deploy: a super administrator becomes scoped only when their null row is replaced by organization rows.
--
-- Scoping the grants does not, by itself, scope what such an account can READ: that is the read-authorization work,
-- which must enforce on the personal-data tables before this is a data control (plan, Phase 0, point 1).
--
-- The anonymiser classifies authorization_super_admin as a whole table ('*', not-pii, "ids only"); an organization
-- id says nothing about a person, so that holds.
--
-- DDL: a nullable column with no default (metadata only), a foreign key on a table of a handful of rows, and a unique
-- index so the same (person, organization) — or two platform rows for one person — cannot be inserted twice.
-- NULLS NOT DISTINCT needs PostgreSQL 15+ (production and staging run 17). Safe to re-run.

ALTER TABLE public.authorization_super_admin ADD COLUMN IF NOT EXISTS organization_id integer;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'authorization_super_admin_organization_fk') THEN
        ALTER TABLE public.authorization_super_admin
            ADD CONSTRAINT authorization_super_admin_organization_fk
            FOREIGN KEY (organization_id) REFERENCES public.organization (id);
    END IF;
END $$;

CREATE UNIQUE INDEX IF NOT EXISTS authorization_super_admin_person_organization_uq
    ON public.authorization_super_admin (super_admin_id, organization_id) NULLS NOT DISTINCT;

COMMENT ON COLUMN public.authorization_super_admin.organization_id IS
    'The organization this super administrator is scoped to; NULL = platform super administrator (global) (V0167).';
