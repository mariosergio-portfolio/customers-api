package com.mycompany.customersapi.domain;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;

/**
 * Languages accepted by the pronounce endpoint. Each constant carries the BCP-47
 * code sent to AWS Polly (neural engine); the API accepts and documents that code
 * (e.g. {@code pt-BR}), not the constant name.
 */
public enum PronounceLanguage {

    EN_US("en-US"),
    EN_GB("en-GB"),
    PT_BR("pt-BR"),
    PT_PT("pt-PT"),
    ES_ES("es-ES"),
    ES_MX("es-MX"),
    FR_FR("fr-FR"),
    DE_DE("de-DE"),
    IT_IT("it-IT"),
    NL_NL("nl-NL"),
    SV_SE("sv-SE"),
    DA_DK("da-DK"),
    NB_NO("nb-NO"),
    FI_FI("fi-FI"),
    PL_PL("pl-PL"),
    JA_JP("ja-JP");

    private final String code;

    PronounceLanguage(String code) {
        this.code = code;
    }

    @JsonValue
    public String code() {
        return code;
    }

    /** Resolves a BCP-47 code (case-insensitive); throws {@link IllegalArgumentException} if unsupported. */
    public static PronounceLanguage fromCode(String code) {
        String trimmed = code == null ? "" : code.trim();
        return Arrays.stream(values())
                .filter(l -> l.code.equalsIgnoreCase(trimmed))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unsupported language '" + code + "'. Supported: "
                                + String.join(", ", Arrays.stream(values()).map(PronounceLanguage::code).toList())));
    }
}
