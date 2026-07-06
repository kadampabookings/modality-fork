-- Adds a 5th event-part slot to event_selection (EventSelection.part5 in DomainModel.json),
-- so a selection can span up to 5 consecutive event parts. Mirrors part2-part4 exactly:
-- nullable integer FK to event_part (inline REFERENCES auto-names the constraint
-- event_selection_part5_id_fkey, consistent with the part1-4 constraints).
--
-- Idempotent via ADD COLUMN IF NOT EXISTS.

ALTER TABLE public.event_selection
    ADD COLUMN IF NOT EXISTS part5_id integer REFERENCES public.event_part(id);
