package one.modality.crm.server.authn.gateway.shared;

import dev.webfx.platform.resource.Resource;
import dev.webfx.platform.util.Strings;

import java.io.IOException;
import java.io.StringReader;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * A localized email template composed of:
 *
 * <ul>
 *   <li>one HTML body per language — {@code baseName.html} (English / default) plus
 *       {@code baseName_<lang>.html} for each translated language. Full markup per
 *       language keeps the source natural to read and translate (no token puzzles).</li>
 *   <li>one {@code .properties} dictionary per language for elements that live
 *       <b>outside</b> the HTML body — the email subject, and anything else the
 *       mail layer needs that isn't part of the rendered page.</li>
 * </ul>
 *
 * <p>Missing language files (HTML or properties) silently fall back to English so
 * partial translations are safe to ship.</p>
 *
 * <p>Dictionary files are loaded as UTF-8 via {@link Properties#load(java.io.Reader)} —
 * the legacy {@code InputStream} overload would assume ISO-8859-1 and garble
 * non-ASCII characters.</p>
 */
public final class LocalizedMailTemplate {

    /** Languages we attempt to load variants for; English (no suffix) is always loaded as the fallback. */
    private static final List<String> SUPPORTED_LANGS = List.of("fr", "de", "es", "pt", "vi", "zh");

    private final Map<String, String> bodiesByLang;
    private final Map<String, Map<String, String>> messagesByLang;

    private LocalizedMailTemplate(
        Map<String, String> bodiesByLang,
        Map<String, Map<String, String>> messagesByLang
    ) {
        this.bodiesByLang = bodiesByLang;
        this.messagesByLang = messagesByLang;
    }

    /**
     * Load the default HTML body + all translated variants and dictionaries at class-init time.
     *
     * @param htmlBaseName       e.g. {@code "AccountCreationMailBody"} — the loader will read
     *                           {@code baseName.html} (English) and {@code baseName_<lang>.html}
     *                           for each supported language; missing files are skipped.
     * @param messagesBaseName   e.g. {@code "AccountCreationMailMessages"} — same pattern for
     *                           the {@code .properties} dictionaries (subject + anything else).
     * @param loaderClass        any class in the same resources root (used for classpath lookup)
     */
    public static LocalizedMailTemplate load(String htmlBaseName, String messagesBaseName, Class<?> loaderClass) {
        Map<String, String> bodies = new HashMap<>();
        bodies.put("en", Resource.getText(Resource.toUrl(htmlBaseName + ".html", loaderClass)));

        Map<String, Map<String, String>> messages = new HashMap<>();
        messages.put("en", parseProperties(Resource.getText(Resource.toUrl(messagesBaseName + ".properties", loaderClass))));

        for (String lang : SUPPORTED_LANGS) {
            String body = tryLoadResourceText(htmlBaseName + "_" + lang + ".html", loaderClass);
            if (body != null && !body.isEmpty()) {
                bodies.put(lang, body);
            }
            String dict = tryLoadResourceText(messagesBaseName + "_" + lang + ".properties", loaderClass);
            if (dict != null && !dict.isEmpty()) {
                messages.put(lang, parseProperties(dict));
            }
        }
        return new LocalizedMailTemplate(Map.copyOf(bodies), Map.copyOf(messages));
    }

    /** Returns the translated HTML body for the given language, or the English body as fallback. */
    public String renderBody(String lang) {
        return bodiesByLang.getOrDefault(normalize(lang), bodiesByLang.get("en"));
    }

    /** Returns the {@code subject} line for the given language, falling back to English. */
    public String renderSubject(String lang) {
        return messagesFor(lang).get("subject");
    }

    /** Returns an arbitrary localized string from the properties dictionary (e.g. {@code "subject"}). */
    public String getMessage(String lang, String key) {
        return messagesFor(lang).get(key);
    }

    private Map<String, String> messagesFor(String lang) {
        return messagesByLang.getOrDefault(normalize(lang), messagesByLang.get("en"));
    }

    /** Strip any region suffix (e.g. "fr-FR" → "fr") and lowercase for map lookup. */
    private static String normalize(String lang) {
        if (lang == null || lang.isBlank()) return "en";
        String normalized = lang.toLowerCase();
        int sep = Math.max(normalized.indexOf('-'), normalized.indexOf('_'));
        return sep > 0 ? normalized.substring(0, sep) : normalized;
    }

    private static String tryLoadResourceText(String resourceName, Class<?> loaderClass) {
        try {
            String url = Resource.toUrl(resourceName, loaderClass);
            if (url == null) return null;
            return Resource.getText(url);
        } catch (Exception e) {
            return null;
        }
    }

    private static Map<String, String> parseProperties(String text) {
        Properties props = new Properties();
        try {
            props.load(new StringReader(text));
        } catch (IOException e) {
            throw new RuntimeException("Failed to parse email message dictionary", e);
        }
        Map<String, String> map = new HashMap<>(props.size());
        props.forEach((k, v) -> map.put(Strings.toString(k), Strings.toString(v)));
        return Map.copyOf(map);
    }
}
