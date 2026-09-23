package com.planet.customersapi.repository;

import com.planet.customersapi.domain.Customer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface CustomerRepository extends JpaRepository<Customer, UUID> {

    List<Customer> findByCompanyIdOrderByNameAsc(Long companyId);

    List<Customer> findByCompanyIdOrderByIdAsc(Long companyId);

    /**
     * Search customers for a given company and domain with optional partial text filters on name and/or country.
     * Both filters are case-insensitive. Either filter is optional (null skips that predicate).
     * When both are provided they are combined with AND.
     */
    @Query("""
            SELECT c FROM Customer c
            WHERE c.companyId = :companyId
              AND (:name    IS NULL OR LOWER(c.name)    LIKE LOWER(CONCAT('%', :name, '%')))
              AND (:country IS NULL OR LOWER(c.country) LIKE LOWER(CONCAT('%', :country, '%')))
            ORDER BY c.customerPk
            """)
    List<Customer> searchCustomers(
            @Param("companyId") Long companyId,
            @Param("name") String name,
            @Param("country") String country);


}
