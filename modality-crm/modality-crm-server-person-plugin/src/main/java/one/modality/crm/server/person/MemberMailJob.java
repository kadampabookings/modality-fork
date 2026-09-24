package one.modality.crm.server.person;

import dev.webfx.platform.boot.spi.ApplicationJob;
import dev.webfx.platform.conf.ConfigLoader;

/**
 * Says at boot whether the member emails can go out, and nothing else.
 *
 * <p>A job for one log line is worth it here because the alternative is no signal at all until an
 * invitation is sent. {@link MemberMail} skips rather than fails when its origin is unresolved — the
 * right call, since the invitation itself has already happened — but a skip that only announces itself
 * at send time means a misconfigured deploy reads as healthy for as long as nobody invites anybody.
 *
 * <p>Hung off {@code onConfigLoaded} rather than done in {@link #onStart()}: the configuration is not
 * necessarily loaded when jobs start, and reading it early finds the bundled sources, where the value
 * is still the unsubstituted template. Announcing "disabled" for a correctly configured deploy would
 * be worse than saying nothing.
 *
 * @author Claude Code
 */
public final class MemberMailJob implements ApplicationJob {

    @Override
    public void onStart() {
        ConfigLoader.onConfigLoaded(MemberMail.CONFIG_PATH, config -> MemberMail.announceConfiguration());
    }
}
