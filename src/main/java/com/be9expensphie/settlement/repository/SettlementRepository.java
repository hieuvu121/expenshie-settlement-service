package com.be9expensphie.settlement.repository;

import com.be9expensphie.settlement.entity.SettlementEntity;
import com.be9expensphie.settlement.enums.SettlementStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface SettlementRepository extends JpaRepository<SettlementEntity, Long> {

    List<SettlementEntity> findByHouseholdId(Long householdId);

    List<SettlementEntity> findByHouseholdIdAndStatus(Long householdId, SettlementStatus status);

    boolean existsByExpenseIdAndFromMemberId(Long expenseId, Long fromMemberId);

    // Member-filtered, newest-first (for paginated "All Settlements")
    List<SettlementEntity> findByFromMemberIdAndHouseholdIdOrderByIdDesc(Long fromMemberId, Long householdId);

    List<SettlementEntity> findByFromMemberIdAndHouseholdIdAndIdLessThanOrderByIdDesc(Long fromMemberId, Long householdId, Long cursor);

    // Stats: pending/awaiting settlements in a date range
    List<SettlementEntity> findByFromMemberIdAndHouseholdIdAndStatusInAndCreatedAtGreaterThanEqual(
            Long fromMemberId, Long householdId, List<SettlementStatus> statuses, LocalDateTime from);

    // Awaiting approval: settlements the current member needs to approve
    List<SettlementEntity> findByToMemberIdAndHouseholdIdAndStatus(Long toMemberId, Long householdId, SettlementStatus status);
}
