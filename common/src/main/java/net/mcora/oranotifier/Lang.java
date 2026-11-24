// The plugin's messages, read from lang/<language>.properties in the jar. Those files are generated at build time
// (generateLang in build.gradle) from packages/i18n/locales/*/plugin.yml, already merged over zh-TW, so a language
// only needs its own file here; zh_tw is the fallback for an unknown language or a key the file lacks.
// Called by OraCore, PlayerTracker and each platform's /mcora handler; OraCore.start() picks the configured language.
package net.mcora.oranotifier;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Properties;

public final class Lang {
    private static final Properties FALLBACK = read("zh_tw");
    private static volatile Properties current = FALLBACK;
    // Minecraft language code -> the Accept-Language OraCore sends, so Mcora's refusal reasons come back in it too.
    private static final Map<String, String> HTTP = Map.of("zh_tw", "zh-TW", "zh_hk", "zh-HK", "zh_cn", "zh-CN", "ja_jp", "ja", "ko_kr", "ko", "en_us", "en-US");
    private static volatile String http = "zh-TW";

    private Lang() {}

    /** Switches to `language` (Minecraft's code, e.g. zh_tw, en_us); unknown codes keep zh_tw. */
    public static void use(String language) {
        Properties p = read(language.trim().toLowerCase());
        current = p.isEmpty() ? FALLBACK : p;
        http = HTTP.getOrDefault(language.trim().toLowerCase(), "zh-TW");
    }

    /** The configured language as an Accept-Language value (zh-TW for an unknown code, like the messages). */
    public static String httpLanguage() {
        return http;
    }

    /** The message for `key` with each {name} replaced: t("vote.received", "player", name, "service", service). */
    public static String t(String key, Object... params) {
        String s = current.getProperty(key, FALLBACK.getProperty(key, key));
        for (int i = 0; i + 1 < params.length; i += 2) s = s.replace("{" + params[i] + "}", String.valueOf(params[i + 1]));
        return s;
    }

    private static Properties read(String language) {
        Properties p = new Properties();
        if (!language.matches("[a-z_]{2,16}")) return p;
        try (InputStream in = Lang.class.getResourceAsStream("/lang/" + language + ".properties")) {
            if (in != null) p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            // a broken file reads as missing, so the zh_tw text is used
        }
        return p;
    }
}
