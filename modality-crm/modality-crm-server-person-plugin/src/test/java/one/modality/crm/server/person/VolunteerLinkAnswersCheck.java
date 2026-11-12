package one.modality.crm.server.person;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * What the volunteer link endpoints accept and refuse before touching the database, and that they are registered.
 *
 * <p>The decisions are pure ({@link VolunteerLinkAnswers}); every refusal of a malformed answer happens before any
 * query, so a refused call that reached the database would show up here as an exception from a service with no
 * provider rather than as the refusal. The reads and writes themselves are exercised against a real database on
 * staging.
 *
 * <p>No test framework: runs from main() and exits non-zero on failure.
 *
 * @author Claude Code
 */
public class VolunteerLinkAnswersCheck {

    static int pass = 0, fail = 0;

    static void check(String what, boolean ok) {
        if (ok) { pass++; System.out.println("  ok   " + what); }
        else { fail++; System.out.println("  FAIL " + what); }
    }

    static String failureOf(dev.webfx.platform.async.Future<?> future) {
        return future.failed() ? future.cause().getMessage() : null;
    }

    public static void main(String[] args) throws Exception {
        LocalDateTime now = LocalDateTime.of(2026, 11, 12, 10, 0);
        LocalDate today = now.toLocalDate();

        System.out.println("a link can be answered only when it was sent, has not expired and was not used:");
        check("sent, no expiry, unused", VolunteerLinkAnswers.linkOpen("t", null, null, now));
        check("sent, expiring later (timestamp)", VolunteerLinkAnswers.linkOpen("t", now.plusDays(1), null, now));
        check("sent, expiring today (date)", VolunteerLinkAnswers.linkOpen("t", today, null, now));
        check("never sent", !VolunteerLinkAnswers.linkOpen(null, null, null, now));
        check("already used", !VolunteerLinkAnswers.linkOpen("t", null, now.minusHours(1), now));
        check("expired (timestamp)", !VolunteerLinkAnswers.linkOpen("t", now.minusMinutes(1), null, now));
        check("expired (date)", !VolunteerLinkAnswers.linkOpen("t", today.minusDays(1), null, now));
        check("an expiry it cannot read counts as passed", !VolunteerLinkAnswers.linkOpen("t", "soon", null, now));

        System.out.println("the answers a link offers:");
        check("confirm → validated", "validated".equals(VolunteerLinkAnswers.arrivalStatusFor("confirm")));
        check("cancel → cancelled", "cancelled".equals(VolunteerLinkAnswers.arrivalStatusFor("cancel")));
        check("anything else → no answer", VolunteerLinkAnswers.arrivalStatusFor("approved") == null);
        check("a counter-proposal from today", VolunteerLinkAnswers.counterDates("2026-11-12", "2026-11-30", today) != null);
        check("a one-day stay", VolunteerLinkAnswers.counterDates("2026-12-01", "2026-12-01", today) != null);
        check("the server's yesterday accepted (a volunteer ahead of UTC)", VolunteerLinkAnswers.counterDates("2026-11-11", "2026-11-30", today) != null);
        check("dates further in the past refused", VolunteerLinkAnswers.counterDates("2026-11-10", "2026-11-30", today) == null);
        check("end before start refused", VolunteerLinkAnswers.counterDates("2026-12-10", "2026-12-01", today) == null);
        check("a stay beyond " + VolunteerLinkAnswers.MAX_STAY_DAYS + " days refused",
            VolunteerLinkAnswers.counterDates("2026-12-01", "2028-12-01", today) == null);
        check("not ISO days refused", VolunteerLinkAnswers.counterDates("01/12/2026", "2026-12-20", today) == null);
        check("a date with a time refused", VolunteerLinkAnswers.counterDates("2026-12-01T00:00", "2026-12-20", today) == null);
        check("a number refused", VolunteerLinkAnswers.counterDates(20261201, "2026-12-20", today) == null);

        System.out.println("free text and tokens:");
        check("text trimmed", "why".equals(VolunteerLinkAnswers.text("  why ")));
        check("blank text is none", VolunteerLinkAnswers.text("   ") == null);
        check("text capped", VolunteerLinkAnswers.text("x".repeat(5000)).length() == VolunteerLinkAnswers.MAX_TEXT);
        check("a token must be text", VolunteerLinkAnswers.token(42) == null);
        check("a blank token is none", VolunteerLinkAnswers.token(" ") == null);
        check("an over-long token is none", VolunteerLinkAnswers.token("x".repeat(200)) == null);

        System.out.println("malformed answers are refused before any read:");
        check("arrival without a token", VolunteerLinkAnswers.BAD_ANSWER.equals(failureOf(
            VolunteerLinkAnswers.answerArrival(null, "confirm", null, null, "203.0.113.1"))));
        check("arrival with an unknown choice", VolunteerLinkAnswers.BAD_ANSWER.equals(failureOf(
            VolunteerLinkAnswers.answerArrival("t", "approve", null, null, "203.0.113.1"))));
        check("dates with an unknown choice", VolunteerLinkAnswers.BAD_ANSWER.equals(failureOf(
            VolunteerLinkAnswers.answerDates("t", "reject", null, null, null, null, "203.0.113.1"))));
        check("a counter-proposal with bad dates", VolunteerLinkAnswers.BAD_ANSWER.equals(failureOf(
            VolunteerLinkAnswers.answerDates("t", "counter", "2020-01-01", "2020-01-02", null, null, "203.0.113.1"))));

        System.out.println("registered in the three places a bus endpoint needs:");
        String base = Files.isDirectory(Path.of("src")) ? "" : "modality-fork/modality-crm/modality-crm-server-person-plugin/";
        String moduleInfo = read(base + "src/main/java/module-info.java"), services = read(base
            + "src/main/resources/META-INF/services/dev.webfx.stack.com.bus.call.spi.BusCallEndpoint"), webfx = read(base + "webfx.xml");
        for (String endpoint : new String[] { "AnswerVolunteerArrivalEndpoint", "AnswerVolunteerDatesEndpoint" })
            check(endpoint, moduleInfo != null && moduleInfo.contains(endpoint) && services != null && services.contains(endpoint)
                            && webfx != null && webfx.contains(endpoint));

        System.out.println();
        System.out.println(pass + " passed, " + fail + " failed");
        if (fail > 0)
            System.exit(1);
    }

    static String read(String path) {
        try {
            return Files.readString(Path.of(path));
        } catch (Exception e) {
            return null;
        }
    }
}
