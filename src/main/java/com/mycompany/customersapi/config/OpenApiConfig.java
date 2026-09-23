package com.mycompany.customersapi.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI customersOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Customers API")
                        .description("REST API for querying imported customers with partial text search on name and/or country")
                        .version("1.0.0"));
    }
}
