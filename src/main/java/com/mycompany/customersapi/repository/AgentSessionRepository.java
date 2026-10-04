package com.mycompany.customersapi.repository;

import com.mycompany.customersapi.domain.AgentSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface AgentSessionRepository extends JpaRepository<AgentSession, UUID> {

    Optional<AgentSession> findBySessionIdAndCompanyId(UUID sessionId, Long companyId);
}
