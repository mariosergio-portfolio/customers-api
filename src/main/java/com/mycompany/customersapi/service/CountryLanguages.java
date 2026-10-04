package com.mycompany.customersapi.service;

import java.util.Locale;
import java.util.Map;

/**
 * Main language of a country, by the English country name stored on the customer.
 *
 * A fixed lookup is more predictable than asking the model. Countries with several official
 * languages get one default (Belgium: Dutch, Switzerland: German). Unknown or missing countries
 * fall back to English.
 */
public final class CountryLanguages {

    public static final String DEFAULT_LANGUAGE = "English";

    private static final Map<String, String> BY_COUNTRY = Map.ofEntries(
            Map.entry("austria", "German"),
            Map.entry("belgium", "Dutch"),
            Map.entry("czech republic", "Czech"),
            Map.entry("denmark", "Danish"),
            Map.entry("finland", "Finnish"),
            Map.entry("france", "French"),
            Map.entry("germany", "German"),
            Map.entry("greece", "Greek"),
            Map.entry("hungary", "Hungarian"),
            Map.entry("ireland", "English"),
            Map.entry("italy", "Italian"),
            Map.entry("netherlands", "Dutch"),
            Map.entry("norway", "Norwegian"),
            Map.entry("poland", "Polish"),
            Map.entry("portugal", "Portuguese"),
            Map.entry("romania", "Romanian"),
            Map.entry("spain", "Spanish"),
            Map.entry("sweden", "Swedish"),
            Map.entry("switzerland", "German"),
            Map.entry("united kingdom", "English"),
            Map.entry("united states", "English"),
            Map.entry("canada", "English"),
            Map.entry("australia", "English"),
            Map.entry("new zealand", "English"),
            Map.entry("brazil", "Portuguese"),
            Map.entry("mexico", "Spanish"),
            Map.entry("argentina", "Spanish"),
            Map.entry("japan", "Japanese"));

    private CountryLanguages() {
    }

    public static String languageFor(String country) {
        if (country == null || country.isBlank()) {
            return DEFAULT_LANGUAGE;
        }
        return BY_COUNTRY.getOrDefault(country.trim().toLowerCase(Locale.ROOT), DEFAULT_LANGUAGE);
    }
}
