-- V0039: share-owner pricing quantity must come from the rate that actually priced the line.
--
-- BUG (event 1901, document ref 80): a twin room (share_owner, share_owner_quantity=2) priced by a
-- per-ROOM rate (60856, 115/night) was charged 690 instead of 345. compute_document_prices picked
-- the right rate (rate_matches_document filters on event/event-type), but the final quantity step
-- called compute_document_line_pricing_quantity, which re-fetched a rate on its own with only
-- item_id + site_id (no event filter, no ORDER BY) to read per_person. It landed on the PREVIOUS
-- year's rate for the same item+site (58012, event 1839, per_person=true) and multiplied by the
-- number of sharers. Any share_owner line whose item+site carries rates from several events with
-- differing per_person flags is exposed.
--
-- FIX: compute_document_prices already knows the applied rate (document_line_rates[dl_index]) at
-- the call site, so it now passes it in; a new 2-arg compute_document_line_pricing_quantity reads
-- per_person from it directly. The 1-arg signature stays as a delegating wrapper for any direct
-- caller; its fallback lookup now joins the document and requires rate_matches_document(r, d), so
-- even the fallback can no longer pick another event's rate.
--
-- This migration also ships the current canonical compute_document_prices body (breakfast credit +
-- withAccommodation gates, both previously applied manually from the aggregate repo's scripts/
-- folder). The columns those features reference are guarded idempotently below, so the shipped
-- body cannot reference a missing column on a database where the manual scripts never ran.

-- Column prerequisites (no-ops where the manual migrations already ran) ---------------------------

ALTER TABLE public.rate
    ADD COLUMN IF NOT EXISTS with_accommodation boolean;

COMMENT ON COLUMN public.rate.with_accommodation IS
    'Residential/non-residential rate filter: NULL applies to all; TRUE applies only when the booking has a live accommodation line (onsite); FALSE applies only when it has none (offsite). "Has accommodation" uses the same-cancelled-state rule as with_item.';

ALTER TABLE public.rate
    ADD COLUMN IF NOT EXISTS breakfast_included boolean;

ALTER TABLE public.item
    ADD COLUMN IF NOT EXISTS breakfast_included boolean;

ALTER TABLE public.document_line
    ADD COLUMN IF NOT EXISTS breakfast_included boolean;

-- Quantity: 2-arg form taking the rate applied by compute_document_prices ------------------------

CREATE OR REPLACE FUNCTION public.compute_document_line_pricing_quantity(dl public.document_line, applied_rate public.rate)
 RETURNS integer
 LANGUAGE plpgsql
AS $function$
DECLARE
	quantity document_line.quantity%TYPE;
	per_person bool;
BEGIN
	IF (dl.price_is_custom) THEN
		quantity := 1;
	ELSE
		quantity := dl.quantity;
		IF (dl.share_mate and not dl.share_mate_charged) THEN
			quantity := 0;
		ELSIF (dl.share_owner) THEN
			-- Whether one room price covers all sharers (per-room) or each sharer pays (per-person)
			-- must be read from the SAME rate that priced the line; compute_document_prices passes it in.
			per_person := applied_rate.per_person;
			IF (per_person is null) THEN
				-- No applied rate known (direct 1-arg call): look one up, but only among rates
				-- applicable to this booking (rate_matches_document enforces the event / event-type
				-- match). The old unfiltered item+site lookup could return another event's rate and
				-- flip per_person (e.g. doubling a per-room twin price).
				select into per_person r.per_person
					from rate r
					join item i on i.id = dl.item_id
					join document d on d.id = dl.document_id
					where r.item_id = coalesce(i.rate_alias_item_id, i.id)
					  and r.site_id = dl.site_id
					  and rate_matches_document(r, d)
					limit 1;
			END IF;
			IF (per_person is not true) THEN
				quantity := 1;
			ELSE
				quantity := dl.share_owner_quantity - COALESCE((select sum(dlm.quantity) from document_line dlm where dlm.share_mate_owner_document_line_id=dl.id and dlm.share_mate_charged=true), 0);
			END IF;
		END IF;
	END IF;
	RETURN quantity;
END;
$function$;

-- Quantity: 1-arg signature kept as a delegating wrapper (exact match beats the 2-arg form, so
-- existing direct callers keep working and now get the event-filtered fallback) ------------------

CREATE OR REPLACE FUNCTION public.compute_document_line_pricing_quantity(dl public.document_line)
 RETURNS integer
 LANGUAGE plpgsql
AS $function$
BEGIN
	RETURN public.compute_document_line_pricing_quantity(dl, null::public.rate);
END;
$function$;

-- compute_document_prices: canonical body; the only change vs scripts/compute_document_prices.sql
-- is the quantity call site, which now passes the applied rate ------------------------------------

CREATE OR REPLACE FUNCTION public.compute_document_prices(document_id integer, trace boolean)
 RETURNS integer
 LANGUAGE plpgsql
AS $function$
  DECLARE
did int := document_id; -- second name to remove ambiguity in sql statement
      recs compute_price_record[];
      rec compute_price_record;
      rec_index int := 0;
      i int;
      document document;
      document_line document_line;
      attendance attendance;
      document_lines document_line[];
      document_line_rates rate[];
      document_line_rate_indexes int[];
      block_site_id int;
      block_rate_item_id int;
      block_price int;
      block_length int;
      single_line_block bool;
      with_item_block_applicable bool[];
      block_rec_index int;
      rate_block_length int;
      consumed_days int[];
      consumed_prices int[];
      consumed_day int;
      consumed_price int;
      rate_unit_price int;
      attendance_price int;
      cheapest_attendance_price int;
      block_attendance_price int;
      rate rate;
      rates_count int;
      rate_index int;
      rate_applicable bool;
      rate_min_day int;
      rate_max_day int;
      rate_min_deposit int;
      rate_non_refundable int;
      r_date date;
      pricing_quantity int;
      dl_index int;
write bool;
      starting_block bool;
      rounding_factor int := null;
      new_rounding_algo bool;
      rounding_net int := 0;
      rounding_min_deposit int := 0;
      rounding_non_refundable int := 0;
      family_code item_family.code%TYPE;
      bkf_live int := 0;              -- breakfast credit: charged breakfasts on live (non-cancelled) BKF lines
      bkf_cancelled int := 0;         -- charged breakfasts on cancelled BKF lines
      acco_nights_live int := 0;      -- breakfast_included nights on live accommodation lines
      acco_nights_cancelled int := 0; -- breakfast_included nights on cancelled accommodation lines
      forgiven_live int := 0;         -- least(bkf_live, acco_nights_live): live breakfasts to forgive
      forgiven_cancelled int := 0;    -- cancelled breakfasts to forgive (cancelled + leftover live nights)
      is_breakfast_line bool;         -- true when the current line is the breakfast item (BKF)
BEGIN
      raise notice '>>> compute_document_prices(%)', document_id;
      recs := array(
          with dls as (select d -- using a with statement to be able to use rate_item_id and rate_date in the final select for fetching rates
                  , dl
                  , coalesce((select rate_alias_item_id from item i where i.id=dl.item_id), dl.item_id) as rate_item_id
                  , case when dl.cancelled and dl.cancellation_date is not null then dl.cancellation_date else cast(now() as date) end as rate_date
                  , dl.site_id -- also used for fetching rates
                  , dl.id as document_line_id -- used for attendance join in the final select
                  , dl.cancelled or dl.abandoned as cancelled
                  , cast(row_number() over (order by dl.id) as int) as document_line_index
                  , cast((select count(*) from document_line dl where dl.document_id=d.id) as int) as document_lines_count
              from document_line dl join document d on d.id=dl.document_id
              where d.id=did and dl.item_id<>23)
          , dlrs as (select d, dl, rate_item_id, rate_date, site_id, document_line_id, cancelled, document_line_index, document_lines_count
                  , array(select r from rate r where r.site_id=dls.site_id and r.item_id=rate_item_id and rate_matches_document(r,d) and (true or kbs_overlaps(rate_date, rate_date, r.on_date, r.off_date)) and (not r.early_bird or (d).early_bird) order by coalesce(compute_rate_unit_price(r, d), 0) / case when r.per_day then 1 else
          coalesce(r.max_day, 1) end desc) as rates
              from dls)
          select (d, dl, rate_item_id, rate_date, rates, document_line_index, document_lines_count, a)
              from dlrs left join attendance a on a.document_line_id=dlrs.document_line_id
              where a.charged or a is null -- skipping not charged attendance
              order by dlrs.site_id,rate_item_id,dlrs.cancelled,a.date
      );
      if (array_length(recs) = 0) then
          select into document * from document d where d.id = did;
          write := document.trigger_defer_compute_prices; -- detecting if called from trigger, if yes we need to update tables
      end if;
      foreach rec in array recs loop
          rec_index := rec_index + 1;
          if (document is null) then -- first iteration, initializing document_lines as empty array
              document := rec.d;
              write := document.trigger_defer_compute_prices; -- detecting if called from trigger, if yes we need to update tables
              document_lines := array_fill(cast(null as document_line), ARRAY[rec.document_lines_count]);
              document_line_rates := array_fill(cast(null as rate), ARRAY[rec.document_lines_count]);
              document_line_rate_indexes := array_fill(0, ARRAY[rec.document_lines_count]);
          end if;
          document_line := document_lines[rec.document_line_index];
          if (document_line is null) then
              document_line := rec.dl;
              if (not document_line.price_is_custom) then
                  document_line.price_net := 0;
                  document_line.price_min_deposit := 0;
                  document_line.price_non_refundable := 0;
              end if;
          end if;
          attendance := rec.a;
          -- new block detection (a block is identified by site_id and rate_item_id pair
          starting_block := block_site_id is null or block_site_id <> document_line.site_id or block_rate_item_id <> rec.rate_item_id;
          if (starting_block) then
              -- resetting the block as a new block
              block_site_id := document_line.site_id;
              block_rate_item_id := rec.rate_item_id;
              block_price := 0;
              block_rec_index = rec_index;
              -- computing the block length
              block_length := 1; -- starting with 1 (the minimum)
              single_line_block = true;
              -- then increasing the length until we are out of the block (another site_id or rate_item_id)
              while (rec_index + block_length <= array_length(recs) and recs[rec_index + block_length].dl.site_id = block_site_id and recs[rec_index + block_length].rate_item_id = block_rate_item_id) loop
                  single_line_block := single_line_block and recs[rec_index + block_length].document_line_index = rec.document_line_index;
                  block_length := block_length + 1;
              end loop;
              rates_count := array_length(rec.rates);
              consumed_days   := array_fill(0, ARRAY[rates_count]); -- consumed days for each rate
              consumed_prices := array_fill(0, ARRAY[rates_count]); -- consumed price for each rate
              -- Pre-compute withItem all-or-nothing applicability for fixed rates with a temporal companion.
              -- Per-day rates are re-checked per attendance; fixed rates need every block date covered by a
              -- companion sharing the current line's cancelled state on that date (same-cancelled-state rule).
              with_item_block_applicable := array_fill(true, ARRAY[rates_count]);
              if (attendance is not null) then
                  for i in 1 .. rates_count loop
                      if (rec.rates[i].with_item_id is not null and not coalesce(rec.rates[i].per_day, false)) then
                          if ((select coalesce(temporal, false) from item where id = rec.rates[i].with_item_id)) then
                              with_item_block_applicable[i] := not exists(
                                  select 1 from generate_series(0, block_length - 1) gs(idx)
                                  where not exists(
                                      select 1 from attendance wa
                                      join document_line wdl on wdl.id = wa.document_line_id
                                      where wdl.document_id = did
                                        and coalesce((select rate_alias_item_id from item ii where ii.id = wdl.item_id), wdl.item_id) = rec.rates[i].with_item_id
                                        and wa.date = recs[block_rec_index + gs.idx].a.date
                                        and wa.charged
                                        and wdl.cancelled = recs[block_rec_index + gs.idx].dl.cancelled and not wdl.abandoned
                                  )
                              );
                          end if;
                      end if;
                  end loop;
              end if;
          end if;
          block_attendance_price := 0;
          rate := null; -- Resetting rate variable to null, because it may not be set (if following condition is false) and hold a deprecated value (ex: cancelled Vegetarian option - which has no attendances - was charged £5 for an admin fee previously computed on a previous option).
          if (not document.abandoned and not document_line.abandoned and not attendance is null) then
              -- searching the cheapest rate for this attendance
              cheapest_attendance_price := null;
              rate_index := 0;
              foreach rate in array rec.rates loop
                  rate_index := rate_index + 1;
                  rate_applicable := kbs_overlaps(attendance.date, attendance.date, rate.start_date, rate.end_date) and kbs_overlaps(document_line.creation_date, document_line.creation_date, rate.on_date, rate.off_date);
                  if (rate_applicable and rate.arriving_or_leaving and rec_index > 1 and rec_index < array_length(recs)) then
                      rate_applicable := recs[rec_index - 1].document_line_index <> rec.document_line_index
                                      or recs[rec_index + 1].document_line_index <> rec.document_line_index
                                      or greatest(attendance.date - recs[rec_index - 1].a.date, recs[rec_index + 1].a.date - attendance.date) > 1 ;
                  end if;
                  -- withItem: the rate applies only when the companion item is booked in this document with
                  -- the SAME cancelled state as the current line. Both live => bundle rate stays live; both
                  -- cancelled => bundle rate drives the cancellation fee; a divergent state (only one cancelled)
                  -- drops this rate so the current line falls back to its own (non-companion) rate.
                  -- Current item is always temporal here (attendance is not null).
                  if (rate_applicable and rate.with_item_id is not null) then
                      if (not (select coalesce(temporal, false) from item where id = rate.with_item_id)) then
                          -- Companion non-temporal: existence check only (no date condition), same cancelled state
                          rate_applicable := exists(
                              select 1 from document_line wdl
                              where wdl.document_id = did
                                and coalesce((select rate_alias_item_id from item ii where ii.id = wdl.item_id), wdl.item_id) = rate.with_item_id
                                and wdl.cancelled = document_line.cancelled and not wdl.abandoned
                          );
                      elsif (rate.per_day) then
                          -- Both temporal, per-day: companion must be attended on this specific date, same cancelled state
                          rate_applicable := exists(
                              select 1 from attendance wa
                              join document_line wdl on wdl.id = wa.document_line_id
                              where wdl.document_id = did
                                and coalesce((select rate_alias_item_id from item ii where ii.id = wdl.item_id), wdl.item_id) = rate.with_item_id
                                and wa.date = attendance.date
                                and wa.charged
                                and wdl.cancelled = document_line.cancelled and not wdl.abandoned
                          );
                      else
                          -- Both temporal, fixed rate: all-or-nothing (pre-computed at block start)
                          rate_applicable := with_item_block_applicable[rate_index];
                      end if;
                  end if;
                  -- withAccommodation: residential/non-residential filter, matched per day. NULL applies
                  -- to all; TRUE only on days the guest is resident; FALSE only on days they are not. This
                  -- teaching day is residential when a live accommodation night (same cancelled state) is
                  -- booked on the same date or the day before: a night is dated by its check-in day N and
                  -- covers the evening of N and the morning of N+1, so it covers a teaching on day N (stayed
                  -- that evening) and on day N+1 (present that morning, e.g. the departure day).
                  if (rate_applicable and rate.with_accommodation is not null) then
                      rate_applicable := rate.with_accommodation = exists(
                          select 1 from attendance wa
                          join document_line wdl on wdl.id = wa.document_line_id
                          join item wi on wi.id = wdl.item_id
                          join item_family wf on wf.id = wi.family_id
                          where wdl.document_id = did
                            and wf.code = 'acco'
                            and wa.date in (attendance.date, attendance.date - 1)
                            and wa.charged
                            and wdl.cancelled = document_line.cancelled and not wdl.abandoned
                      );
                  end if;
                  rate_min_day = coalesce(rate.min_day, 1);
                  rate_max_day = case when rate.per_day then 1 else coalesce(rate.max_day, 10000) end;
                  rate_block_length = block_length;
                  -- cropping the rate_block_length within the rate [start_date, end_date] for min day comparison (ex: 2018 Summer part 2 discount is within 3-11 August, minDay = 9 but free day 2 August should be ignored in rate_block_length)
                  if (rate_applicable and block_length >= rate_min_day and (recs[block_rec_index].a.date < rate.start_date or recs[block_rec_index + block_length - 1].a.date > rate.end_date)) then
                      for i in block_rec_index .. block_rec_index + block_length - 1 loop
                          if (recs[i].a.date < rate.start_date or recs[i].a.date > rate.end_date) then
                              rate_block_length := rate_block_length - 1;
                          end if;
                      end loop;
                  end if;
                  if (rate_applicable and rate_block_length < rate_min_day and not rate.min_day_ceiling) then
                      rate_applicable := false;
                  end if;
                  if (not rate_applicable) then
                      consumed_days[rate_index] := 0;
                      consumed_prices[rate_index] := 0;
                  else
                      rate_unit_price := coalesce(compute_rate_unit_price(rate, rec.d), 0);
                      -- When a rate defines a new lower daily price that applies after a minimum of days (ex: 30% discount when >= 14 days),
                      -- we need to ensure that people approaching that number of days (ex: 12 or 13 days)
                      -- don't pay more with the previous rate than people staying that minimum of days (ex: 14 days)
                      -- In other words, we need to put an upper limit for such people, equals to the price that is applied at that minimum of days
                      if (rate_block_length < rate_min_day and rate_max_day = 1) then -- So if the block is less than the rate min day,
                          rate_unit_price = rate_unit_price * rate_min_day; -- we transform the daily rate into a fixed rate with the upper limit
                          rate_max_day = rate_min_day; -- that applies over that period
                      end if;
                      consumed_day := consumed_days[rate_index];
                      -- if (trace) THEN raise notice '148> consumed_price = %', consumed_price; end if;
                      if (rates_count = 1 and not rate.per_day and rate.max_day is null) then
                          consumed_price := block_price;
                          -- if (trace) THEN raise notice '151> consumed_price = %', consumed_price; end if;
                          if (document_line_rate_indexes[rec.document_line_index] = 0 and rate_unit_price > 0) then
                              while (consumed_price >= rate_unit_price) LOOP
                                  consumed_price := consumed_price - rate_unit_price;
                              end loop;
                          end if;
                      else
                          consumed_price := consumed_prices[rate_index];
                          -- if (trace) THEN raise notice '159> consumed_price = %', consumed_price; end if;
                      end if;
                      if (consumed_price > rate_unit_price) then
                          attendance_price := LEAST(0, rate_unit_price);
                      else
                          attendance_price := rate_unit_price - consumed_price;
                      end if;
                      consumed_day := consumed_day + 1;
                      if (consumed_day >= rate_max_day) then
                          consumed_days[rate_index] := 0;
                          consumed_prices[rate_index] := 0;
                      else
                          consumed_days[rate_index] := consumed_day;
                      end if;
                      if (cheapest_attendance_price is null or attendance_price < cheapest_attendance_price) then
                          cheapest_attendance_price := attendance_price;
                          -- memorizing applied rate to the document_line for min_deposit, non_refundable, and custom_price computation
                          document_line_rates[rec.document_line_index] := rate;
                          document_line_rate_indexes[rec.document_line_index] := rate_index;
                          if (trace) then raise notice '>>> Cheapest rate rate_id = %, unit_price = %, consumed_day = %, attendance_price = %', rate.id, rate_unit_price, consumed_day, attendance_price; end if;
                      end if;
                  end if;
              end loop;
              block_attendance_price := coalesce(cheapest_attendance_price, 0);
              for rate_index in 1 .. rates_count loop
                  if (consumed_days[rate_index] > 0) then
                      consumed_prices[rate_index] := consumed_prices[rate_index] + block_attendance_price;
                      -- if (trace) then    raise notice '>> rate_id = %, consumed_day = %, consumed_price = %', rec.rates[rate_index].id, consumed_days[rate_index], consumed_prices[rate_index]; end if;
                  end if;
              end loop;
              if (trace) then raise notice '> attendance: date = %, price = %', attendance.date, block_attendance_price; end if;
          elsif (not document.abandoned and not document_line.abandoned and attendance is null and rec.rates is not null) then
              -- No attendance: check for non-per-day rates (fixed fees not tied to individual attendance days)
              foreach rate in array rec.rates loop
                  if (not rate.per_day) then
                      -- withItem + withAccommodation gates for non-temporal items (existence checks only,
                      -- no date condition; both use the same cancelled state as the current line).
                      if ((rate.with_item_id is null or exists(
                          select 1 from document_line wdl
                          where wdl.document_id = did
                            and coalesce((select rate_alias_item_id from item ii where ii.id = wdl.item_id), wdl.item_id) = rate.with_item_id
                            and wdl.cancelled = document_line.cancelled and not wdl.abandoned
                      )) and (rate.with_accommodation is null or rate.with_accommodation = exists(
                          select 1 from document_line wdl
                          join item wi on wi.id = wdl.item_id
                          join item_family wf on wf.id = wi.family_id
                          where wdl.document_id = did
                            and wf.code = 'acco'
                            and wdl.cancelled = document_line.cancelled and not wdl.abandoned
                      ))) then
                          block_attendance_price := coalesce(compute_rate_unit_price(rate, rec.d), 0);
                          document_line_rates[rec.document_line_index] := rate;
                          if (trace) then raise notice '> no attendance, applying non-per-day rate: rate_id = %, price = %', rate.id, block_attendance_price; end if;
                          exit;
                      end if;
                  end if;
              end loop;
          end if;
          -- appending it to the block
          block_price := block_price + block_attendance_price;
          -- if (trace) then raise notice 'block: rate_item_id = %, price = %', rec.rate_item_id, block_price; end if;
          if (not document_line.price_is_custom) then
              rate := coalesce(document_line_rates[rec.document_line_index], rate);
              -- if the rate applies on the whole block, we reset the whole document line computation because different
              -- amount may finally apply for min deposit and non refundable (ex: £5 admin fees)
              rate_index = document_line_rate_indexes[rec.document_line_index];
              if (rate_index <> 0 and single_line_block and consumed_days[rate_index] = block_length) then
                  document_line.price_net             := 0;
                  document_line.price_min_deposit     := 0;
                  document_line.price_non_refundable     := 0;
                  block_attendance_price = block_price;
              end if;
              rate_min_deposit    = compute_rate_min_deposit   (rate, rec.rate_date);
              rate_non_refundable = compute_rate_non_refundable(rate, rec.rate_date);
              -- If rate min deposit or non refundable are negative, they express a fixed amount (and not a percentage).
              -- Note: only fixed non refundable are used so for (for admin fees), fixed min deposit should work as well
              -- here but be aware this will need an update of the front-end (to consider the case of negative values).
              document_line.price_net             := document_line.price_net                + block_attendance_price;
              document_line.price_min_deposit     := document_line.price_min_deposit         + case when rate_min_deposit    >=0 then block_attendance_price else -100 end * rate_min_deposit;
              document_line.price_non_refundable     := document_line.price_non_refundable     + case when rate_non_refundable >=0 then block_attendance_price else -100 end * rate_non_refundable;
          end if;
          document_lines[rec.document_line_index] := document_line;
      end loop;

      -- final iteration: finalizing details (quantity, percentage, discount, rounding) and computing total prices
      document.price_net := 0;
      document.price_min_deposit := 0;
      document.price_non_refundable := 0;
      new_rounding_algo := document.event_id >= 45 and document.event_id <= 64 or document.event_id >= 90;
      if (document_lines is not null) then
          select into rounding_factor option_rounding_factor from event where id=document.event_id;
          -- Breakfast credit (bundle rule): a charged breakfast (BKF) is forgiven when a
          -- breakfast_included accommodation night covers it, so breakfast is never charged twice.
          -- Cancellation is handled per state so the credit survives cancellation (mirroring the
          -- with_item same-cancelled-state rule): live breakfasts are covered only by live included
          -- nights; cancelled breakfasts are covered by cancelled included nights AND any live
          -- included nights left over after the live breakfasts (a still-live room already covers
          -- them), so cancelling a redundant/prepaid breakfast costs nothing.
          -- No event gating is needed: KBS2 lines capture breakfast_included = false, so N stays 0 there.
          select count(*) filter (where not coalesce(dl.cancelled, false)),
                 count(*) filter (where coalesce(dl.cancelled, false))
              into bkf_live, bkf_cancelled
              from document_line dl join attendance a on a.document_line_id = dl.id and a.charged
                      join item i on i.id = dl.item_id
              where dl.document_id = did and not coalesce(dl.abandoned, false) and i.code = 'BKF';
          select count(*) filter (where not coalesce(dl.cancelled, false)),
                 count(*) filter (where coalesce(dl.cancelled, false))
              into acco_nights_live, acco_nights_cancelled
              from document_line dl join attendance a on a.document_line_id = dl.id and a.charged
              where dl.document_id = did and not coalesce(dl.abandoned, false)
                    and dl.breakfast_included is true;
          forgiven_live := least(bkf_live, acco_nights_live);
          -- Live breakfasts get first claim on live included nights; cancelled breakfasts then draw
          -- on cancelled included nights plus whatever live nights remain (prevents over-forgiving).
          forgiven_cancelled := least(bkf_cancelled, acco_nights_cancelled + acco_nights_live - forgiven_live);
          dl_index := 0;
          foreach document_line in array document_lines loop
              dl_index := dl_index + 1;
              -- if custom price, computing the min deposit and non refundable over the whole line
              if (document_line.price_is_custom) then
                  rate := document_line_rates[dl_index];
                  r_date := case when document_line.cancelled and document_line.cancellation_date is not null then document_line.cancellation_date else cast(now() as date) end;
                  rate_min_deposit    = case when rate is null then 100 else compute_rate_min_deposit(rate, r_date) end;
                  rate_non_refundable = case when rate is null then 100 else compute_rate_non_refundable(rate, r_date) end;
                  document_line.price_net            := document_line.price_custom;
                  document_line.price_min_deposit    := case when rate_min_deposit    >=0 then document_line.price_net else -100 end * rate_min_deposit;
                  document_line.price_non_refundable := case when rate_non_refundable >=0 then document_line.price_net else -100 end * rate_non_refundable;
                  pricing_quantity := 1;
              else
                  pricing_quantity := compute_document_line_pricing_quantity(document_line, document_line_rates[dl_index]);
              end if;
              -- applying the quantity (for all) and percentage (for min deposit and non refundable)
              document_line.price_net            := coalesce(pricing_quantity * document_line.price_net, 0);
              document_line.price_min_deposit    := coalesce(pricing_quantity * document_line.price_min_deposit / 100, 0);
              document_line.price_non_refundable := coalesce(pricing_quantity * document_line.price_non_refundable / 100, 0);
              -- applying discount if any
              if (document_line.price_discount is not null and not document_line.price_is_custom) then -- no discount on custom price
                  document_line.price_net            := document_line.price_net            - document_line.price_net            * document_line.price_discount / 100;
                  document_line.price_min_deposit    := document_line.price_min_deposit    - document_line.price_min_deposit    * document_line.price_discount / 100;
                  document_line.price_non_refundable := document_line.price_non_refundable - document_line.price_non_refundable * document_line.price_discount / 100;
              end if;
              -- applying rounding factor if any
              if (rounding_factor is not null) then
                   select into family_code f.code from item i join item_family f on f.id=i.family_id where i.id=document_line.item_id;
                   if (family_code not in ('acco', 'tax')) then -- not on accommodation and tax (because they are not round anymore in KMCF courses)
                      document_line.price_net := round(document_line.price_net * 1.0 / rounding_factor) * rounding_factor;
                   end if;
              end if;
              -- rounding balance for min deposit and non refundable
              if (not new_rounding_algo) then
                  document_line.price_min_deposit    := document_line.price_net - (document_line.price_net - document_line.price_min_deposit)    / 100 * 100;
                  document_line.price_non_refundable := document_line.price_net - (document_line.price_net - document_line.price_non_refundable) / 100 * 100;
              end if;
              -- Breakfast credit: on each breakfast line, keep only the uncovered fraction
              -- (B - forgiven)/B (proportional, integer arithmetic). Live and cancelled BKF lines
              -- use their own pool; the cancelled scaling runs here, before the cancelled-line
              -- correction below turns net into non_refundable (so a fully-covered cancelled
              -- breakfast ends up as a £0 fee rather than a full-price one).
              if (forgiven_live > 0 or forgiven_cancelled > 0) then
                  select (i.code = 'BKF') into is_breakfast_line
                      from item i where i.id = document_line.item_id;
                  if (is_breakfast_line) then
                      if (not document_line.cancelled and not document_line.abandoned and bkf_live > 0) then
                          document_line.price_net            := document_line.price_net            * (bkf_live - forgiven_live) / bkf_live;
                          document_line.price_min_deposit    := document_line.price_min_deposit    * (bkf_live - forgiven_live) / bkf_live;
                          document_line.price_non_refundable := document_line.price_non_refundable * (bkf_live - forgiven_live) / bkf_live;
                      elsif (document_line.cancelled and not document_line.abandoned and bkf_cancelled > 0) then
                          document_line.price_net            := document_line.price_net            * (bkf_cancelled - forgiven_cancelled) / bkf_cancelled;
                          document_line.price_min_deposit    := document_line.price_min_deposit    * (bkf_cancelled - forgiven_cancelled) / bkf_cancelled;
                          document_line.price_non_refundable := document_line.price_non_refundable * (bkf_cancelled - forgiven_cancelled) / bkf_cancelled;
                      end if;
                  end if;
              end if;
              -- price correction for cancelled lines => net and min deposit become non refundable
              if (document_line.cancelled) then
                  rounding_net := rounding_net + document_line.price_net - document_line.price_non_refundable;
                  document_line.price_net         := document_line.price_non_refundable;
                  document_line.price_min_deposit := document_line.price_non_refundable;
              end if;
              rounding_min_deposit    := rounding_min_deposit    + document_line.price_net - document_line.price_min_deposit;
              rounding_non_refundable := rounding_non_refundable + document_line.price_net - document_line.price_non_refundable;
              document.price_net               := document.price_net            + document_line.price_net;
              document.price_min_deposit       := document.price_min_deposit    + document_line.price_min_deposit;
              document.price_non_refundable := document.price_non_refundable + document_line.price_non_refundable;
              if (write) then
                  update document_line as dl set
                               price_net            = document_line.price_net,
                               price_min_deposit    = document_line.price_min_deposit,
                               price_non_refundable = document_line.price_non_refundable
                      where dl.id=document_line.id;
              end if;
              if (trace) then raise notice 'document_line = % - % - % - % - % - %', document_line.id, document_line.item_id, document_line.dates, document_line.price_net, document_line.price_min_deposit, document_line.price_non_refundable; end if;
          end loop;
      end if;

      if (new_rounding_algo) then
          -- if (document.event_id = 115 and document.price_net % 100 <> 0) then
          --    rounding_net := rounding_net + 100 - document.price_net % 100;
          -- end if;
          rounding_net := rounding_net % 100;
          rounding_min_deposit := (rounding_min_deposit + rounding_net) % 100;
          rounding_non_refundable := (rounding_non_refundable + rounding_net) % 100;
          if (rounding_net = 0 and rounding_min_deposit = 0 and rounding_non_refundable = 0) then
              if (write) then
                  delete from document_line dl where dl.document_id=document.id and dl.item_id=23;
              end if;
          else
              if (write) then
                  update document_line dl set price_net = rounding_net, price_min_deposit = rounding_min_deposit, price_non_refundable = rounding_non_refundable, read=true where dl.document_id=document.id and dl.item_id=23;
                  if (not found) then
                    insert into document_line (document_id, item_id, price_net, price_min_deposit, price_non_refundable, read) values (document.id, 23, rounding_net, rounding_min_deposit, rounding_non_refundable, true);
                  end if;
              end if;
              document.price_net := document.price_net + rounding_net;
              document.price_min_deposit := document.price_min_deposit + rounding_min_deposit;
          end if;
      end if;

      if (write) then
          update document as d set
                         price_net            = document.price_net,
                         price_min_deposit    = document.price_min_deposit,
                         price_non_refundable = document.price_non_refundable,
                         trigger_defer_compute_prices = false
            where d.id=document.id;
      end if;

RETURN document.price_net;
END;
  $function$
;
