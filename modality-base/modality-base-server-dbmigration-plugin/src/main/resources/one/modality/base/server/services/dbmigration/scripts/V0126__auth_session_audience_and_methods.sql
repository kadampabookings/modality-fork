-- Records, on each session family, which app it was opened FOR and how it was authenticated —
-- the $aud and $amr claims the token already carries, written where they can be queried.
--
-- WHY: reuse detection ends about 3.4 front-office families a day (2026-10-13), every one of them a
-- session with two holders, one of which woke up two generations behind. What kind of login produces
-- a stranded second holder is unanswerable today, because auth_session records no method: the first
-- attempt had to correlate `issued` against magic_link.usage_date, which cannot see BOOKING_ACCESS
-- redeems at all (they are never marked used) and found 2 of 7 with no mechanism behind it. With
-- these two columns the question is one GROUP BY, and — the part a log line cannot give — it can be
-- asked of the LIVE families too, so a method's share of revocations can be compared with its share
-- of sessions. A rate means nothing without its denominator.
--
-- Both are written once, at open(), and never updated: the claims are frozen at login and a renewal
-- carries them forward unchanged, so a row that disagreed with its token could only mean a bug.
--
-- NULL means "this row predates the column", not "none" — the same reading $amr has in the token,
-- and for the same reason: there is nothing to infer it from, and guessing the common case would
-- manufacture the fact somebody is asking about. Sessions open at deploy keep NULL for their
-- lifetime; back-office ones are capped at a day, front-office ones can carry it for a year.
--
-- Widths match the vocabularies, not the hope of growth: SessionAudience codes are 'fo' and 'bo',
-- and $amr is a comma-separated list of SessionMethod codes, the longest possible today being
-- 'pwd,pk,totp,ml,code,cart,sv,bov' at 31 characters.
--
-- No anonymiser change: auth_session is classified ('auth_session','*','truncated') and the staging
-- refresh empties the whole table, so these are dropped with everything else. Re-read rather than
-- assumed — a '*' row silently admits new columns, and the one disposition this would have to change
-- for is 'not-pii'. It is not: the reason on that row is that the table records when each member was
-- online, and recording how they authenticate says, if anything, more about them.

alter table auth_session add column audience varchar(2);
alter table auth_session add column methods varchar(64);

comment on column auth_session.audience is
    'Which app this session was opened FOR, as the token''s $aud says: fo | bo. NULL before V0126.';
comment on column auth_session.methods is
    'How it was authenticated, as the token''s $amr says: comma-separated SessionMethod codes, e.g. pwd,totp. NULL before V0126, which is "not recorded" rather than "none".';
