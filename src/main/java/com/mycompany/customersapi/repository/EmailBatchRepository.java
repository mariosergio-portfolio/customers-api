package com.mycompany.customersapi.repository;

import com.mycompany.customersapi.domain.EmailBatch;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface EmailBatchRepository extends JpaRepository<EmailBatch, UUID> {

    Optional<EmailBatch> findByBatchIdAndCompanyId(UUID batchId, Long companyId);

    /** Row lock so two approvals of the same batch cannot send it twice. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM EmailBatch b WHERE b.batchId = :batchId AND b.companyId = :companyId")
    Optional<EmailBatch> lockByBatchIdAndCompanyId(@Param("batchId") UUID batchId, @Param("companyId") Long companyId);
}
