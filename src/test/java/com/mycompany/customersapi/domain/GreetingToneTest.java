package com.mycompany.customersapi.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GreetingToneTest {

    @ParameterizedTest
    @CsvSource({"0,PLAYFUL", "17,PLAYFUL", "18,CASUAL", "34,CASUAL", "35,WARM", "59,WARM", "60,FORMAL", "99,FORMAL"})
    void bandsFollowTheAge(int age, GreetingTone expected) {
        assertEquals(expected, GreetingTone.fromAge(age));
    }

    @Test
    void unknownOrImplausibleAgeIsNeutral() {
        assertEquals(GreetingTone.NEUTRAL, GreetingTone.fromAge(null));
        assertEquals(GreetingTone.NEUTRAL, GreetingTone.fromAge(-1));
        assertEquals(GreetingTone.NEUTRAL, GreetingTone.fromAge(200));
    }
}
