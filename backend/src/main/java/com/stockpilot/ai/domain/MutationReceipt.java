package com.stockpilot.ai.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.UUID;

@Entity
@Table(name = "mutation_receipts", uniqueConstraints = @UniqueConstraint(columnNames = {"tenant_id", "user_id", "operation", "request_key"}))
public class MutationReceipt extends TenantOwnedEntity {
    public UUID userId;
    @Column(length = 48) public String operation;
    @Column(length = 128) public String requestKey;
    @Column(length = 64) public String requestHash;
    public UUID resultId;
}
