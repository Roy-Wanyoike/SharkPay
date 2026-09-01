package com.sharkpay.risk.storage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** rule_sets row: versioned configurations, exactly one active. */
@Entity
@Table(name = "rule_sets")
public class RuleSetEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    public Long id;

    @Column(name = "version", nullable = false)
    public long version;

    /** jsonb — RuleSetMapper JSON shape. */
    @Column(name = "config", nullable = false, columnDefinition = "jsonb")
    public String config;

    @Column(name = "active", nullable = false)
    public boolean active;

    protected RuleSetEntity() {
        // JPA
    }

    public RuleSetEntity(Long id, long version, String config, boolean active) {
        this.id = id;
        this.version = version;
        this.config = config;
        this.active = active;
    }
}
