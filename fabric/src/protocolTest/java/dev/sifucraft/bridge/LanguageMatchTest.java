package dev.sifucraft.bridge;

import dev.sifucraft.ui.LanguageMatch;
import java.util.Set;

final class LanguageMatchTest {
    static void run() {
        Set<String> installed = Set.of("en_us", "en_gb", "zh_cn", "zh_tw", "fr_fr", "pt_br");
        for (String tag : new String[] {"zh-Hans", "zh_CN", "zh-Hans-TW", "zh-SG"}) check(tag, "zh_cn", installed);
        for (String tag : new String[] {"zh-Hant", "zh-TW", "zh-HK", "zh-Hant-CN"}) check(tag, "zh_tw", installed);
        check("en-GB", "en_gb", installed);
        check("en", "en_us", installed);
        check("fr", "fr_fr", installed);
        check("pt", "pt_br", installed);
        for (String tag : new String[] {"", "unknown", "ja-JP"}) check(tag, "", installed);
        check("zh-Hans", "", Set.of("en_us"));
        System.out.println("Host language matching tests PASS");
    }

    private static void check(String tag, String expected, Set<String> installed) {
        String actual = LanguageMatch.resolve(tag, installed);
        if (!actual.equals(expected)) throw new AssertionError(tag + " -> " + actual + "; expected " + expected);
    }
}
