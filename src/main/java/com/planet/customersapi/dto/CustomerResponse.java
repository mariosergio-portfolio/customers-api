package com.planet.customersapi.dto;

import com.planet.customersapi.domain.Customer;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Builder
public class CustomerResponse {

    private Long id;
    private UUID importId;
    private Long companyId;
    private String name;
    private String email;
    private Integer age;
    private String country;
    private String phone;
    private LocalDateTime createdAt;

    public static CustomerResponse from(Customer c) {
        return CustomerResponse.builder()
                .id(c.getId())
                .importId(c.getImportId())
                .companyId(c.getCompanyId())
                .name(c.getName())
                .email(c.getEmail())
                .age(c.getAge())
                .country(c.getCountry())
                .phone(c.getPhone())
                .createdAt(c.getCreatedAt())
                .build();
    }
}
