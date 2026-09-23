package com.mycompany.customersapi.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class CustomerPageResponse {

    private int total;
    private List<CustomerResponse> customers;
}
