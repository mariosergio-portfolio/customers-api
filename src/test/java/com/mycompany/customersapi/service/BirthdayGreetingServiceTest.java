package com.mycompany.customersapi.service;

import com.mycompany.customersapi.domain.Customer;
import com.mycompany.customersapi.dto.BirthdayGreetingResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class BirthdayGreetingServiceTest {

    private static final UUID PK = UUID.randomUUID();

    private CustomerService customers;
    private BedrockService bedrock;
    private BirthdayGreetingService service;

    @BeforeEach
    void setUp() {
        customers = mock(CustomerService.class);
        bedrock = mock(BedrockService.class);
        service = new BirthdayGreetingService(customers, bedrock);
    }

    private void customer(String name, Integer age, String country) {
        when(customers.getCustomer(PK)).thenReturn(Customer.builder().customerPk(PK).name(name)
                .email("secret@example.com").phone("+351 900 000 000").age(age).country(country).build());
    }

    @Test
    void writesInTheCountryLanguageWithTheAgeToneAndNoPersonalData() {
        customer("Chloe Pereira", 56, "Portugal");
        when(bedrock.ask(anyString(), anyString())).thenReturn("Parabéns, {{NAME}}!");

        BirthdayGreetingResponse response = service.greet(PK);

        assertEquals("Portuguese", response.language());
        assertEquals("WARM", response.tone());
        var system = ArgumentCaptor.forClass(String.class);
        verify(bedrock).ask(system.capture(), anyString());
        String prompt = system.getValue();
        assertTrue(prompt.contains("in Portuguese"));
        assertTrue(prompt.contains("warm and polite"));
        for (String personal : new String[] {"Chloe", "Pereira", "secret@example.com", "900", "56", "Portugal"}) {
            assertFalse(prompt.contains(personal), "prompt must not contain " + personal);
        }
    }

    @Test
    void putsTheNameInPlaceOfThePlaceholder() {
        customer("Chloe Pereira", 56, "Portugal");
        when(bedrock.ask(anyString(), anyString())).thenReturn("  Parabéns, {{NAME}}! Feliz aniversário, {{ name }}.  ");

        assertEquals("Parabéns, Chloe Pereira! Feliz aniversário, Chloe Pereira.", service.greet(PK).message());
    }

    @Test
    void messageWithoutPlaceholderIsReturnedAsIs() {
        customer("Chloe", 30, "France");
        when(bedrock.ask(anyString(), anyString())).thenReturn("Joyeux anniversaire !");

        assertEquals("Joyeux anniversaire !", service.greet(PK).message());
    }

    @Test
    void blankNameDropsThePlaceholderAndTidiesSpacing() {
        assertEquals("Happy birthday! Enjoy.", service.insertName("Happy birthday {{NAME}}! Enjoy.", " "));
        assertEquals("Hello, Ann!", service.insertName("Hello, {{NAME}}!", "Ann"));
    }

    @Test
    void missingAgeAndCountryUseNeutralToneAndEnglish() {
        customer("Ann", null, null);
        when(bedrock.ask(anyString(), anyString())).thenReturn("Happy birthday, {{NAME}}!");

        BirthdayGreetingResponse response = service.greet(PK);

        assertEquals("English", response.language());
        assertEquals("NEUTRAL", response.tone());
    }

    @Test
    void emptyModelReplyIsABedrockError() {
        customer("Ann", 40, "France");
        when(bedrock.ask(anyString(), anyString())).thenReturn("  ");

        assertThrows(BedrockService.BedrockException.class, () -> service.greet(PK));
    }

    @Test
    void unknownCustomerIsNotSentToTheModel() {
        when(customers.getCustomer(PK)).thenThrow(new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND));

        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> service.greet(PK));
        verifyNoInteractions(bedrock);
    }
}
