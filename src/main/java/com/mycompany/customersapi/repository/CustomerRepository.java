package com.mycompany.customersapi.repository;

import com.mycompany.customersapi.domain.Customer;
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

    @Query("""
            SELECT c FROM Customer c
            WHERE c.companyId = :companyId
              AND (CAST(:name AS string)    IS NULL OR LOWER(c.name)    LIKE LOWER(CONCAT('%', CAST(:name AS string), '%')))
              AND (CAST(:country AS string) IS NULL OR LOWER(c.country) LIKE LOWER(CONCAT('%', CAST(:country AS string), '%')))
            ORDER BY c.name ASC
            """)
    List<Customer> searchCustomersOrderByName(
            @Param("companyId") Long companyId,
            @Param("name") String name,
            @Param("country") String country);

    @Query("""
            SELECT c FROM Customer c
            WHERE c.companyId = :companyId
              AND (CAST(:name AS string)    IS NULL OR LOWER(c.name)    LIKE LOWER(CONCAT('%', CAST(:name AS string), '%')))
              AND (CAST(:country AS string) IS NULL OR LOWER(c.country) LIKE LOWER(CONCAT('%', CAST(:country AS string), '%')))
            ORDER BY c.id ASC
            """)
    List<Customer> searchCustomersOrderById(
            @Param("companyId") Long companyId,
            @Param("name") String name,
            @Param("country") String country);


}
