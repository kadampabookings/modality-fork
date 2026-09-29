-- V0115: a rate's dates are days in the event's time zone, and its offDate is the first day without it.
--
-- A rate carries two kinds of dates:
-- - start_date / end_date bound the attended days. They are only ever compared with attendance dates,
--   which are days too, so no time zone is involved and nothing changes for them.
-- - on_date / off_date (when the rate can be booked) and cutoff_date..cutoff_date5 (when each min deposit
--   and non-refundable tier ends) are compared with a moment: the line's creation, or today.
--
-- Those moments were turned into days in the database SESSION's time zone - kbs_overlaps casts the dates
-- to timestamptz, and now() and the cancellation trigger were cast to date. The same booking was
-- therefore priced differently depending on the server that computed it, the PostgreSQL JDBC driver
-- setting the session time zone to the JVM's. Booking 1157 at event 1898 (Europe/Madrid) was made at
-- 00:30 CEST on 21 August, after its early bird ended (off_date = 21 August, displayed as "20 August,
-- 23:59"). It was first priced without the discount, then given it back by a recompute on a UTC server,
-- where midnight on the 21st is 02:00 CEST.
--
-- The moments are now read in the event's time zone (event.timezone, then organization.timezone, then
-- UTC - event_time_zone), the zone the client calculators and the front office's deadline use:
-- - compute_document_prices: on/off dates via rate_on_sale_at; today (the date the min deposit and
--   non-refundable tiers are read at for a live line) in the event's time zone.
-- - trigger_document_line_set_cancellation_date: document_line.cancellation_date (a date, the day a
--   cancelled line's tiers are read at) is the day of the cancellation in the event's time zone.
--   Existing values are left as they were stamped.
-- - update_documents_min_deposit (nightly): a cutoff is reached for a booking when its day begins in the
--   booking's event time zone, not the session's.
--
-- Meanings: on_date is the first day with the rate, off_date the first day without it, and each cutoff
-- date the first day of the next tier - unchanged in the database. The client calculators (SiteItemBill
-- and Rates in modality-fork, SiteItemBill and policy-helpers in kbs3-react) treated off_date as the last
-- day WITH the rate; they now apply this meaning too.
--
-- Existing bookings are NOT recomputed by this migration: their stored prices change only when something
-- next triggers compute_document_prices for them. A booking created on its rate's off_date then loses
-- the discount the old client rule gave it.
--
-- Event 1857's two whole-festival rates (58143, 59653) had their off_date moved from 20 to 19 June by
-- scripts/fix-event-1857-early-bird-deadline.sql, so that the old client rule would end the early bird
-- announced as "on or before 11:59pm 19 June" at the right time. Under this meaning, 19 June would end it a
-- day early, so they are put back to 20 June (a no-op where the script was not run).
--
-- The rate's date columns are commented with their exact meaning (they had no comment), and
-- bookings_updated's comment now says which time zone its clock time is in.
--
-- Body of compute_document_prices = scripts/compute_document_prices.sql in the aggregate repo.

CREATE OR REPLACE FUNCTION public.event_time_zone(eid integer)
 RETURNS text
 LANGUAGE plpgsql
 STABLE
AS $function$
  -- The time zone of an event's days: the event's, then its organization's, then UTC. A misspelt zone falls
  -- back to UTC with a warning rather than failing the statement that needs it (a booking's save, a
  -- cancellation). A null event is UTC.
  DECLARE
    zone text;
  BEGIN
    select into zone coalesce(e.timezone, o.timezone) from event e left join organization o on o.id = e.organization_id where e.id = eid;
    zone := coalesce(zone, 'UTC');
    begin
      perform now() at time zone zone;
    exception when invalid_parameter_value then
      raise warning 'event_time_zone(%): unknown time zone "%", using UTC', eid, zone;
      zone := 'UTC';
    end;
    return zone;
  END;
$function$;

CREATE OR REPLACE FUNCTION public.rate_on_sale_at(r rate, at_instant timestamptz, zone text)
 RETURNS boolean
 LANGUAGE sql
 STABLE
AS $function$
    -- Whether the rate is on sale at that instant: from its on_date included to its off_date excluded,
    -- both being days in the given time zone. A null instant, like a null date, does not restrict.
    SELECT at_instant IS NULL
        OR ((r.on_date IS NULL OR r.on_date <= (at_instant AT TIME ZONE zone)::date)
        AND (r.off_date IS NULL OR (at_instant AT TIME ZONE zone)::date < r.off_date));
$function$;

CREATE OR REPLACE FUNCTION public.trigger_document_line_set_cancellation_date()
 RETURNS trigger
 LANGUAGE plpgsql
AS $function$
BEGIN
	-- A date, read against the rate's cutoff dates: the day of the cancellation in the event's time zone
	NEW.cancellation_date := (now() at time zone event_time_zone((select d.event_id from document d where d.id = NEW.document_id)))::date;
	return NEW;
END $function$;

CREATE OR REPLACE FUNCTION public.update_documents_min_deposit()
 RETURNS integer
 LANGUAGE plpgsql
AS $function$
  DECLARE
    updated integer;
  begin

perform set_transaction_parameters(true);
-- Arms the recompute of the live bookings whose rate has reached a new min deposit tier. A cutoff date is
-- a day in the booking's event time zone, so it is reached when that day begins there.
-- Candidates are the rates with a cutoff that has begun somewhere (UTC+14 is the first zone to enter a
-- day) and that their bookings were not yet updated for everywhere (UTC-12 is the last zone to leave
-- one); each booking is then checked in its own event's time zone. bookings_updated stays per rate, which
-- assumes a rate's bookings share one time zone: true of every rate with a 2025-2026 cutoff.
-- bookings_updated has no time zone: it holds now() as a clock time in the session's zone, so it is cast
-- back to timestamptz (in that same zone - the job always runs from the same server) before being read
-- in another zone. AT TIME ZONE applied to it directly would read it as a clock time in that other zone.
with candidates as (
	select r.id, r.item_id, r.site_id, r.bookings_updated::timestamptz as bookings_updated, c.cutoff
	from rate r, unnest(array[r.cutoff_date, r.cutoff_date2, r.cutoff_date3, r.cutoff_date4, r.cutoff_date5]) as c(cutoff)
	where c.cutoff <= (now() at time zone 'Etc/GMT-14')::date -- POSIX signs: Etc/GMT-14 is UTC+14
	  and (r.bookings_updated is null or (r.bookings_updated::timestamptz at time zone 'Etc/GMT+12')::date < c.cutoff)
),
bookings as (
	select distinct c.id as rate_id, c.bookings_updated, c.cutoff, d.id as document_id, d.event_id
	from candidates c
	join document_line dl on dl.item_id = c.item_id and dl.site_id = c.site_id and not dl.cancelled
	join document d on d.id = dl.document_id and not d.cancelled
),
zones as materialized (
	select event_id, event_time_zone(event_id) as zone from (select distinct event_id from bookings) e
),
reached as (
	select b.rate_id, b.document_id
	from bookings b join zones z on z.event_id is not distinct from b.event_id
	where b.cutoff <= (now() at time zone z.zone)::date
	  and (b.bookings_updated is null or (b.bookings_updated at time zone z.zone)::date < b.cutoff)
),
updates as (
	update "document" set trigger_defer_compute_prices = true where id in (select document_id from reached)
)
update rate set bookings_updated = now() where id in (select rate_id from reached);
GET DIAGNOSTICS updated = ROW_COUNT;
return updated;
	END;
$function$;

UPDATE public.rate SET off_date = DATE '2026-06-20' WHERE id IN (58143, 59653) AND off_date = DATE '2026-06-19';

COMMENT ON COLUMN public.rate.start_date IS
    'First attended day the rate can price (included); NULL = no lower bound. Compared with attendance dates, which are days too, so no time zone is involved. A stay''s length compared with min_day only counts the days within [start_date, end_date].';
COMMENT ON COLUMN public.rate.end_date IS
    'Last attended day the rate can price (included); NULL = no upper bound. Compared with attendance dates, which are days too, so no time zone is involved.';
COMMENT ON COLUMN public.rate.on_date IS
    'First day the rate can be booked (included); NULL = from the start. A line gets the rate when it is created on or after that day, the creation moment being read as a day in the event''s time zone (event_time_zone: event, then organization, then UTC).';
COMMENT ON COLUMN public.rate.off_date IS
    'First day the rate can NO LONGER be booked (excluded); NULL = no end. A line gets the rate when it is created before that day, the creation moment being read as a day in the event''s time zone (event_time_zone). An early bird ending on 20 August at 23:59 has off_date = 21 August, and the front office displays its deadline as "20 August, 23:59".';
COMMENT ON COLUMN public.rate.cutoff_date IS
    'First day of the second min deposit / non-refundable tier. Before it, min_deposit and non_refundable apply; from it, min_deposit2 and non_refundable2 (up to cutoff_date2). NULL = the first tier applies for ever, and the later cutoff dates are ignored. Read at today for a live line, at its cancellation_date for a cancelled one, both being days in the event''s time zone (event_time_zone). update_documents_min_deposit re-prices the bookings each night a cutoff is reached.';
COMMENT ON COLUMN public.rate.cutoff_date2 IS
    'First day of the third min deposit / non-refundable tier: min_deposit3 and non_refundable3 apply from it (min_deposit2 and non_refundable2 before it, from cutoff_date). NULL = the second tier applies for ever. Same reading as cutoff_date.';
COMMENT ON COLUMN public.rate.cutoff_date3 IS
    'First day of the fourth min deposit / non-refundable tier: min_deposit4 and non_refundable4 apply from it. NULL = the third tier applies for ever. Same reading as cutoff_date.';
COMMENT ON COLUMN public.rate.cutoff_date4 IS
    'First day of the fifth min deposit / non-refundable tier: min_deposit5 and non_refundable5 apply from it. NULL = the fourth tier applies for ever. Same reading as cutoff_date.';
COMMENT ON COLUMN public.rate.cutoff_date5 IS
    'First day of the sixth and last min deposit / non-refundable tier: min_deposit6 and non_refundable6 apply from it. NULL = the fifth tier applies for ever. Same reading as cutoff_date.';
COMMENT ON COLUMN public.rate.bookings_updated IS
    'When update_documents_min_deposit last re-priced the bookings of this rate for a reached cutoff date. A clock time without time zone, in the time zone of the database session that ran the job.';

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
      cheapest_specificity int;       -- specificity of the rate chosen for the current attendance
      rate_specificities int[];       -- specificity of each rate applicable to the current attendance
      candidate_prices int[];         -- attendance price of each rate applicable to the current attendance (null otherwise)
      candidate_ceilings bool[];      -- whether each rate applies only as a ceiling (stay shorter than its min_day)
      top_regular_specificity int;    -- most specific applicable rate that is not a ceiling
      chosen_index int;               -- index of the rate chosen for the current attendance
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
      event_zone text;                -- time zone of the rates' dates (on/off, cutoffs): see event_time_zone
BEGIN
      raise notice '>>> compute_document_prices(%)', document_id;
      -- A rate's dates are days in the event's time zone, as the client calculators read them. Comparing them
      -- with a timestamptz, or with now() cast to a date, would use the session's time zone instead.
      event_zone := event_time_zone((select d.event_id from document d where d.id = did));
      recs := array(
          with dls as (select d -- using a with statement to be able to use rate_item_id and rate_date in the final select for fetching rates
                  , dl
                  , coalesce((select rate_alias_item_id from item i where i.id=dl.item_id), dl.item_id) as rate_item_id
                  , case when dl.cancelled and dl.cancellation_date is not null then dl.cancellation_date else (now() at time zone event_zone)::date end as rate_date
                  , dl.site_id -- also used for fetching rates
                  , dl.id as document_line_id -- used for attendance join in the final select
                  , dl.cancelled or dl.abandoned as cancelled
                  , cast(row_number() over (order by dl.id) as int) as document_line_index
                  , cast((select count(*) from document_line dl where dl.document_id=d.id) as int) as document_lines_count
              from document_line dl join document d on d.id=dl.document_id
              where d.id=did and dl.item_id<>23)
          , dlrs as (select d, dl, rate_item_id, rate_date, site_id, document_line_id, cancelled, document_line_index, document_lines_count
                  , array(select r from rate r where r.site_id=dls.site_id and r.item_id=rate_item_id and rate_matches_document(r,d) and (true or kbs_overlaps(rate_date, rate_date, r.on_date, r.off_date)) and (not r.early_bird or (d).early_bird) order by r.event_id is not null desc, r.event_type_id is not null desc, coalesce(compute_rate_unit_price(r, d), 0) / case when r.per_day then 1 else
          coalesce(r.max_day, 1) end desc, case when (dls.dl).share_owner then coalesce(r.per_person, false) end, r.id) as rates
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
              rate_specificities := array_fill(0, ARRAY[rates_count]);
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
              cheapest_specificity := null;
              candidate_prices := array_fill(null::int, ARRAY[rates_count]);
              candidate_ceilings := array_fill(false, ARRAY[rates_count]);
              rate_specificities := array_fill(0, ARRAY[rates_count]);
              rate_index := 0;
              foreach rate in array rec.rates loop
                  rate_index := rate_index + 1;
                  rate_applicable := kbs_overlaps(attendance.date, attendance.date, rate.start_date, rate.end_date) and rate_on_sale_at(rate, document_line.creation_date, event_zone);
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
                      -- Recorded here and chosen after the loop: the specificity a ceiling competes with depends on
                      -- the other rates applicable to this attendance.
                      candidate_prices[rate_index] := attendance_price;
                      candidate_ceilings[rate_index] := rate_block_length < rate_min_day;
                      rate_specificities[rate_index] := case when rate.event_id is not null then 2 when rate.event_type_id is not null then 1 else 0 end;
                      if (trace) then raise notice '>>> Candidate rate rate_id = %, unit_price = %, consumed_day = %, attendance_price = %', rate.id, rate_unit_price, consumed_day, attendance_price; end if;
                  end if;
              end loop;
              -- The most specific applicable rate wins whatever its price (event > event type > generic); among rates of
              -- the same specificity the cheapest wins, exact ties keeping the array order. A rate applying only as a
              -- ceiling (stay shorter than its min_day) caps the price: it competes at the lower of its own specificity
              -- and that of the most specific other applicable rate, so it caps the rates of its scope but never
              -- overrides a less specific rate.
              top_regular_specificity := null;
              for rate_index in 1 .. rates_count loop
                  if (candidate_prices[rate_index] is not null and not candidate_ceilings[rate_index]) then
                      top_regular_specificity := greatest(top_regular_specificity, rate_specificities[rate_index]);
                  end if;
              end loop;
              chosen_index := null;
              for rate_index in 1 .. rates_count loop
                  if (candidate_prices[rate_index] is not null) then
                      if (candidate_ceilings[rate_index]) then
                          rate_specificities[rate_index] := least(rate_specificities[rate_index], coalesce(top_regular_specificity, rate_specificities[rate_index]));
                      end if;
                      if (chosen_index is null or rate_specificities[rate_index] > cheapest_specificity
                          or (rate_specificities[rate_index] = cheapest_specificity and candidate_prices[rate_index] < cheapest_attendance_price)) then
                          chosen_index := rate_index;
                          cheapest_attendance_price := candidate_prices[rate_index];
                          cheapest_specificity := rate_specificities[rate_index];
                      end if;
                  end if;
              end loop;
              if (chosen_index is not null) then
                  -- memorizing applied rate to the document_line for min_deposit, non_refundable, and custom_price computation
                  document_line_rates[rec.document_line_index] := rec.rates[chosen_index];
                  document_line_rate_indexes[rec.document_line_index] := chosen_index;
                  if (trace) then raise notice '>>> Cheapest rate rate_id = %, attendance_price = %', rec.rates[chosen_index].id, cheapest_attendance_price; end if;
              end if;
              block_attendance_price := coalesce(cheapest_attendance_price, 0);
              for rate_index in 1 .. rates_count loop
                  if (consumed_days[rate_index] > 0 and rate_specificities[rate_index] < cheapest_specificity) then
                      -- A more specific rate priced this attendance: a less specific fixed rate must not count that
                      -- price towards its own ceiling, so its window starts again the next time it applies.
                      consumed_days[rate_index] := 0;
                      consumed_prices[rate_index] := 0;
                  elsif (consumed_days[rate_index] > 0) then
                      consumed_prices[rate_index] := consumed_prices[rate_index] + block_attendance_price;
                      -- if (trace) then    raise notice '>> rate_id = %, consumed_day = %, consumed_price = %', rec.rates[rate_index].id, consumed_days[rate_index], consumed_prices[rate_index]; end if;
                  end if;
              end loop;
              if (trace) then raise notice '> attendance: date = %, price = %', attendance.date, block_attendance_price; end if;
          elsif (not document.abandoned and not document_line.abandoned and attendance is null and rec.rates is not null) then
              -- No attendance: check for non-per-day rates (fixed fees not tied to individual attendance days)
              foreach rate in array rec.rates loop
                  -- The rates are ordered most specific first, so a fee rate whose on/off dates exclude the line's
                  -- creation must be skipped here, or a lapsed specific fee would win over a valid generic one.
                  if (not rate.per_day and rate_on_sale_at(rate, document_line.creation_date, event_zone)) then
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
              -- null when no fee rate matched: the last rate looked at must not supply its fixed amounts below
              rate := document_line_rates[rec.document_line_index];
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
                  r_date := case when document_line.cancelled and document_line.cancellation_date is not null then document_line.cancellation_date else (now() at time zone event_zone)::date end;
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
