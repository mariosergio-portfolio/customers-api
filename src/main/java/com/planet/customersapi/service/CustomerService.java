package com.planet.customersapi.service;

import com.planet.customersapi.domain.Customer;
import com.planet.customersapi.dto.CustomerPageResponse;
import com.planet.customersapi.dto.CustomerResponse;
import com.planet.customersapi.repository.CustomerRepository;
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
     * Returns the name of the customer with the given ID, or throws 404 if not found.
     */
    public String getCustomerName(UUID customerPk) {
        return customerRepository.findById(customerPk)
                .map(Customer::getName)
                .orElseThrow(() -> {
                    log.warn("Customer not found for pronounce: customerPk={}", customerPk);
                    return new ResponseStatusException(
                            HttpStatus.NOT_FOUND,
                            "Customer not found: " + customerPk);
                });
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
