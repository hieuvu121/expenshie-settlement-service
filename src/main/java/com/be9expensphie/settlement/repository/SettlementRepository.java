package com.be9expensphie.settlement.repository;

import com.be9expensphie.settlement.entity.SettlementEntity;
import com.be9expensphie.settlement.enums.SettlementStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SettlementRepository extends JpaRepository<SettlementEntity, Long> {

    List<SettlementEntity> findByHouseholdId(Long householdId);

    List<SettlementEntity> findByHouseholdIdAndStatus(Long householdId, SettlementStatus status);

    boolean existsByExpenseIdAndFromMemberId(Long expenseId, Long fromMemberId);
}
