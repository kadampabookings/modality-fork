package one.modality.crm.server.authn.gateway.webauthn;

/**
 * Check for the passkey enrolment rule: which credential statuses make an account "enrolled in a
 * passkey" in each state of the {@code WEBAUTHN_BACKOFFICE_APPROVAL} switch.
 *
 * <p>No test framework: this repository declares no JUnit, so this runs from {@code main()} and exits
 * non-zero on failure, following {@code TotpCheck} and the {@code webfx-stack-session-token}
 * {@code *Check} classes.
 *
 * <p><b>Why this table and not others.</b> The answer this pins is not cosmetic: it decides whether a
 * back-office password login is HELD for a second step, and it must agree exactly with what the
 * assertion path would then accept. Said yes where the login says no and the owner is asked for a
 * factor the server refuses — locked out. Said no where the login says yes and the account is quietly
 * downgraded to password-only — which is the bug this whole change exists to fix, in reverse. The two
 * read one method ({@link WebAuthnCredentialStore#opensBackofficeLogin}), so what is checked here is
 * that method's table plus the "any one row" quantifier over an account's credentials.
 *
 * <p>The switch states are checked as a pair on purpose: with approval ON only APPROVED counts, and
 * with it OFF a PENDING row counts as much as an APPROVED one (phase 1 — the account's own
 * {@code backoffice} flag is the gate). REJECTED counts in neither, which is the one line that must
 * never move: a rejection is a decision on record, not a queue state.
 *
 * <p>Also pinned, because each is a constant a future edit could move without noticing: the wire
 * code {@code pk}, {@code isStrongAlone}, and that two ids of different numeric types compare equal
 * once normalised — the comparison the step-up's account fence rests on.
 *
 * <p><b>What this canNOT reach, so nobody reads a green run as covering it.</b> The two fail-closed
 * rules live inside {@code isEnrolled}'s async lambdas and need a database to exercise: a failed
 * query answering ENROLLED, and the loud line for a usable credential on an unconfigured gateway.
 * (The latter changes only what is LOGGED — the ANSWER on an unconfigured gateway is whatever
 * {@code holdsUsableCredential} says, which is checked here — but "it says so loudly" is not.)
 */
public class PasskeySecondFactorVerifierCheck {

    static int pass = 0, fail = 0;

    static void check(String what, boolean ok) {
        if (ok) { pass++; System.out.println("  ok   " + what); }
        else { fail++; System.out.println("  FAIL " + what); }
    }

    static final boolean APPROVAL_ON = true, APPROVAL_OFF = false;

    /** A credential row as the account listing returns it — only its status matters to the rule. */
    static WebAuthnCredentialStore.CredentialSummary credential(String status) {
        return new WebAuthnCredentialStore.CredentialSummary(1L, "credId", "userHandle", null, null, null, null, null, status);
    }

    static boolean enrolled(boolean approvalRequired, String... statuses) {
        return PasskeySecondFactorVerifier.holdsUsableCredential(
            java.util.Arrays.stream(statuses).map(PasskeySecondFactorVerifierCheck::credential).toList(), approvalRequired);
    }

    public static void main(String[] args) {
        System.out.println("WEBAUTHN_BACKOFFICE_APPROVAL = on — only a super administrator's APPROVED opens the back office:");
        check("APPROVED opens it", WebAuthnCredentialStore.opensBackofficeLogin(WebAuthnCredentialStore.STATUS_APPROVED, APPROVAL_ON));
        check("PENDING does NOT — it is waiting for a decision",
            !WebAuthnCredentialStore.opensBackofficeLogin(WebAuthnCredentialStore.STATUS_PENDING, APPROVAL_ON));
        check("REJECTED does NOT", !WebAuthnCredentialStore.opensBackofficeLogin(WebAuthnCredentialStore.STATUS_REJECTED, APPROVAL_ON));

        System.out.println("WEBAUTHN_BACKOFFICE_APPROVAL = off (phase 1) — anything but REJECTED opens it:");
        check("APPROVED opens it", WebAuthnCredentialStore.opensBackofficeLogin(WebAuthnCredentialStore.STATUS_APPROVED, APPROVAL_OFF));
        check("PENDING opens it too — the gate is dormant, the account's backoffice flag decides",
            WebAuthnCredentialStore.opensBackofficeLogin(WebAuthnCredentialStore.STATUS_PENDING, APPROVAL_OFF));
        check("REJECTED still does NOT — a rejection signs in nowhere, ever",
            !WebAuthnCredentialStore.opensBackofficeLogin(WebAuthnCredentialStore.STATUS_REJECTED, APPROVAL_OFF));

        System.out.println("a status neither value names follows the same two lines (the V0089 CHECK makes it unreachable from the database):");
        check("unknown status with the gate on is refused, like PENDING",
            !WebAuthnCredentialStore.opensBackofficeLogin("SOMETHING_ELSE", APPROVAL_ON));
        check("unknown status with the gate off is accepted, like PENDING",
            WebAuthnCredentialStore.opensBackofficeLogin("SOMETHING_ELSE", APPROVAL_OFF));
        check("null with the gate on is refused", !WebAuthnCredentialStore.opensBackofficeLogin(null, APPROVAL_ON));
        check("null with the gate off is accepted", WebAuthnCredentialStore.opensBackofficeLogin(null, APPROVAL_OFF));

        System.out.println("\"enrolled\" is ANY ONE usable row of the account, not all of them:");
        check("no rows at all is not enrolled (gate on)", !enrolled(APPROVAL_ON));
        check("no rows at all is not enrolled (gate off)", !enrolled(APPROVAL_OFF));
        check("a null list is not enrolled", !PasskeySecondFactorVerifier.holdsUsableCredential(null, APPROVAL_OFF));
        check("two rejected and one approved IS enrolled (gate on)",
            enrolled(APPROVAL_ON, WebAuthnCredentialStore.STATUS_REJECTED, WebAuthnCredentialStore.STATUS_REJECTED,
                WebAuthnCredentialStore.STATUS_APPROVED));
        check("only rejected rows is NOT enrolled (gate off) — this is the account that must stay password-only",
            !enrolled(APPROVAL_OFF, WebAuthnCredentialStore.STATUS_REJECTED, WebAuthnCredentialStore.STATUS_REJECTED));
        check("only pending rows is NOT enrolled with the gate on",
            !enrolled(APPROVAL_ON, WebAuthnCredentialStore.STATUS_PENDING, WebAuthnCredentialStore.STATUS_PENDING));
        check("only pending rows IS enrolled with the gate off",
            enrolled(APPROVAL_OFF, WebAuthnCredentialStore.STATUS_PENDING, WebAuthnCredentialStore.STATUS_PENDING));
        check("a pending row alongside a rejected one is enough with the gate off",
            enrolled(APPROVAL_OFF, WebAuthnCredentialStore.STATUS_REJECTED, WebAuthnCredentialStore.STATUS_PENDING));

        System.out.println("what the verifier reports, and what it is:");
        PasskeySecondFactorVerifier verifier = new PasskeySecondFactorVerifier();
        check("its method code is the wire code \"pk\"", "pk".equals(verifier.methodCode()));
        check("it is strong ALONE — a user-verified passkey is both factors in one gesture", verifier.isStrongAlone());

        System.out.println("ids from two sources compare equal once normalised (raw-SQL Long vs an entity's Integer):");
        check("an Integer and a Long of the same value normalise equal",
            WebAuthnCredentialStore.normaliseId(42).equals(WebAuthnCredentialStore.normaliseId(42L)));
        check("a Short normalises equal too (what a session token can deserialize to)",
            WebAuthnCredentialStore.normaliseId((short) 7).equals(WebAuthnCredentialStore.normaliseId(7L)));
        check("null normalises to null", WebAuthnCredentialStore.normaliseId(null) == null);

        System.out.println(pass + " passed, " + fail + " failed");
        if (fail > 0)
            System.exit(1);
    }
}
