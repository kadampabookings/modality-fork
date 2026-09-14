package one.modality.crm.server.authn.gateway.shared;

import dev.webfx.platform.ast.AST;
import dev.webfx.platform.ast.AstArray;
import dev.webfx.platform.ast.AstObject;
import dev.webfx.platform.ast.json.Json;

import java.util.List;

/**
 * Builds the reply a back-office login gets when its password was right and a second factor is still
 * owed:
 *
 * <pre>{@code {"secondFactorRequired":true,"methods":["totp","pk"],"expiresInSeconds":300,"attemptsRemaining":5}}</pre>
 *
 * <p>It signs nobody in. Returning a plain string from a credentials call is how this SPI already
 * works ({@code continueAccountCreation} and the passkey ceremony options do the same), and the
 * endpoint hands the result back unchanged — so the client learns what to ask for next while the
 * session stays untouched and nothing is pushed.
 *
 * <p>It is returned only AFTER the password matched. Before that, "wrong user or password" is the
 * answer to everything: the marker says the account holds a factor, which is not a stranger's to
 * learn.
 *
 * <p>Built through the AST and formatted, never by concatenating strings: a label or method code
 * carrying a quote would otherwise produce a broken or forged document.
 *
 * @author Claude Code
 */
public final class SecondFactorMarker {

    private SecondFactorMarker() {
    }

    public static String json(List<String> methods, long expiresInSeconds, int attemptsRemaining) {
        AstObject marker = AST.createObject();
        marker.set("secondFactorRequired", true);
        AstArray methodsArray = AST.createArray();
        if (methods != null)
            for (String method : methods)
                methodsArray.push(method);
        marker.setArray("methods", methodsArray);
        marker.set("expiresInSeconds", expiresInSeconds);
        marker.set("attemptsRemaining", attemptsRemaining);
        return Json.formatObject(marker);
    }
}
