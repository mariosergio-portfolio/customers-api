package com.mycompany.customersapi.service;

import com.mycompany.customersapi.domain.Customer;
import com.mycompany.customersapi.domain.GreetingTone;
import com.mycompany.customersapi.dto.BirthdayGreetingResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Writes a birthday greeting for one customer with Bedrock, in the main language of the customer's
 * country and with a tone that follows the customer's age.
 *
 * The service decides the language ({@link CountryLanguages}) and the tone ({@link GreetingTone}),
 * and the model only writes the text. The model never sees the customer's name, email, phone,
 * country or exact age: it is asked to write the placeholder {@value #NAME_PLACEHOLDER} and the
 * service puts the name in afterwards.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BirthdayGreetingService {

    static final String NAME_PLACEHOLDER = "{{NAME}}";

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*NAME\\s*}}", Pattern.CASE_INSENSITIVE);

    private final CustomerService customerService;
    private final BedrockService  bedrockService;

    public BirthdayGreetingResponse greet(UUID customerPk) {
        Customer customer = customerService.getCustomer(customerPk);
        String language = CountryLanguages.languageFor(customer.getCountry());
        GreetingTone tone = GreetingTone.fromAge(customer.getAge());
        log.debug("Birthday greeting: customerPk={}, language={}, tone={}", customerPk, language, tone);

        String reply = bedrockService.ask(systemPrompt(language, tone), "Write the birthday greeting.");
        if (reply == null || reply.isBlank()) {
            throw new BedrockService.BedrockException("The model returned an empty greeting", null);
        }
        return new BirthdayGreetingResponse(insertName(reply.trim(), customer.getName()), language, tone.name());
    }

    String systemPrompt(String language, GreetingTone tone) {
        return """
                You write birthday greetings for a company's customers.
                Write one birthday greeting in %s.
                Tone: %s. Express it through word choice and, where the language has them, formal or informal forms of address.
                Address the customer with the exact placeholder %s, which will be replaced by the customer's name afterwards.
                Do not invent a name, and do not mention the customer's age or country.
                Do not assume the customer's gender: use gender-neutral wording.
                Reply with the greeting text only, in two to four sentences: no title, no explanation, no translation.
                """.formatted(language, tone.description(), NAME_PLACEHOLDER);
    }

    /** Replaces the placeholder with the name; with no name the placeholder is dropped and spacing is tidied. */
    String insertName(String text, String name) {
        String clean = name == null ? "" : name.trim();
        String result = PLACEHOLDER.matcher(text).replaceAll(Matcher.quoteReplacement(clean));
        if (clean.isEmpty()) {
            result = result.replaceAll("\\s+([,.!?:;])", "$1").replaceAll(" {2,}", " ");
        }
        return result;
    }
}
