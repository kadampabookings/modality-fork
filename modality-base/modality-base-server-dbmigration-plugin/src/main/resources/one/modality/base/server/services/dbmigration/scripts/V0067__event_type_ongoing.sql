-- V0067: mark event types whose events are ONGOING containers rather than
-- discrete events — Stays, Residents, Volunteering and the like, which run for
-- months or years (e.g. "Volunteer Visits" Jul–Dec, "Volunteering - kbs3"
-- 2026–2028) and drown the back-office event Gantt strip in full-width bars.
--
-- ("ongoing" and not "background": event_type.background already exists — a
-- legacy KBS2 varchar for display styling.)
--
-- The strip hides ongoing events by default (a persisted toggle reveals
-- them); the events dropdown always lists everything, so they stay selectable
-- for admin work. Classification is deliberately on the TYPE, not on event
-- duration: long legitimate events exist (a multi-month STTP with hundreds of
-- bookings must never be hidden) and short volunteering weeks are still
-- ongoing-kind. This also formalises what the Java gantt hardcoded
-- (EventsGanttCanvas excludes typeId 61 with a "hardcoded for now" comment).
--
-- The seed below flags the clearly-ongoing types by name (event_type is
-- organization-scoped). Refine per organization by flipping the flag:
--   update event_type set ongoing = true|false where id = ...;
-- null/false = normal event type. Note two known CMKF ongoing events
-- (315 "Résidents", 1856 "Indisponibilités") currently have NO type at all —
-- they only start hiding once given an ongoing-flagged type.

alter table event_type add column if not exists ongoing boolean;

update event_type set ongoing = true
 where lower(name) in (
   'not event',          -- 45  Manjushri: Stays / Volunteer Visits / Volunteering
   'volunteers',         -- 65  global
   'bénévolat',          -- 49  CMKF volunteering stays
   'résidents',          -- 57  CMKF residents
   'visiteurs',          -- 58  CMKF visitors
   'séjours personnels'  -- 17  CMKF personal stays
 );
