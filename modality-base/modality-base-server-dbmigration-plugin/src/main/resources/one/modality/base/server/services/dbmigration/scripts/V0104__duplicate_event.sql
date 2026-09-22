-- Duplicating a KBS3 event: the new event row, then its programme.
--
-- The back office's "Create event" tile creates next year's festival from last year's, and the
-- whole of it has to be one transaction: an event row whose programme copy then failed would be
-- an empty shell nobody asked for, sitting in the event list with the name somebody just typed.
--
-- So it is one function. `copy_event_scheduled_items` (V0041) already copies the programme --
-- scheduled items, phases, parts, coverages, selections and the boundaries they reference,
-- every date shifted by the gap between the two events' start dates. What was missing was the
-- row it copies INTO, and the question of which of `event`'s hundred-odd columns come with it.
--
-- ── What is copied, cleared and shifted ──────────────────────────────────────────────────────
--
-- The rule: copy what describes the KIND of event, clear what identifies THIS occurrence of it,
-- shift what is a date.
--
--   * COPIED -- organization, corporation, type, venue, activity, currency, timezone, frontend,
--     theme and its colour overrides, the booking-form layout, every message label, the terms
--     URLs, the day-ticket / in-person / online / VOD / recurring flags, the descriptions, the
--     default pool (pools are global, not event-scoped). These are what makes it the same event
--     again.
--
--   * CLEARED -- `slug`, `uri` and `external_link`, which address THIS occurrence on the public
--     web; `livestream_url` and the two Bunny VOD ids, which point at this occurrence's stream
--     and library (5 of the 5 kbs3 events carrying a library id carry a distinct one); the four
--     `bookings_auto_confirm_event_id*` references and the auto-confirm letter, which name other
--     specific events and a letter belonging to the source; and `series_id` and
--     `repeated_event_id`, because a duplicate joins nothing by being made. A duplicate carrying
--     last year's slug or stream URL is not a new event, it is a second door onto the old one.
--
--   * OFF -- `state` starts at DRAFT and `advertised`, `live`, `active`, `livestream_live`,
--     `booking_closed`, `bookings_auto_confirm` and `accounting_exported` all start false. A
--     duplicate is a draft somebody is about to edit; nothing about making one says it should be
--     visible to the public, let alone open for booking. Same policy as the series rollover
--     (populateDuplicateSeriesChangeSet), for the same reason.
--
--   * SHIFTED by the same day gap as the programme -- opening_date, booking_process_start,
--     booking_closing_date, payment_closing_date, pre_date, post_date, and the audio/VOD
--     expiration and closing dates. They describe when things happen RELATIVE to the event, so
--     moving the event moves them; leaving them would open next year's bookings in the past.
--
--   * `uuid` is left to its own default, so the copy gets a fresh one -- it is unique, and it is
--     how a row is named outside the database.
--
-- ── The three content labels ─────────────────────────────────────────────────────────────────
--
-- `label_id` (the event's TRANSLATED title) is set to NULL rather than copied. The caller has
-- just typed the new event's name, and every reader falls back to `event.name` when the label is
-- absent (resolveLabelText(...) ?? event.name); copying the source's label would make the front
-- office show last year's title under this year's name until somebody noticed.
--
-- The short and long description labels ARE copied, into FRESH label rows -- last year's blurb
-- is the right starting point, and sharing the row would mean editing the copy silently rewrote
-- the source event's description too.
--
-- ── Failures ─────────────────────────────────────────────────────────────────────────────────
--
-- The programme copy can legitimately fail: it rebinds boundaries that point at items NOT bound
-- to the event (Lunch, Dinner) to the destination item for the same site and item on the shifted
-- date, and raises when the venue has none there -- which is what a festival duplicated into a
-- year whose venue-global meals have not been generated yet looks like. That is re-raised with a
-- PROGRAM_COPY_FAILED marker so the endpoint can tell the back office which of the two it was,
-- and the whole transaction rolls back: no half-made event.
--
-- It can also fail QUIETLY, which is why there is a post-condition below: that same rebinding
-- accepts an item belonging to another event, so a missing venue session can be papered over with
-- a neighbouring event's row instead of refused. Checked here rather than watched in the NOTICEs,
-- because this runs from a button now.
--
-- Called by DuplicateEventEndpoint (modality-event-server-event-plugin), which checks first that
-- the caller may reach /create-event IN THE SOURCE EVENT'S organization. The new event lands in
-- that same organization -- the caller never says where it goes.

CREATE OR REPLACE FUNCTION duplicate_event(src_event_id integer, new_name text, new_start_date date)
RETURNS integer
LANGUAGE plpgsql
AS $$
DECLARE
    src          event%ROWTYPE;
    v_name       text;
    v_days       integer;
    v_new_id     integer;
    v_short_label integer;
    v_long_label  integer;
BEGIN
    v_name := btrim(coalesce(new_name, ''));
    IF v_name = '' THEN
        RAISE EXCEPTION 'duplicate_event: the new event needs a name';
    END IF;
    IF length(v_name) > 64 THEN
        RAISE EXCEPTION 'duplicate_event: the new event name is longer than 64 characters';
    END IF;
    IF new_start_date IS NULL THEN
        RAISE EXCEPTION 'duplicate_event: the new event needs a start date';
    END IF;

    SELECT * INTO src FROM event WHERE id = src_event_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'duplicate_event: source event % not found', src_event_id;
    END IF;
    IF src.start_date IS NULL OR src.end_date IS NULL THEN
        RAISE EXCEPTION 'duplicate_event: source event % has no start or end date', src_event_id;
    END IF;

    -- The gap the programme copy will use too: it reads both events' start_date and computes the
    -- same number, so the event row and its items move together by construction.
    v_days := new_start_date - src.start_date;

    -- Descriptions: copied into fresh rows, so editing the copy leaves the source alone.
    IF src.short_description_label_id IS NOT NULL THEN
        INSERT INTO label (ref, de, el, en, es, fr, pt, vi, zhs, zht, organization_id, alert, livestream_message)
        SELECT ref, de, el, en, es, fr, pt, vi, zhs, zht, organization_id, alert, livestream_message
          FROM label WHERE id = src.short_description_label_id
        RETURNING id INTO v_short_label;
    END IF;
    IF src.long_description_label_id IS NOT NULL THEN
        INSERT INTO label (ref, de, el, en, es, fr, pt, vi, zhs, zht, organization_id, alert, livestream_message)
        SELECT ref, de, el, en, es, fr, pt, vi, zhs, zht, organization_id, alert, livestream_message
          FROM label WHERE id = src.long_description_label_id
        RETURNING id INTO v_long_label;
    END IF;

    INSERT INTO event (
        -- identity given by the caller
        name, start_date, end_date,
        -- what the event belongs to
        organization_id, corporation_id, activity_id, type_id, venue_id, currency_id, timezone, frontend,
        -- content
        label_id, short_description_label_id, long_description_label_id, description, short_description,
        image_id, buddha_id, teacher_id, css_class,
        -- theme
        theme_id, theme_base_color, theme_accent_color, theme_border_color, theme_surface_color,
        theme_strong_background,
        -- booking form & messages
        booking_form_layout_id, booking_success_message_id, cart_message_id, fees_bottom_label_id,
        options_top_label_id, support_notice_label_id, privacy_notice_label_id,
        in_person_terms_label_id, online_terms_label_id, livestream_message_label_id,
        notification_cart_default_label_id, notification_cart_payed_label_id,
        notification_cart_confirmed_label_id, notification_cart_payed_confirmed_label_id,
        terms_url_en, terms_url_es, terms_url_fr, terms_url_de, terms_url_pt,
        -- livestream guidance (the messages, not the stream)
        livestream_audio_issue, livestream_audio_issue_label_id,
        livestream_video_issue, livestream_video_issue_label_id,
        livestream_connection_issue, livestream_connection_issue_label_id,
        livechat_url,
        -- how it is sold
        kbs3, teachings_day_ticket, audio_recordings_day_ticket, in_person_allowed, online_allowed,
        vod_enabled, vod_processing_time_minutes, recurring_with_audio, recurring_with_video,
        repeatable, repeat_audio, repeat_video, early_bird, no_account_booking, pass_control,
        pass_control_date_time_range, show_sites, use_frontend_accounts, send_history_emails,
        options_hide_days_outside_event, arrival_departure_from_dates_sections,
        option_rounding_factor, default_pool_id, host,
        date_time_range, min_date_time_range, max_date_time_range, facebook_conversion_pixel_id,
        -- dates, shifted with the event
        opening_date, booking_process_start, booking_closing_date, payment_closing_date,
        pre_date, post_date, audio_expiration_date, vod_expiration_date, audio_closing_date,
        -- a duplicate starts as an unpublished draft
        state, advertised, live, active, livestream_live, booking_closed, bookings_auto_confirm,
        accounting_exported)
    SELECT
        v_name, new_start_date, new_start_date + (src.end_date - src.start_date),
        src.organization_id, src.corporation_id, src.activity_id, src.type_id, src.venue_id,
        src.currency_id, src.timezone, src.frontend,
        NULL,                                  -- label: the caller just named this event
        v_short_label, v_long_label, src.description, src.short_description,
        src.image_id, src.buddha_id, src.teacher_id, src.css_class,
        src.theme_id, src.theme_base_color, src.theme_accent_color, src.theme_border_color,
        src.theme_surface_color, src.theme_strong_background,
        src.booking_form_layout_id, src.booking_success_message_id, src.cart_message_id,
        src.fees_bottom_label_id, src.options_top_label_id, src.support_notice_label_id,
        src.privacy_notice_label_id, src.in_person_terms_label_id, src.online_terms_label_id,
        src.livestream_message_label_id,
        src.notification_cart_default_label_id, src.notification_cart_payed_label_id,
        src.notification_cart_confirmed_label_id, src.notification_cart_payed_confirmed_label_id,
        src.terms_url_en, src.terms_url_es, src.terms_url_fr, src.terms_url_de, src.terms_url_pt,
        src.livestream_audio_issue, src.livestream_audio_issue_label_id,
        src.livestream_video_issue, src.livestream_video_issue_label_id,
        src.livestream_connection_issue, src.livestream_connection_issue_label_id,
        src.livechat_url,
        src.kbs3, src.teachings_day_ticket, src.audio_recordings_day_ticket, src.in_person_allowed,
        src.online_allowed, src.vod_enabled, src.vod_processing_time_minutes,
        src.recurring_with_audio, src.recurring_with_video,
        src.repeatable, src.repeat_audio, src.repeat_video, src.early_bird, src.no_account_booking,
        src.pass_control, src.pass_control_date_time_range, src.show_sites, src.use_frontend_accounts,
        src.send_history_emails, src.options_hide_days_outside_event,
        src.arrival_departure_from_dates_sections,
        src.option_rounding_factor, src.default_pool_id, src.host,
        src.date_time_range, src.min_date_time_range, src.max_date_time_range,
        src.facebook_conversion_pixel_id,
        src.opening_date          + (v_days || ' days')::interval,
        src.booking_process_start + (v_days || ' days')::interval,
        src.booking_closing_date  + (v_days || ' days')::interval,
        src.payment_closing_date  + (v_days || ' days')::interval,
        src.pre_date  + v_days,
        src.post_date + v_days,
        src.audio_expiration_date + (v_days || ' days')::interval,
        src.vod_expiration_date   + (v_days || ' days')::interval,
        src.audio_closing_date    + (v_days || ' days')::interval,
        'DRAFT', false, false, false, false, false, false, false
    RETURNING id INTO v_new_id;

    -- The programme, with every date shifted by the same gap. Its own guards apply: it refuses a
    -- destination that already has scheduled items, and it refuses to rebind a boundary it cannot
    -- resolve rather than leaving a programme pointing at the wrong day.
    BEGIN
        PERFORM copy_event_scheduled_items(src_event_id, v_new_id);
    EXCEPTION WHEN raise_exception THEN
        -- Only what copy_event_scheduled_items RAISEd itself is described as a programme failure;
        -- a timeout, a deadlock or a disk error is re-raised as itself below, because telling the
        -- back office that the venue's meals are missing when the truth is a lock wait sends
        -- somebody to check the wrong thing. Marked so the endpoint can say WHICH half failed.
        -- The whole transaction rolls back either way -- the event row above goes with it, which
        -- is the point of doing both here.
        RAISE EXCEPTION 'duplicate_event: PROGRAM_COPY_FAILED copying event % into new event %: %',
            src_event_id, v_new_id, SQLERRM;
    END;

    -- Post-condition: no boundary of the new event may depend on ANOTHER event's scheduled item.
    --
    -- copy_event_scheduled_items resolves a boundary that points at a non event-bound item (Lunch,
    -- Dinner) to "the item with this site, item and shifted date", and that search does not exclude
    -- items bound to some other event. Where the venue's own row is missing on the new dates but a
    -- neighbouring event happens to have one of its own, the copy binds to it and reports success:
    -- the new event's phase or part would then start when a row belonging to somebody else's event
    -- says so, and vanish if that event is edited. Nothing in the function is wrong for the run it
    -- was written for -- a DBA copying one event and reading the NOTICEs -- but this is now a
    -- button, so the outcome is checked rather than watched.
    --
    -- Reported as the same programme failure the missing-row case reports, because it IS that case:
    -- the venue's sessions are not there for those dates, and what was found instead is not a
    -- substitute. The whole transaction rolls back.
    IF EXISTS (
        SELECT 1
          FROM scheduled_boundary sb
          JOIN scheduled_item si ON si.id = sb.scheduled_item_id
         WHERE sb.event_id = v_new_id
           AND si.event_id IS NOT NULL
           AND si.event_id <> v_new_id)
    THEN
        RAISE EXCEPTION 'duplicate_event: PROGRAM_COPY_FAILED copying event % into new event %: a boundary was bound to another event''s scheduled item, so the venue has no session of its own on those dates',
            src_event_id, v_new_id;
    END IF;

    RAISE NOTICE 'Duplicated event % into event % "%" starting % (shift % days)',
        src_event_id, v_new_id, v_name, new_start_date, v_days;

    RETURN v_new_id;
END;
$$;
