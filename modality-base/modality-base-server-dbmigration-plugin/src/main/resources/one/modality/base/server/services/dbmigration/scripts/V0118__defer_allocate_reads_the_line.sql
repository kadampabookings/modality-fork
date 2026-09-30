-- V0118: allocation skips a share-mate line by ITS OWN flag, not only by its item's.
--
-- The trigger already means to skip mates, and says so in its own comment: "checking that it's not a
-- share_mate item (because mates are managed by on_share_linked_copy_info trigger that automatically
-- allocates to the same resource as the room booker when the link is made)". It asks the ITEM, which
-- answered the question while a mate could only name a sharing item. A VIRTUAL sharing option (room-mate
-- plan Part B) names the ROOM's item and says what it is on the line, so the item now answers false and
-- the mate is queued for an allocation of their own.
--
-- What that costs, concretely: an unlinked mate is placed in whichever room of that type the allocator
-- reaches first, which is usually NOT the room they are joining. Registration then sees them in a room
-- that means nothing, and linking moves them again. V0117 keeps them from taking a BED there (quantity 0
-- while unlinked), so the count stays right, but a person shown in a room they never asked for is worse
-- than one shown in no room at all -- and staging booking 128 is exactly that shape today.
--
-- Scope of the change: only lines that are share_mate, NOT share_owner, and name a non-sharing item --
-- since a sharing item already fails `share = false`, and a LINKED mate is already excluded by the
-- trigger's WHEN clause. So it moves virtual mates (which do not exist yet) and the 378 legacy unlinked
-- mates sitting on real room items, which stop being re-allocated every time one of their watched columns
-- is touched. Nothing else in the system reaches this branch.
--
-- The share_owner exemption is not a detail. Without it, the 6 live lines flagged share_mate AND
-- share_owner while unlinked would stop being allocated at all: they are room bookers, holding beds this
-- trigger is what places, and a skipped allocation there is a room nobody is put in and no sold-out check
-- runs against. V0117 excluded exactly these rows, for exactly this reason, and the two must agree.
--
-- What stops running for these lines, stated rather than assumed: they no longer reach
-- deferred_allocate_document_line, so neither its capacity check nor its sold-out check (V0082) applies to
-- them. The capacity half is moot -- V0117 holds these lines at quantity 0 while unlinked, so they occupy
-- nothing there is capacity to exceed. The sold-out half moves to the submit gate, where
-- SharingPlaceAvailability.virtualOfferFor now refuses a room registration has forced sold out. That covers
-- the SoldOutItem rows KBS3 writes, which is the whole of the KBS3 mechanism: ItemPolicy.forceSoldOut and
-- option.force_soldout are the superseded KBS2-era sources that no KBS3 booking path consults, here or
-- anywhere else. A KBS2-written event relying on them keeps its own behaviour, because KBS2 does not book
-- virtual sharing options.
--
-- A function replacement, deliberately: the trigger itself is not dropped or recreated, so this takes no
-- lock on document_line (1.37M rows) and needs none of V0117's lock_timeout dance.
--
-- The body below is the deployed one with a single conjunct added; everything else, including the French
-- Festivals meal-code exclusion, is reproduced verbatim so the diff is the rule and nothing else.

CREATE OR REPLACE FUNCTION public.trigger_document_line_defer_allocate() RETURNS trigger AS $$
DECLARE
    share bool;
BEGIN
    -- RAISE NOTICE 'Entering trigger %.%(%)', TG_RELNAME, TG_NAME, NEW.id;
    -- first, checking that there was really a change (an update trigger is triggered whenever new values are identical or not to old ones)
    IF (TG_OP = 'INSERT' or NEW.site_id is distinct from OLD.site_id or NEW.item_id is distinct from OLD.item_id or NEW.dates is distinct from OLD.dates or NEW.pool_id is distinct from OLD.pool_id or NEW.reserved is distinct from OLD.reserved or NEW.resource_configuration_id is distinct from OLD.resource_configuration_id) THEN
        -- then, checking that it's not a share_mate item (because mates are managed by on_share_linked_copy_info trigger that automatically allocates to the same resource as the room booker when the link is made)
        select into share share_mate from item where id=NEW.item_id;
        -- ... and not a share-mate LINE either: a virtual sharing option names the room's own item, so the
        -- item's flag no longer answers the question the line does (room-mate plan Part B). The
        -- share_owner exemption is V0117's, for the same reason and on the same rows: 17 live lines carry
        -- BOTH flags, and their bed is a room booker's, so the owner reading governs -- skipping their
        -- allocation would leave a room they hold unallocated and unchecked for sold-out.
        IF share = false AND NOT (coalesce(NEW.share_mate, false) AND NOT coalesce(NEW.share_owner, false)) THEN
            -- Now all OK to trigger a normal allocation
            update document_line dlu set trigger_defer_allocate=true from document_line dl join item i on i.id=dl.item_id where dl.id=dlu.id and dl.trigger_defer_allocate=false and dl.id=NEW.id and (i.code is null or i.code not in('pdej', 'din')); -- for French Festivals: excluding Petit Déjeuner and Dîner (keeping only Déjeuner)
        END IF;
    END IF;
    return NEW;
END $$ LANGUAGE plpgsql;
