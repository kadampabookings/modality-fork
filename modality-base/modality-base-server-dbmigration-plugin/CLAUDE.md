# CLAUDE.md — database migrations

The rules for writing a migration are in the header of
`src/main/resources/one/modality/base/server/services/dbmigration/scripts/index.txt`.

## A migration is not finished until the staging anonymiser knows about it

The staging database is a production dump, anonymised by the kbs3-aggregate repo's
`scripts/gdpr-anonymise/` package before the staging apps start. The package works from a
classification of every column (`anon.manifest` in `10-anon-helpers.sql`). So any migration
here that adds, renames or drops a table or column needs an update there, **in the same
change**:

- Classify every new table and column, with a reason, **whether or not it holds personal
  data**. An unclassified text/date/inet/jsonb column fails the next staging refresh; any
  other type passes unseen, so a table of person ids and timestamps leaks silently unless
  you classify it.
- A column that is `anonymised` needs a rewrite in `20-anonymise.sql`, guarded on the
  catalog — the anonymiser runs at production's schema version, which lags staging.
- A new constraint or trigger on a column the anonymiser rewrites must accept what it writes.

The procedure is "Changing the database schema" in
[the aggregate's CLAUDE.md](../../../CLAUDE.md#changing-the-database-schema), and in full,
"Changing the schema" in
[`scripts/gdpr-anonymise/README.md`](../../../scripts/gdpr-anonymise/README.md#changing-the-schema).
