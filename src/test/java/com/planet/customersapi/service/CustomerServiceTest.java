package com.planet.customersapi.service;

import com.planet.customersapi.domain.Customer;
import com.planet.customersapi.domain.FileDomain;
import com.planet.customersapi.dto.CustomerPageResponse;
import com.planet.customersapi.repository.CustomerRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CustomerServiceTest {

    @Mock
    private CustomerRepository customerRepository;

    @InjectMocks
    private CustomerService customerService;

    private Customer sampleCustomer(Long id, String name, String country) {
        return Customer.builder()
                .id(id)
                .importId(UUID.randomUUID())
                .companyId(1L)
                .fileDomain("CUSTOMER")
                .name(name)
                .email(name.toLowerCase().replace(" ", ".") + "@example.com")
                .age(30)
                .country(country)
                .phone(null)
                .createdAt(LocalDateTime.now())
                .build();
    }

    @Test
    void searchWithNoFilters_returnsAllCustomers() {
        List<Customer> data = List.of(
                sampleCustomer(1L, "John Smith", "Portugal"),
                sampleCustomer(2L, "Ana Costa", "Portugal")
        );
        when(customerRepository.searchByCompanyAndDomain(1L, "CUSTOMER", null, null)).thenReturn(data);

        CustomerPageResponse result = customerService.search(1L, FileDomain.CUSTOMER, null, null);

        assertThat(result.getTotal()).isEqualTo(2);
        assertThat(result.getCustomers()).hasSize(2);
        verify(customerRepository).searchByCompanyAndDomain(1L, "CUSTOMER", null, null);
    }

    @Test
    void searchWithNameFilter_passesNormalizedName() {
        List<Customer> data = List.of(sampleCustomer(1L, "John Smith", "Portugal"));
        when(customerRepository.searchByCompanyAndDomain(1L, "CUSTOMER", "john", null)).thenReturn(data);

        CustomerPageResponse result = customerService.search(1L, FileDomain.CUSTOMER, " john ", null);

        assertThat(result.getTotal()).isEqualTo(1);
        verify(customerRepository).searchByCompanyAndDomain(1L, "CUSTOMER", "john", null);
    }

    @Test
    void searchWithCountryFilter_passesNormalizedCountry() {
        List<Customer> data = List.of(
                sampleCustomer(1L, "John Smith", "Portugal"),
                sampleCustomer(4L, "Ana Costa", "Portugal")
        );
        when(customerRepository.searchByCompanyAndDomain(1L, "CUSTOMER", null, "port")).thenReturn(data);

        CustomerPageResponse result = customerService.search(1L, FileDomain.CUSTOMER, null, "port");

        assertThat(result.getTotal()).isEqualTo(2);
        verify(customerRepository).searchByCompanyAndDomain(1L, "CUSTOMER", null, "port");
    }

    @Test
    void searchWithBothFilters_passesBoth() {
        List<Customer> data = List.of(sampleCustomer(1L, "John Smith", "Portugal"));
        when(customerRepository.searchByCompanyAndDomain(1L, "CUSTOMER", "smith", "port")).thenReturn(data);

        CustomerPageResponse result = customerService.search(1L, FileDomain.CUSTOMER, "smith", "port");

        assertThat(result.getTotal()).isEqualTo(1);
        verify(customerRepository).searchByCompanyAndDomain(1L, "CUSTOMER", "smith", "port");
    }

    @Test
    void searchWithBlankFilters_treatsThemAsNull() {
        when(customerRepository.searchByCompanyAndDomain(1L, "CUSTOMER", null, null)).thenReturn(List.of());

        CustomerPageResponse result = customerService.search(1L, FileDomain.CUSTOMER, "  ", "");

        assertThat(result.getTotal()).isZero();
        verify(customerRepository).searchByCompanyAndDomain(1L, "CUSTOMER", null, null);
    }
}
