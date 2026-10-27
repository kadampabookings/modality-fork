-- V0144: label.it -- Italian text on labels.
--
-- WHY. The Summer Festival 2026 audio recordings are now published in Italian as well as the other
-- languages. An audio track is titled from its session's label in the track's own language
-- (kbs3-react shared/src/utils/audio-track-title.ts), and `label` had no Italian column, so Italian
-- tracks could only be titled in English. Greek already had one (`el`).
--
-- Nothing else needs to change for Italian to be read:
--   - label() and lang_or_default() (V0053) find a label's language columns in the catalog, so a
--     letter in Italian now takes `it` where it is filled and still falls back to `en` where not;
--   - the Java entity interface (EntityHasI18nFields) already declared `it`;
--   - the domain model snapshot gains Label.it (field 2245) and `?lang='it' ? it :` in Label.text,
--     in the same change.
--
-- duplicate_event (V0143) is recreated unchanged except that it copies `it` with the other language
-- columns when it copies an event's description labels, so a duplicate keeps its Italian text.
--
-- Not changed: the change-notification trigger record_changes_for_notification_label, which never
-- listed zhs, zht, el or vi either.
--
-- NULL for every existing row. Anonymiser: `label` is classified whole-table `kept` in
-- scripts/gdpr-anonymise/10-anon-helpers.sql; Italian text is the same kind of text as the other
-- language columns, and the row's reason now names it.

ALTER TABLE label
    ADD COLUMN IF NOT EXISTS it text;

COMMENT ON COLUMN label.it IS 'Italian';

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
        INSERT INTO label (ref, de, el, en, es, fr, it, pt, vi, zhs, zht, organization_id, alert, livestream_message)
        SELECT ref, de, el, en, es, fr, it, pt, vi, zhs, zht, organization_id, alert, livestream_message
          FROM label WHERE id = src.short_description_label_id
        RETURNING id INTO v_short_label;
    END IF;
    IF src.long_description_label_id IS NOT NULL THEN
        INSERT INTO label (ref, de, el, en, es, fr, it, pt, vi, zhs, zht, organization_id, alert, livestream_message)
        SELECT ref, de, el, en, es, fr, it, pt, vi, zhs, zht, organization_id, alert, livestream_message
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
        repeatable, repeat_audio, repeat_video, external_audio, early_bird, no_account_booking,
        pass_control,
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
        src.repeatable, src.repeat_audio, src.repeat_video, src.external_audio, src.early_bird,
        src.no_account_booking,
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
