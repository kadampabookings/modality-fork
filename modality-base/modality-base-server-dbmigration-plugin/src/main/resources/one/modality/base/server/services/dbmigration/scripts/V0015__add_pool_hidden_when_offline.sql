-- V0015: add pool.hidden_when_offline.
--
-- Flags pools whose rooms should be HIDDEN from the registration rooms view when
-- offline — e.g. Residents, Overflow Spaces, Unavailable. Their rooms are not
-- part of the normal registration inventory; they reappear only when a config
-- sets online=true (residents vacate for a big event), or when the registrar
-- turns the "Show hidden rooms" toggle on. Default false = normal pools, always
-- shown.
--
-- Data-only follow-up (NOT here): flag the specific pools (Residents / Overflow
-- Spaces / Unavailable) via the pool editor or a targeted UPDATE — pool names
-- are org data, so they are not baked into this schema migration.

ALTER TABLE pool
    ADD COLUMN IF NOT EXISTS hidden_when_offline boolean NOT NULL DEFAULT false;
