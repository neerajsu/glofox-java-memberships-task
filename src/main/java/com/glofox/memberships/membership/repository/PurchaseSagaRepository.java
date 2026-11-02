package com.glofox.memberships.membership.repository;

import com.glofox.memberships.membership.model.PurchaseSagaStatus;
import com.glofox.memberships.membership.repository.entity.PurchaseSaga;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PurchaseSagaRepository extends JpaRepository<PurchaseSaga, UUID> {

    @Query("""
            select saga from PurchaseSaga saga
             where saga.status in :statuses
               and saga.updatedAt <= :inactiveBefore
               and saga.nextRecoveryAt is not null
               and saga.nextRecoveryAt <= :now
               and (saga.recoveryLeaseUntil is null or saga.recoveryLeaseUntil <= :now)
             order by saga.nextRecoveryAt asc, saga.id asc
            """)
    List<PurchaseSaga> findRecoverable(
            @Param("statuses") List<PurchaseSagaStatus> statuses,
            @Param("now") Instant now,
            @Param("inactiveBefore") Instant inactiveBefore,
            Pageable pageable);

    Optional<PurchaseSaga> findByClientIdempotencyKey(String clientIdempotencyKey);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update PurchaseSaga saga
               set saga.recoveryLeaseUntil = :leaseUntil,
                   saga.version = saga.version + 1
             where saga.id = :id
               and saga.status = :expectedStatus
               and saga.version = :expectedVersion
               and saga.updatedAt <= :inactiveBefore
               and saga.nextRecoveryAt <= :now
               and (saga.recoveryLeaseUntil is null or saga.recoveryLeaseUntil <= :now)
            """)
    int claimForRecovery(
            @Param("id") UUID id,
            @Param("expectedStatus") PurchaseSagaStatus expectedStatus,
            @Param("expectedVersion") Long expectedVersion,
            @Param("now") Instant now,
            @Param("inactiveBefore") Instant inactiveBefore,
            @Param("leaseUntil") Instant leaseUntil);
}
