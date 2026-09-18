package one.modality.crm.server.services.authz;

import dev.webfx.platform.async.Future;
import dev.webfx.stack.db.submit.ClientSubmitGuard;
import dev.webfx.stack.db.submit.ProtectedEntityWriteRegistry;

import java.util.ArrayList;
import java.util.List;

/**
 * Every client row rule, asked as one: the guard holds a single policy, and a write must pass all of them.
 *
 * <p>All are asked at once, on the caller's thread, before any answers: a rule may read the caller's session from the
 * thread local, which is gone after the first async hop, so asking them one after another would have the later ones
 * judge a caller they can no longer see. A rule that throws or fails refuses, as the guard itself treats one.
 *
 * @author Claude Code
 */
final class ClientWritePolicies implements ClientSubmitGuard.WritePolicy {

    private final ClientSubmitGuard.WritePolicy[] policies;

    ClientWritePolicies(ClientSubmitGuard.WritePolicy... policies) {
        this.policies = policies.clone();
    }

    @Override
    public Future<String> refusalReason(ProtectedEntityWriteRegistry.WriteRequest write) {
        List<Future<String>> answers = new ArrayList<>(policies.length);
        for (ClientSubmitGuard.WritePolicy policy : policies) {
            Future<String> answer;
            try {
                answer = policy.refusalReason(write);
            } catch (Throwable e) {
                answer = null;
            }
            answers.add(answer == null ? Future.succeededFuture(ClientSubmitGuard.UNCHECKABLE_REFUSED)
                : answer.otherwise(e -> ClientSubmitGuard.UNCHECKABLE_REFUSED));
        }
        return Future.all(new ArrayList<>(answers))
            .map(composite -> {
                for (int i = 0; i < answers.size(); i++) {
                    String refusal = composite.resultAt(i);
                    if (refusal != null)
                        return refusal;
                }
                return null;
            });
    }
}
