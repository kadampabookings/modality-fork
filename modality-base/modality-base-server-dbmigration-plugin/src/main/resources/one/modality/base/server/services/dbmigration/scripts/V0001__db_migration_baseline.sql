-- Baseline migration: proves the boot-time migration pipeline end-to-end and documents the log table.
-- (The db_migration table itself is created by the migration runner on first boot, before any script runs.)

COMMENT ON TABLE db_migration IS 'DB migration execution log, managed by webfx-stack-db-migration at server boot. Do not edit rows manually, except for documented checksum repairs.';

COMMENT ON COLUMN db_migration.execution_time_ms IS 'Milliseconds elapsed since the start of the migration transaction when this row was recorded (cumulative across the scripts of one run; total time until failure on failure rows).';
