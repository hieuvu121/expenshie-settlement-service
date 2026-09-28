package com.be9expensphie.settlement.service;

import com.be9expensphie.settlement.entity.SettlementEntity;
import com.be9expensphie.settlement.enums.SettlementStatus;
import com.be9expensphie.settlement.repository.SettlementRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A voided settlement is no longer a debt.
 *
 * Without these guards the reversal saga leaves a hole: the expense is
 * REVERSED, the debt is VOIDED, and a member can still walk up and pay it --
 * or a creditor approve it as paid -- because the ownership checks alone are
 * perfectly happy with a voided row.
 */
@ExtendWith(MockitoExtension.class)
class SettlementVoidedGuardTest {

    private static final long SETTLEMENT_ID = 7L;
    private static final long FROM_MEMBER = 11L;
    private static final long TO_MEMBER = 12L;

    @Mock private SettlementRepository settlementRepo;

    @InjectMocks private SettlementService service;

    private void settlementIsVoided() {
        when(settlementRepo.findById(SETTLEMENT_ID)).thenReturn(Optional.of(
                SettlementEntity.builder()
                        .id(SETTLEMENT_ID)
                        .expenseId(42L)
                        .householdId(3L)
                        .fromMemberId(FROM_MEMBER)
                        .toMemberId(TO_MEMBER)
                        .amount(new BigDecimal("25.00"))
                        .status(SettlementStatus.VOIDED)
                        .voidingSagaId("saga-1")
                        .build()));
    }

    @Test
    void aVoidedSettlementCannotBeMarkedPaid() {
        settlementIsVoided();

        assertThatThrownBy(() -> service.toggleStatus(SETTLEMENT_ID, FROM_MEMBER))
                .hasMessageContaining("voided");

        verify(settlementRepo, never()).save(any());
    }

    @Test
    void aVoidedSettlementCannotBeApproved() {
        settlementIsVoided();

        assertThatThrownBy(() -> service.approveSettlement(SETTLEMENT_ID, TO_MEMBER))
                .hasMessageContaining("voided");

        verify(settlementRepo, never()).save(any());
    }

    @Test
    void aVoidedSettlementCannotBeRejected() {
        settlementIsVoided();

        assertThatThrownBy(() -> service.rejectSettlement(SETTLEMENT_ID, TO_MEMBER))
                .hasMessageContaining("voided");

        verify(settlementRepo, never()).save(any());
    }
}
