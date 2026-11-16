-- V0162: item.lay_allowed and item.ordained_allowed — who an item is open to by ordination.
--
-- Some program items are for one kind of person only, whatever event they are scheduled in: the
-- Sojong is a ceremony of the ordained Sangha; a Pratimoksha vow ceremony would be for lay people
-- only. The booking form knew the Sojong by its item code ('SOJ') and nothing else could be said.
--
-- Two flags, after the ItemPolicy age flags' pattern (child_allowed, young_adult_allowed,
-- adult_allowed), but on the item: the rule belongs to the ceremony, not to an event's policy for it,
-- so there is no wider scope to defer to and the columns are NOT NULL DEFAULT true — every existing
-- item stays open to everyone. An item may not close itself to both (the check constraint).
--
-- The booking form reads them for event parts too: a part whose program sessions all exclude a
-- booker is not offered to that booker (the Sojong part's Thursday arrival and Friday-morning half are
-- shown to ordained bookers only). EventPart itself is unchanged.
--
-- The Sojong item (code 'SOJ', the global item 537) is set lay_allowed = false here, so it is
-- ordained-only from the moment the column exists.

-- One ALTER, so `item` — read by every policy query — is locked once and briefly: adding a column with
-- a constant default is catalog-only, and the check reads ~1,200 rows. Like V0139 and V0143 on `event`,
-- it takes ACCESS EXCLUSIVE under the runner's 5 s lock_timeout; deploy outside a busy moment.
-- Guarded on the constraint (as V0096), so a database that already has it is left alone.
do $$
begin
    if not exists (select 1 from pg_constraint
                    where conname = 'item_lay_or_ordained_allowed'
                      and conrelid = 'public.item'::regclass) then
        alter table public.item
            add column if not exists lay_allowed boolean default true not null,
            add column if not exists ordained_allowed boolean default true not null,
            add constraint item_lay_or_ordained_allowed check (lay_allowed or ordained_allowed);
    end if;
end $$;

UPDATE item SET lay_allowed = false WHERE code = 'SOJ';

COMMENT ON COLUMN item.lay_allowed IS
    'False when the item is not open to lay people (e.g. the Sojong, for the ordained Sangha only).';
COMMENT ON COLUMN item.ordained_allowed IS
    'False when the item is not open to ordained people (e.g. a lay Pratimoksha vow ceremony).';
