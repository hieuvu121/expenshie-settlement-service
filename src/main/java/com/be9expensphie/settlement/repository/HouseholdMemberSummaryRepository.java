package com.be9expensphie.settlement.repository;

import com.be9expensphie.settlement.entity.HouseholdMemberSummary;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface HouseholdMemberSummaryRepository extends JpaRepository<HouseholdMemberSummary, Long> {

    /*
     * Unfiltered on removedAt on purpose. A member who has left keeps their
     * outstanding settlements and must still be able to pay them, so
     * authorization asks "is this memberId yours?" rather than "are you still
     * a member?".
     */
    Optional<HouseholdMemberSummary> findByMemberIdAndUserId(Long memberId, Long userId);

    /*
     * Filtered on removedAt, unlike the lookup above. Reading a whole
     * household's settlements is a membership privilege, not something a
     * departed member keeps -- they retain access to their own debts only.
     */
    boolean existsByUserIdAndHouseholdIdAndRemovedAtIsNull(Long userId, Long householdId);
}
