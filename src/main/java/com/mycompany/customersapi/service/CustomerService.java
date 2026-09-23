package com.mycompany.customersapi.service;

import com.mycompany.customersapi.domain.Customer;
import com.mycompany.customersapi.dto.CustomerPageResponse;
import com.mycompany.customersapi.dto.CustomerResponse;
import com.mycompany.customersapi.repository.CustomerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class CustomerService {

    private final CustomerRepository customerRepository;
    private final PronounceService    pronounceService;

    public CustomerPageResponse search(Long companyId, String name, String country, String orderBy) {
        String normalizedName    = isBlank(name)    ? null : name.trim();
        String normalizedCountry = isBlank(country) ? null : country.trim();
        boolean byName = "name".equalsIgnoreCase(orderBy);

        log.info("Customer search: companyId={}, name='{}', country='{}', orderBy='{}'",
                companyId, normalizedName, normalizedCountry, orderBy);

        List<Customer> customers;

        if (normalizedName == null && normalizedCountry == null) {
            customers = byName
                    ? customerRepository.findByCompanyIdOrderByNameAsc(companyId)
                    : customerRepository.findByCompanyIdOrderByIdAsc(companyId);
        } else {
            customers = byName
                    ? customerRepository.searchCustomersOrderByName(companyId, normalizedName, normalizedCountry)
                    : customerRepository.searchCustomersOrderById(companyId, normalizedName, normalizedCountry);
        }

        List<CustomerResponse> items = customers.stream()
                .map(CustomerResponse::from)
                .toList();

        return CustomerPageResponse.builder()
                .total(items.size())
                .customers(items)
                .build();
    }

    /**
     * Returns the customer with the given PK, or throws 404 if not found.
     */
    public Customer getCustomer(UUID customerPk) {
        return customerRepository.findById(customerPk)
                .orElseThrow(() -> {
                    log.warn("Customer not found for pronounce: customerPk={}", customerPk);
                    return new ResponseStatusException(
                            HttpStatus.NOT_FOUND,
                            "Customer not found: " + customerPk);
                });
    }

    /**
     * Looks up the customer by PK and delegates synthesis to PronounceService.
     */
    public byte[] pronounce(UUID customerPk, String languageCode) {
        Customer customer = getCustomer(customerPk);
        return pronounceService.synthesize(customer.getName(), customer.getCountry(), languageCode);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
