package com.mycompany.customersapi.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CountryLanguagesTest {

    /** Every country in the example data. */
    @ParameterizedTest
    @CsvSource({
            "Czech Republic,Czech", "Netherlands,Dutch", "Norway,Norwegian", "Switzerland,German", "Hungary,Hungarian",
            "Sweden,Swedish", "Poland,Polish", "Germany,German", "Austria,German", "Spain,Spanish", "Greece,Greek",
            "Italy,Italian", "France,French", "Denmark,Danish", "Romania,Romanian", "Ireland,English",
            "Finland,Finnish", "Belgium,Dutch", "Portugal,Portuguese", "United Kingdom,English"})
    void coversTheExampleCountries(String country, String language) {
        assertEquals(language, CountryLanguages.languageFor(country));
    }

    @Test
    void ignoresCaseAndSurroundingSpaces() {
        assertEquals("French", CountryLanguages.languageFor("  FRANCE "));
    }

    @Test
    void unknownOrMissingCountryFallsBackToEnglish() {
        assertEquals("English", CountryLanguages.languageFor("Atlantis"));
        assertEquals("English", CountryLanguages.languageFor(null));
        assertEquals("English", CountryLanguages.languageFor(" "));
    }
}
