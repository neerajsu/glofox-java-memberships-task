package com.glofox.memberships.testharness.repository;

import com.glofox.memberships.testharness.repository.entity.TestHarnessSetting;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TestHarnessSettingRepository extends JpaRepository<TestHarnessSetting, UUID> {
    Optional<TestHarnessSetting> findByKey(String key);
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update TestHarnessSetting s set s.value = cast((cast(s.value as integer) - 1) as string) where s.key = :key and s.enabled = true and cast(s.value as integer) > 0")
    int decrementEnabledCounter(@Param("key") String key);
}
