package dev.sifucraft.ui;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Maps the host UI culture to an installed Minecraft translation. */
public final class LanguageMatch {
    private static final Map<String, String> DEFAULTS = Map.ofEntries(
            Map.entry("en", "en_us"), Map.entry("fr", "fr_fr"), Map.entry("de", "de_de"),
            Map.entry("es", "es_es"), Map.entry("pt", "pt_br"), Map.entry("it", "it_it"),
            Map.entry("ja", "ja_jp"), Map.entry("ko", "ko_kr"), Map.entry("ru", "ru_ru"),
            Map.entry("pl", "pl_pl"), Map.entry("tr", "tr_tr"), Map.entry("ar", "ar_sa"));

    private LanguageMatch() { }

    /** Empty means unavailable: keep the user's current language. */
    public static String resolve(String culture, Set<String> available) {
        if (culture == null || culture.isBlank()) return "";
        String tag = culture.toLowerCase(Locale.ROOT).replace('-', '_');
        if (available.contains(tag)) return tag;
        String[] parts = tag.split("_");
        String candidate = DEFAULTS.getOrDefault(parts[0], "");
        if (parts[0].equals("zh")) {
            // A script takes precedence over a region, e.g. zh-Hans-TW.
            boolean traditional = false;
            boolean hasScript = false;
            for (String part : parts) {
                if (part.equals("hans") || part.equals("hant")) {
                    traditional = part.equals("hant");
                    hasScript = true;
                    break;
                }
            }
            if (!hasScript) for (String part : parts) {
                if (part.equals("tw") || part.equals("hk") || part.equals("mo")) traditional = true;
            }
            candidate = traditional ? "zh_tw" : "zh_cn";
        }
        return available.contains(candidate) ? candidate : "";
    }
}
