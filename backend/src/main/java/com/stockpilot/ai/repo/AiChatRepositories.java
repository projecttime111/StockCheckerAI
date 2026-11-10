package com.stockpilot.ai.repo;

import com.stockpilot.ai.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.*;
import java.time.Instant;
import java.util.*;

public final class AiChatRepositories {
    private AiChatRepositories() { }
    public interface Access extends org.springframework.data.repository.Repository<UserTenantMembership,UUID> {
        @org.springframework.data.jpa.repository.Query("select m.role from UserTenantMembership m, Tenant t, UserAccount u where m.tenantId = :tenantId and m.userId = :userId and t.id = m.tenantId and t.status = com.stockpilot.ai.domain.DomainEnums.TenantStatus.ACTIVE and u.id = m.userId and u.active = true and u.emailVerified = true")
        Optional<DomainEnums.Role> findCurrentRole(@org.springframework.data.repository.query.Param("tenantId") UUID tenantId,@org.springframework.data.repository.query.Param("userId") UUID userId);
    }
    public interface Conversations extends JpaRepository<AiConversation,UUID> {
        Optional<AiConversation> findByTenantIdAndUserIdAndId(UUID tenantId,UUID userId,UUID id);
        Page<AiConversation> findByTenantIdAndUserId(UUID tenantId,UUID userId,Pageable page);
    }
    public interface Messages extends JpaRepository<AiMessage,UUID> {
        Page<AiMessage> findByTenantIdAndConversationId(UUID tenantId,UUID conversationId,Pageable page);
        List<AiMessage> findTop12ByTenantIdAndConversationIdOrderByCreatedAtDescIdDesc(UUID tenantId,UUID conversationId);
        void deleteByTenantIdAndConversationId(UUID tenantId,UUID conversationId);
        List<AiMessage> findByTenantIdAndConversationIdAndRequestId(UUID tenantId,UUID conversationId,UUID requestId);
    }
    public interface Runs extends JpaRepository<AiChatRun,UUID> {
        Optional<AiChatRun> findByTenantIdAndUserIdAndRequestId(UUID tenantId,UUID userId,UUID requestId);
        List<AiChatRun> findByTenantIdAndConversationIdAndStatus(UUID tenantId,UUID conversationId,String status);
        long countByTenantIdAndUserIdAndLastAttemptAtAfter(UUID tenantId,UUID userId,Instant since);
        long countByTenantIdAndLastAttemptAtAfter(UUID tenantId,Instant since);
        void deleteByTenantIdAndConversationId(UUID tenantId,UUID conversationId);
    }
}
