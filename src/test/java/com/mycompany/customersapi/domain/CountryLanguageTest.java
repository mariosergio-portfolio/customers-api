package com.mycompany.customersapi.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CountryLanguageTest {

    @Test
    void should_return_the_main_language_of_the_country_ignoring_case_and_spaces() {
        assertEquals("French", CountryLanguage.languageOf("France"));
        assertEquals("French", CountryLanguage.languageOf("  FRANCE "));
        assertEquals("Czech", CountryLanguage.languageOf("Czech Republic"));
        assertEquals("Dutch", CountryLanguage.languageOf("Belgium"));
        assertEquals("German", CountryLanguage.languageOf("Switzerland"));
    }

    @Test
    void should_fall_back_to_english_for_unknown_blank_or_null_countries() {
        assertEquals("English", CountryLanguage.languageOf("Atlantis"));
        assertEquals("English", CountryLanguage.languageOf(" "));
        assertEquals("English", CountryLanguage.languageOf(null));
    }

    @Test
    void should_list_one_line_per_country_for_the_prompt() {
        String table = CountryLanguage.promptTable();

        assertTrue(table.contains("France: French"));
        assertTrue(table.contains("Czech Republic: Czech"));
        assertTrue(table.contains("United Kingdom: English"));
    }

    @Test
    void should_cover_every_country_in_the_example_data() {
        for (String country : new String[]{"Netherlands", "Czech Republic", "France", "Portugal", "Belgium", "Norway", "Italy",
                "Switzerland", "Sweden", "Greece", "United Kingdom", "Denmark", "Austria", "Ireland", "Hungary", "Spain",
                "Poland", "Germany", "Finland", "Romania"}) {
            assertNotNull(CountryLanguage.languageOf(country), country);
            assertFalse(CountryLanguage.promptTable().lines().noneMatch(l -> l.startsWith(country + ":")), country + " is listed");
        }
    }
}
