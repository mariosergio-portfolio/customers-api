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

@Service
@RequiredArgsConstructor
@Slf4j
public class CustomerService {

    private final CustomerRepository customerRepository;

    public CustomerPageResponse search(Long companyId, String name, String country) {
        String normalizedName    = isBlank(name)    ? null : name.trim();
        String normalizedCountry = isBlank(country) ? null : country.trim();

        log.info("Customer search: companyId={}, name='{}', country='{}'",
                companyId, normalizedName, normalizedCountry);

        List<Customer> customers;

        if (normalizedName == null && normalizedCountry == null) {
            // If no filters are provided, fetch all customers for the company
            customers = customerRepository.findByCompanyIdOrderByNameAsc(companyId);
        }
        else {
            // If filters are provided, perform the search with the given parameters
             customers = customerRepository.searchCustomers(
                    companyId, normalizedName, normalizedCountry);
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
    public String getCustomerName(Long customerId) {
        return customerRepository.findById(customerId)
                .map(Customer::getName)
                .orElseThrow(() -> {
                    log.warn("Customer not found for pronounce: id={}", customerId);
                    return new ResponseStatusException(
                            HttpStatus.NOT_FOUND,
                            "Customer not found: " + customerId);
                });
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
