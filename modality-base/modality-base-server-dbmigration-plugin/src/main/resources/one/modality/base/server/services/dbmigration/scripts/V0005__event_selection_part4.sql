-- Adds a 4th event-part slot to event_selection (EventSelection.part4 in DomainModel.json),
-- so a selection can span up to 4 consecutive event parts. Mirrors part2_id/part3_id exactly:
-- nullable integer FK to event_part (inline REFERENCES auto-names the constraint
-- event_selection_part4_id_fkey, consistent with the part1-3 constraints).
--
-- Idempotent via ADD COLUMN IF NOT EXISTS.

ALTER TABLE public.event_selection
    ADD COLUMN IF NOT EXISTS part4_id integer REFERENCES public.event_part(id);
