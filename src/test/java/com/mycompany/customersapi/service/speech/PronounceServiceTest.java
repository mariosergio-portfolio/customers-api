package com.mycompany.customersapi.service.speech;

import com.mycompany.customersapi.domain.PronounceLanguage;
import com.mycompany.customersapi.service.CustomerService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PronounceServiceTest {

    @Test
    void looksUpTheCustomerThroughCustomerServiceAndPropagatesNotFound() {
        UUID pk = UUID.randomUUID();
        CustomerService customers = mock(CustomerService.class);
        when(customers.getCustomer(pk)).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));
        PronounceService service = new PronounceService(customers);

        var ex = assertThrows(ResponseStatusException.class, () -> service.pronounce(pk, PronounceLanguage.EN_US));

        assertEquals(404, ex.getStatusCode().value());
        verify(customers).getCustomer(pk);
    }
}
