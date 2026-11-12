package one.modality.crm.server.services.authz;

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

        // ── Everything else is untouched ─────────────────────────────────────────────────────────────────
        check("an entity this does not scope has no rule", dql("Document", ChatReadScope.MEMBER) == null);
        check("nor does Person, which a read POLICY handles instead",
              dql("Person", ChatReadScope.BACKOFFICE) == null);
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
              line.matches(".*(WOULD SCOPE|SCOPING) READ of [A-Za-z]+ under [a-z.]+"));
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
        // Back to none: the 11 Nov staging rehearsal found the back-office arm returns nothing for a caller
        // whose scope is UNRESTRICTED (empty arrays) or stale (null), so no root is enforced again until
        // both are fixed. See the plan's rehearsal section.
        for (String entity : new String[] {"Conversation", "ChatMessage", "ChatMessageReaction",
                                           "ConversationParticipant", "ChatPresence"})
            check("no root is enforced while the rehearsal's findings stand (" + entity + ")",
                  !ChatReadScope.isEnforced(entity));
        check("an entity this does not scope is not enforced either",
              !ChatReadScope.isEnforced("Document"));
        // The line says which mode each root is in, so ONE census tells observing from enforcing per root.
        // With the first root flipped the two wordings appear side by side, which is the point: a census
        // that could only say "this deploy is enforcing" would say nothing about which of the five.
        check("every root reports WOULD SCOPE while none is enforced",
              ChatReadScope.logLine("Conversation", ChatReadScope.MEMBER).contains("WOULD SCOPE READ"));

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
        check("and none for an entity this does not scope either",
              dql("Document", ChatReadScope.UNRESTRICTED) == null);
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
