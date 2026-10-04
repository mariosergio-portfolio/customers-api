package com.mycompany.customersapi.domain;

/**
 * Formality of a greeting, chosen from the customer's age. Only this band is sent to the model,
 * never the exact age.
 */
public enum GreetingTone {

    PLAYFUL("playful, cheerful and informal"),
    CASUAL("casual and friendly"),
    WARM("warm and polite"),
    FORMAL("formal and respectful"),
    NEUTRAL("polite and neutral");

    private final String description;

    GreetingTone(String description) {
        this.description = description;
    }

    public String description() {
        return description;
    }

    /** Under 18 playful, 18-34 casual, 35-59 warm, 60 and over formal; unknown or implausible age is neutral. */
    public static GreetingTone fromAge(Integer age) {
        if (age == null || age < 0 || age > 120) {
            return NEUTRAL;
        }
        if (age < 18) {
            return PLAYFUL;
        }
        if (age < 35) {
            return CASUAL;
        }
        if (age < 60) {
            return WARM;
        }
        return FORMAL;
    }
}
