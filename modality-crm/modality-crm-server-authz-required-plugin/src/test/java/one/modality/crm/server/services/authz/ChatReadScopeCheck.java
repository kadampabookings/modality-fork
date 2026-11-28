package one.modality.crm.server.services.authz;

import dev.webfx.platform.console.spi.ConsoleProvider;
import dev.webfx.platform.service.SingleServiceProvider;

import java.util.ArrayList;
import java.util.List;

/**
 * The chat read rules themselves — which entities are scoped, by what, and which are left alone.
 *
 * <p>Asserted as DQL text rather than through the compiler, deliberately. What the sql comes out as is
 * pinned in webfx-stack-fork's {@code ChatReadScopeCheck}, against the compiler and both injection points;
 * what is pinned HERE is the rule — that a message is bounded by its conversation and not by its author,
 * that the centre boundary is the event and not the organisation, and that an entity nobody named is
 * untouched. Those are the decisions a reader would want to argue with, and they are the ones that do not
 * need a domain model to state.
 *
 * <p>It also means these hold while {@code ENFORCING} is false, which is now. A check that could only run
 * after the flag flipped would be written after the flag flipped.
 *
 * @author Claude Code
 */
public class ChatReadScopeCheck {

    static int pass = 0, fail = 0;

    public static void main(String[] args) {
        // ── The member: their own conversations, and everything hanging off them ─────────────────────────
        check("a member's conversations are their own",
              "viewerPerson=CALLER_PERSON", dql("Conversation", ChatReadScope.MEMBER));
        check("a member's messages are bounded by the conversation, not by who wrote them",
              "conversation in (select id from Conversation where viewerPerson=CALLER_PERSON)",
              dql("ChatMessage", ChatReadScope.MEMBER));
        check("and reactions two hops out, still by conversation",
              "message in (select id from ChatMessage where conversation in"
            + " (select id from Conversation where viewerPerson=CALLER_PERSON))",
              dql("ChatMessageReaction", ChatReadScope.MEMBER));
        check("a member reads only agents' presence, never another viewer's last-seen",
              "agent", dql("ChatPresence", ChatReadScope.MEMBER));
        check("and only their own participant row",
              "person=CALLER_PERSON", dql("ConversationParticipant", ChatReadScope.MEMBER));

        // ── The back office: the centres it is granted, expressed as EVENTS ──────────────────────────────
        // BOTH kinds of grant, and the second arm is why the inbox still works. A grant row names an event,
        // an event type, or neither, and an organisation-wide one never widens into eventIds — 33 of 43
        // staging rows are organisation-wide and 18 of 25 users hold no event grant at all, so the granted-
        // events arm alone binds an empty array for most operators and returns nothing. Measured before the
        // flip, which is the only time it is worth measuring.
        String grantedEvents = "event in CALLER_GRANTED_EVENTS"
            + " or event in (select id from Event where organization in CALLER_GRANTED_ORGANIZATIONS)";
        check("a back office is bounded by its granted events OR its granted organisations",
              grantedEvents, dql("Conversation", ChatReadScope.BACKOFFICE));
        check("and its messages through those conversations",
              "conversation in (select id from Conversation where " + grantedEvents + ")",
              dql("ChatMessage", ChatReadScope.BACKOFFICE));
        check("presence too", grantedEvents, dql("ChatPresence", ChatReadScope.BACKOFFICE));
        // The organisation arm must be a SUBQUERY: event.organization is a path, and a path in a scope
        // condition is refused at both injection points.
        for (String entity : new String[] {"Conversation", "ChatMessage", "ChatMessageReaction", "ChatPresence"})
            check("the organisation arm never navigates (" + entity + ")",
                  !String.valueOf(dql(entity, ChatReadScope.BACKOFFICE)).contains("event.organization"));

        // THE ONE WORTH ARGUING WITH: conversation.organization_id is NULL on every production row, and the
        // front office sets it only when a config resolves one. A rule on it would match nothing today and
        // some rows later, silently. So no rule mentions it.
        for (String entity : new String[] {"Conversation", "ChatMessage", "ChatMessageReaction",
                                           "ConversationParticipant", "ChatPresence"})
            for (String scope : new String[] {ChatReadScope.MEMBER, ChatReadScope.BACKOFFICE}) {
                String rule = dql(entity, scope);
                // The prohibition is on the SCOPED ROW's own organization column — conversation.
                // organization_id, NULL on every production row — not on the word. The back-office arm
                // reaches an organisation through `select id from Event where organization in …`, which is
                // Event's column and is how most operators are granted anything at all. So the sanctioned
                // subquery is removed first and nothing named organization may remain: this still fails the
                // rule it was written to fail, and no longer fails the one that replaced it.
                String outsideEventSubquery = rule == null ? "" : rule.replace(
                    "(select id from Event where organization in CALLER_GRANTED_ORGANIZATIONS)", "");
                check("no rule leans on the conversation's own organization (" + entity + "/" + scope + ")",
                      !outsideEventSubquery.contains("organization"));
                // A path in a condition is refused at both injection points; a subquery is not. Every hop
                // these rules take is inside a select, so none of them contains a dotted navigation.
                check("and none of them navigates (" + entity + "/" + scope + ")",
                      rule == null || !rule.matches(".*\\b[a-z][A-Za-z]*\\.[a-z].*"));
            }

        // Person and Document carry the same prohibition, in a loop of their own. The organisation rule
        // above is about the CONVERSATION's own column and must not be applied to them: Person's
        // back-office arm tests `organization in CALLER_GRANTED_ORGANIZATIONS` against Person's own
        // column on purpose — it is the entire left-hand arm, and the 2026-11-29 measurement says it
        // carries 3,504 of org 151's people that no booking would reach.
        for (String entity : new String[] {"Person", "Document"})
            for (String scope : new String[] {ChatReadScope.MEMBER, ChatReadScope.BACKOFFICE}) {
                String rule = dql(entity, scope);
                // Non-null is part of the assertion: both roots now have a rule under both scopes, so a
                // null here is a rule that has gone missing rather than a rule that has no paths.
                check("a rule exists and takes no path (" + entity + "/" + scope + ")",
                      rule != null && !rule.matches(".*\\b[a-z][A-Za-z]*\\.[a-z].*"));
            }

        // ── Everything else is untouched ─────────────────────────────────────────────────────────────────
        check("an entity no scope owns still has no rule", dql("Organization", ChatReadScope.MEMBER) == null);
        check("and an unknown scope scopes nothing, rather than guessing",
              dql("Conversation", "some.other.scope") == null);
        check("including a null one, which is what a server-origin read compiles under",
              dql("Conversation", null) == null);

        // ── The reported line must match the census that counts it ──────────────────────────────────────
        // The regex below is query 7e of scripts/check-prod-logs-read-aspect.sh, copied. A message that
        // drifts from its filter is a census that reports a confident zero, which this project has shipped
        // before: the write side needed a new filter for a new wording and did not get one, and 6a of that
        // same script reported nothing for days because of an unescaped paren.
        String line = ChatReadScope.logLine("ChatMessage", ChatReadScope.MEMBER);
        check("the reported line matches the census regex",
              line.matches(".*(WOULD SCOPE|SCOPING|UNSCOPED) READ of [A-Za-z]+ under [a-z.]+"));
        String unscoped = ChatReadScope.unscopedLogLine("Conversation", ChatReadScope.UNRESTRICTED);
        check("the unscoped line takes the same shape, so 7e parses it with the other two",
              unscoped.matches(".*(WOULD SCOPE|SCOPING|UNSCOPED) READ of [A-Za-z]+ under [a-z.]+"));
        check("and is NOT mistaken for the enforcing mode, which would read as a root having been flipped",
              !unscoped.contains("SCOPING READ"));
        check("and it names the entity and the scope",
              line.contains("ChatMessage") && line.contains(ChatReadScope.MEMBER));
        // The statement never goes in the line: its literals are a conversation id, and through it a person.
        check("and carries nothing that could identify a conversation or a person",
              !line.contains("$") && !line.contains("select") && !line.contains("where"));

        // ── Observing: consulted, reported, and changing nothing ─────────────────────────────────────────
        check("but the scope IS consulted, or observing would report nothing", ChatReadScope.OBSERVING);
        // Per ROOT, not one switch for all five: they carry different rules over different data, and a
        // single flag would make the first flip the riskiest one available. Conversation goes first — it is
        // the only rule that is a local column test, so what it returns is the easiest to reason about, and
        // it is where the exposure is: any signed-in member could read any conversation by changing one id.
        // The 11 Nov rehearsal sent this back to none, because the back-office arm returned nothing for a
        // caller whose scope was UNRESTRICTED (empty arrays) or stale (null). BOTH are fixed — a third
        // token whose rules are null, and a retriable refusal instead of a null bind — so the first three
        // are enforced again for the staging rehearsal of precondition 3. This assertion is the tripwire
        // that says so out loud: it must be changed deliberately, never drift.
        for (String entity : new String[] {"Conversation", "ChatMessage", "ChatMessageReaction"})
            check("the first flip's roots are enforced (" + entity + ")",
                  ChatReadScope.isEnforced(entity));
        // The two nobody has asked the question of yet — ConversationParticipant is read only as the
        // caller's own row today, ChatPresence is about agents rather than a conversation.
        for (String entity : new String[] {"ConversationParticipant", "ChatPresence"})
            check("and a root outside the first flip is not (" + entity + ")",
                  !ChatReadScope.isEnforced(entity));
        check("an entity no scope owns is not enforced either",
              !ChatReadScope.isEnforced("Organization"));

        // ── PERSON, both arms, and STAGING ONLY ────────────────────────────────────────────────────────
        // The same tripwire as the chat roots above, for the same reason: it must be changed deliberately,
        // never drift. This one carries a second condition the chat ones do not — it must not reach the
        // prod branch while the 11,245 persons it reaches by neither arm are undecided (re-measured
        // 2026-11-29; 11,235 on 2026-10-19, so the population is stable rather than shrinking).
        check("Person is enforced (staging only - see PersonReadScope)",
              ChatReadScope.isEnforced("Person"));
        check("a member reads the people on their own account",
              "frontendAccount=CALLER_ACCOUNT"
            + " or accountPerson in (select id from Person where frontendAccount=CALLER_ACCOUNT)",
              dql("Person", ChatReadScope.MEMBER));
        check("a back office reads its centres' people and its events' bookers",
              "organization in CALLER_GRANTED_ORGANIZATIONS"
            + " or id in (select person from Document where " + grantedEvents + ")",
              dql("Person", ChatReadScope.BACKOFFICE));
        // THE ARM THAT WOULD FAIL SILENTLY. Most production grants are organisation-wide, and such a grant
        // binds CALLER_GRANTED_EVENTS EMPTY (confirmed by the 2026-11-25 production run). Written as
        // "event in CALLER_GRANTED_EVENTS" alone, the booking arm matches nothing for exactly the callers
        // it exists for: organisation 1 has 20 people by centre against 25,359 by booking, so its operator
        // would see 20 of 25,367 and the screen would look broken rather than narrowed.
        check("and its booking arm expands organisations into events, or it matches nothing for an org grant",
              String.valueOf(dql("Person", ChatReadScope.BACKOFFICE))
                  .contains("select id from Event where organization in CALLER_GRANTED_ORGANIZATIONS"));
        check("and a super admin gets no predicate for Person either",
              dql("Person", ChatReadScope.UNRESTRICTED) == null);

        // ── DOCUMENT: a booking, scoped for the two callers that should not read all of them ────────────
        // Until 2026-11-29 this root was in no read scope at all: a member, a centre operator and a
        // platform super administrator each counted all 294,713, and document carries 56 denormalised
        // person_ columns. Closing Person to a member never closed the copy of it living here.
        check("Document is enforced (staging only - see DocumentReadScope)",
              ChatReadScope.isEnforced("Document"));
        check("a member reads the bookings of the people on their own account",
              "person in (select id from Person where frontendAccount=CALLER_ACCOUNT"
            + " or accountPerson in (select id from Person where frontendAccount=CALLER_ACCOUNT))",
              dql("Document", ChatReadScope.MEMBER));
        // Document.event is a foreign key on the row, so this needs no subquery over people and no
        // exists: 49 ms for organisation 1 against 997 ms for the Person rule over the same grant.
        check("a back office reads the bookings of its granted events",
              grantedEvents, dql("Document", ChatReadScope.BACKOFFICE));
        // The line says which mode each root is in, so ONE census tells observing from enforcing per root.
        // With the first root flipped the two wordings appear side by side, which is the point: a census
        // that could only say "this deploy is enforcing" would say nothing about which of the five.
        check("an enforced root reports SCOPING",
              ChatReadScope.logLine("Conversation", ChatReadScope.MEMBER).contains("SCOPING READ"));
        check("and one outside the flip still reports WOULD SCOPE, so ONE census tells them apart per root",
              ChatReadScope.logLine("ChatPresence", ChatReadScope.MEMBER).contains("WOULD SCOPE READ"));

        // ── The token names the CALLER, not the feature ──────────────────────────────────────────────────
        // It is issued for every client read, chat or not, and selects per-entity rules. A feature-named
        // token becomes a lie the moment anything outside chat is scoped for the same caller kind.
        check("the member token is not named after chat", "member", ChatReadScope.MEMBER);
        check("nor the back-office one", "backoffice", ChatReadScope.BACKOFFICE);

        // ── THE UNRESTRICTED SCOPE, which the 11 Nov rehearsal cost an inbox to find ─────────────────────
        // CallerReadScope.Scope carries an `unrestricted` flag whose javadoc says such a caller should get
        // no predicate at all, and grantedScopeOf never consults it — UNRESTRICTED's arrays are EMPTY, so
        // `event in CALLER_GRANTED_EVENTS` matched nothing and a super admin read NOTHING. A token rather
        // than a conditional rule because compiled sql is cached per (statement, token): a condition that
        // varied by caller within one token would serve one caller's compile to another.
        //
        // These cases guard the OUTCOME, not the branch that produces it, and the difference is worth
        // knowing. Deleting conditionDql's explicit `if (UNRESTRICTED…) return null` fails NOTHING here,
        // because the unknown-token fall-through below it returns null too. Probed, and reported rather
        // than quietly accepted. The explicit branch still earns its place: it keeps "unrestricted means no
        // rule" true however the known-token test is edited later, which is exactly the edit that would
        // otherwise hand a super admin the back-office rule again. What no check can pin is which line
        // returned the null.
        for (String entity : new String[] {"Conversation", "ChatMessage", "ChatMessageReaction",
                                           "ConversationParticipant", "ChatPresence"})
            check("an unrestricted caller gets no rule for " + entity,
                  dql(entity, ChatReadScope.UNRESTRICTED) == null);
        check("nor for Person or Document, which other scopes own",
              dql("Person", ChatReadScope.UNRESTRICTED) == null
              && dql("Document", ChatReadScope.UNRESTRICTED) == null);
        check("the unrestricted token is its own scope, not a flag on another",
              !ChatReadScope.UNRESTRICTED.equals(ChatReadScope.BACKOFFICE)
              && !ChatReadScope.UNRESTRICTED.equals(ChatReadScope.MEMBER));

        // ── the probe's cadence ──────────────────────────────────────────────────────────────────────
        // Pinned because the first version was wrong in a way no unit test would have caught and the first
        // four lines off staging did: "the first, then every 100th" logged `(seen 1)` for every kind and
        // then went silent, so the measured answer to "how often is a back office stale?" was a band from 1
        // to 99 per server run — wide enough to hold both "a cold-start race" and "a hundred silently empty
        // screens a day". The band these cases defend is the LOW end, where the decision actually sits.
        check("a staleness occurrence logs the first time", ChatReadScope.shouldLog(1, true));
        check("and the second — the band from 2 to 99 is where the decision sits",
              ChatReadScope.shouldLog(2, true));
        check("and the 99th, which the old cadence silently dropped", ChatReadScope.shouldLog(99, true));
        check("past the cap it falls back to powers of ten, so a flood stays bounded",
              !ChatReadScope.shouldLog(5_001, true) && ChatReadScope.shouldLog(10_000, true));
        check("the expected kind stays throttled: first yes, second no",
              ChatReadScope.shouldLog(1, false) && !ChatReadScope.shouldLog(2, false));
        check("and logs again at ten and a hundred, not in between",
              ChatReadScope.shouldLog(10, false) && !ChatReadScope.shouldLog(11, false)
              && ChatReadScope.shouldLog(100, false));

        // ── THE BLIND SPOT, pinned where it actually lived: the early return above the report ───────────
        // Asserting the rule text could never have caught this. conditionDql returns null for UNRESTRICTED
        // by design — a super admin gets no predicate — and readScopeCondition used to return on that null
        // BEFORE its report line. So a super admin reading a chat root logged nothing, census 7e showed
        // zero back-office observations for two censuses running, and that zero was read as "the back
        // office never reads chat" while 7h logged 100 of their reads in the same output. Three censuses
        // later production still had not exercised it, because it needs a super admin to open the support
        // inbox on a freshly restarted process. Pinned here instead, where it costs nothing to run.
        List<String> logged = new ArrayList<>();
        SingleServiceProvider.registerServiceProvider(ConsoleProvider.class, new ConsoleProvider() {
            @Override public void log(String message) { logged.add(message); }
            @Override public void error(String message, Throwable error) { }
        });
        ChatReadScope.INSTANCE.readScopeCondition("Conversation", ChatReadScope.UNRESTRICTED);
        check("a super admin reading a chat root is REPORTED, not silently skipped",
              logged.size() == 1 && logged.get(0).contains("UNSCOPED READ of Conversation under unrestricted"));
        logged.clear();
        // Organization rather than Document: Document became a scoped root on 2026-11-29, so it is no
        // longer an example of an entity nobody owns. The distinction this pins is still the same one —
        // a root with a rule is reported even when the caller gets no predicate, a root with no rule
        // anywhere is silent — and using a scoped root as the silent example would assert the opposite.
        ChatReadScope.INSTANCE.readScopeCondition("Organization", ChatReadScope.UNRESTRICTED);
        check("but an entity this provider never scopes stays silent - otherwise every read in the system"
              + " would log", logged.isEmpty());
        // THE WIDENED PROBE (25 Nov), written when Person's back-office arm was Phase 2 and a back office
        // read Person with no predicate. Before the widening only UNRESTRICTED was reported, so the only evidence of
        // that gap would have been an absence in 7e - and an absence is precisely what was misread on
        // 20 Nov, two paragraphs up. Pinned so the report cannot be narrowed back without failing here.
        logged.clear();
        ChatReadScope.INSTANCE.readScopeCondition("Person", ChatReadScope.BACKOFFICE);
        check("a back office reading Person with no rule yet is REPORTED, not silently unscoped",
              logged.size() == 1 && logged.get(0).contains("UNSCOPED READ of Person under backoffice"));

        System.out.println(fail == 0 ? "\nALL " + pass + " PASS" : "\n" + fail + " FAILED");
        if (fail > 0)
            throw new AssertionError(fail + " chat read scope checks failed");
    }

    private static String dql(String entity, Object scope) {
        return ChatReadScope.conditionDql(entity, scope);
    }

    private static void check(String what, String expected, String actual) {
        if (expected.equals(actual)) { pass++; System.out.println("  ok   " + what); }
        else { fail++; System.out.println("  FAIL " + what + "\n         got      " + actual
                                        + "\n         expected " + expected); }
    }

    private static void check(String what, boolean ok) {
        if (ok) { pass++; System.out.println("  ok   " + what); }
        else    { fail++; System.out.println("  FAIL " + what); }
    }
}
