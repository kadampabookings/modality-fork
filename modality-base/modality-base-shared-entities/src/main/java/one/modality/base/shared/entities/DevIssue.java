package one.modality.base.shared.entities;

import dev.webfx.stack.orm.entity.Entity;
import one.modality.base.shared.entities.markers.EntityHasLabel;

import java.time.Instant;

/**
 * One thing the dev team should address: a bug, an improvement request, a
 * UX difficulty — hence {@link #kind} rather than an entity called "Bug",
 * since most of what support surfaces is not a defect.
 *
 * <p>NOT to be confused with {@link Issue}, which is the VIEWER-facing
 * catalog behind the streaming page's self-help tips ("no sound" →
 * "unmute the player"). That one is a fixed catalog the wizard reads;
 * this one is a working list the team maintains.
 *
 * <p>GitHub remains the tracker — {@link #githubIssue} holds the issue
 * NUMBER (no URL: the repository is a deployment detail, and a number
 * survives a rename) and is NULL for items tracked only here. The
 * {@code status} is maintained by hand in this database rather than
 * fetched from GitHub, which would need a token, a cache and a failure
 * mode to answer a question that changes a few times a week.
 *
 * <p>Support conversations link to it via {@code Conversation.devIssue};
 * MANY conversations point at ONE row, which is why the status lives here
 * and not on the conversation. Personal data never leaves the database:
 * a GitHub issue carries context and a deep link back, never the viewer.
 *
 * @author Bruno Salmon
 */
public interface DevIssue extends Entity, EntityHasLabel {

    String kind = "kind";
    String title = "title";
    String status = "status";
    String githubIssue = "githubIssue";
    String createdAt = "createdAt";
    String resolvedAt = "resolvedAt";

    // kind values
    String KIND_BUG = "bug";
    String KIND_IMPROVEMENT = "improvement";
    String KIND_UX = "ux";
    String KIND_QUESTION = "question";

    // status values
    String STATUS_REPORTED = "reported";
    String STATUS_CONFIRMED = "confirmed";
    String STATUS_FIXED = "fixed";
    String STATUS_WONTFIX = "wontfix";

    default void setKind(String value) {
        setFieldValue(kind, value);
    }

    default String getKind() {
        return getStringFieldValue(kind);
    }

    default void setTitle(String value) {
        setFieldValue(title, value);
    }

    default String getTitle() {
        return getStringFieldValue(title);
    }

    default void setStatus(String value) {
        setFieldValue(status, value);
    }

    default String getStatus() {
        return getStringFieldValue(status);
    }

    default void setGithubIssue(Integer value) {
        setFieldValue(githubIssue, value);
    }

    default Integer getGithubIssue() {
        return getIntegerFieldValue(githubIssue);
    }

    default void setCreatedAt(Instant value) {
        setFieldValue(createdAt, value);
    }

    default Instant getCreatedAt() {
        return getInstantFieldValue(createdAt);
    }

    default void setResolvedAt(Instant value) {
        setFieldValue(resolvedAt, value);
    }

    default Instant getResolvedAt() {
        return getInstantFieldValue(resolvedAt);
    }
}
