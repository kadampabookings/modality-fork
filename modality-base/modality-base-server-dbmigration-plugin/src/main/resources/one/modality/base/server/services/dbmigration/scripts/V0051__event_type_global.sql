-- V0051: allow GLOBAL event types (event_type.organization_id nullable).
--
-- Some event types are conceptually common to all organizations (residents,
-- volunteers, GP classes, public talks, national festivals, dharma
-- celebrations...) and are today either duplicated per organization
-- (e.g. "Public Talk" ids 18 and 60) or already used cross-organization
-- despite the column (National Festival 38 and NEDC 42 are used by events of
-- orgs 187 AND 287, Dharma Celebration 39 by orgs 152 AND 159).
--
-- An event type with organization_id NULL is GLOBAL: it belongs to no
-- organization and is offered to all of them. Pickers list an organization's
-- own types plus the global ones (KBS2 FIELDs 1097/1981, kbs3-react event-type
-- queries — updated alongside this migration).
--
-- This is schema-only: no existing row is changed. Making a specific type
-- global (UPDATE event_type SET organization_id = NULL WHERE id = ...) is a
-- deliberate per-type data decision taken later.
--
-- Prepares the "eventType only (all organizations)" letter scope (next
-- migration): letters attached to a global event type with no organization.

ALTER TABLE event_type ALTER COLUMN organization_id DROP NOT NULL;

COMMENT ON COLUMN event_type.organization_id IS
    'Owning organization; NULL = global event type, common to all organizations (V0051).';
