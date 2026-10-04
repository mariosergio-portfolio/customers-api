package com.mycompany.customersapi.domain;

import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Fixed country to main-language lookup used to write emails. A table is more predictable than asking
 * the model to guess; for countries with several languages it holds the most widely spoken one.
 * Unknown or missing countries fall back to English.
 */
public final class CountryLanguage {

    public static final String DEFAULT_LANGUAGE = "English";

    private static final Map<String, String> LANGUAGE_BY_COUNTRY = Map.ofEntries(
            Map.entry("austria", "German"),
            Map.entry("australia", "English"),
            Map.entry("belgium", "Dutch"),
            Map.entry("brazil", "Portuguese"),
            Map.entry("bulgaria", "Bulgarian"),
            Map.entry("canada", "English"),
            Map.entry("croatia", "Croatian"),
            Map.entry("czech republic", "Czech"),
            Map.entry("czechia", "Czech"),
            Map.entry("denmark", "Danish"),
            Map.entry("estonia", "Estonian"),
            Map.entry("finland", "Finnish"),
            Map.entry("france", "French"),
            Map.entry("germany", "German"),
            Map.entry("greece", "Greek"),
            Map.entry("hungary", "Hungarian"),
            Map.entry("iceland", "Icelandic"),
            Map.entry("ireland", "English"),
            Map.entry("italy", "Italian"),
            Map.entry("japan", "Japanese"),
            Map.entry("latvia", "Latvian"),
            Map.entry("lithuania", "Lithuanian"),
            Map.entry("luxembourg", "French"),
            Map.entry("mexico", "Spanish"),
            Map.entry("netherlands", "Dutch"),
            Map.entry("norway", "Norwegian"),
            Map.entry("poland", "Polish"),
            Map.entry("portugal", "Portuguese"),
            Map.entry("romania", "Romanian"),
            Map.entry("slovakia", "Slovak"),
            Map.entry("slovenia", "Slovenian"),
            Map.entry("spain", "Spanish"),
            Map.entry("sweden", "Swedish"),
            Map.entry("switzerland", "German"),
            Map.entry("turkey", "Turkish"),
            Map.entry("ukraine", "Ukrainian"),
            Map.entry("united kingdom", "English"),
            Map.entry("united states", "English"));

    private CountryLanguage() {
    }

    /** Main language of the country, or {@value #DEFAULT_LANGUAGE} when it is unknown. */
    public static String languageOf(String country) {
        if (country == null || country.isBlank()) {
            return DEFAULT_LANGUAGE;
        }
        return LANGUAGE_BY_COUNTRY.getOrDefault(country.trim().toLowerCase(Locale.ROOT), DEFAULT_LANGUAGE);
    }

    /** One "Country: Language" line per known country, for the model's prompt. */
    public static String promptTable() {
        return LANGUAGE_BY_COUNTRY.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> capitalize(e.getKey()) + ": " + e.getValue())
                .collect(Collectors.joining("\n"));
    }

    private static String capitalize(String country) {
        StringBuilder sb = new StringBuilder();
        for (String word : country.split(" ")) {
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return sb.toString();
    }
}
