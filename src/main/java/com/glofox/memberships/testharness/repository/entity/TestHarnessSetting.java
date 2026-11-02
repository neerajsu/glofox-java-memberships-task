package com.glofox.memberships.testharness.repository.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "test_harness_settings")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TestHarnessSetting {
    @Id private UUID id;
    @Column(name = "setting_key", nullable = false, unique = true, length = 150) private String key;
    @Column(name = "setting_value", nullable = false, length = 500) private String value;
    @Column(length = 500) private String description;
    @Column(nullable = false) private boolean enabled;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @Column(name = "updated_by", length = 100) private String updatedBy;
    @Version private Long version;

    public TestHarnessSetting(String key, String value, String description, boolean enabled, String updatedBy) {
        this.id = UUID.randomUUID(); this.key = key; this.value = value; this.description = description;
        this.enabled = enabled; this.updatedBy = updatedBy;
    }
    public void update(String value, boolean enabled, String updatedBy) {
        this.value = value; this.enabled = enabled; this.updatedBy = updatedBy;
    }
    @PrePersist @PreUpdate void timestamp() { updatedAt = Instant.now(); }
}
