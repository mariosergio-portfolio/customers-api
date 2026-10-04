package com.mycompany.customersapi.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** A conversation with the company agent: its turns, and the email batch the user is refining. */
@Entity
@Table(name = "AGENT_SESSION")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AgentSession {

    @Id
    @Column(name = "session_id", nullable = false, updatable = false)
    private UUID sessionId;

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    /** Email batch of this conversation, if the agent drafted one. It may have been sent since. */
    @Column(name = "batch_id")
    private UUID batchId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Builder.Default
    @OneToMany(mappedBy = "session", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("seq ASC")
    private List<AgentTurn> turns = new ArrayList<>();

    /** Appends a turn; its position is the number of turns already in the session. */
    public void addTurn(AgentTurnRole role, String text) {
        turns.add(AgentTurn.builder()
                .turnId(UUID.randomUUID())
                .session(this)
                .seq(turns.size())
                .role(role)
                .text(text)
                .build());
    }
}
