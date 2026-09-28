package com.be9expensphie.settlement.service;

import com.be9expensphie.common.event.ExpenseReversalDecided;
import com.be9expensphie.common.event.ReversalOutcome;
import com.be9expensphie.settlement.entity.SettlementEntity;
import com.be9expensphie.settlement.enums.SettlementStatus;
import com.be9expensphie.settlement.repository.SettlementRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The saga's pivot. Before decide() nothing has changed and compensation is
 * free; after an ACCEPTED the debts are gone and only forward recovery works.
 *
 * The two branches easiest to get wrong are the replay -- a lost reply must get
 * the same answer, not a refusal for this saga's own work -- and the veto, which
 * must leave the table completely untouched rather than partially voided.
 */
@ExtendWith(MockitoExtension.class)
class ReversalDecisionServiceTest {

    private static final String SAGA = "saga-1";
    private static final long EXPENSE_ID = 42L;

    @Mock private SettlementRepository settlementRepo;

    @InjectMocks private ReversalDecisionService service;

    private static SettlementEntity settlement(long id, SettlementStatus status, String voidingSagaId) {
        return SettlementEntity.builder()
                .id(id)
                .expenseId(EXPENSE_ID)
                .householdId(3L)
                .fromMemberId(11L)
                .toMemberId(12L)
                .amount(new BigDecimal("25.00"))
                .status(status)
                .voidingSagaId(voidingSagaId)
                .build();
    }

    @Test
    void allOpenSettlementsAreVoidedAndTheReversalIsAccepted() {
        SettlementEntity a = settlement(7, SettlementStatus.PENDING, null);
        SettlementEntity b = settlement(9, SettlementStatus.AWAITING_APPROVAL, null);
        when(settlementRepo.findByExpenseId(EXPENSE_ID)).thenReturn(List.of(a, b));

        ExpenseReversalDecided decision = service.decide(SAGA, EXPENSE_ID);

        assertThat(decision.getOutcome()).isEqualTo(ReversalOutcome.ACCEPTED);
        assertThat(decision.getSagaId()).isEqualTo(SAGA);
        assertThat(decision.getExpenseId()).isEqualTo(EXPENSE_ID);
        assertThat(decision.getSettlementIds()).containsExactlyInAnyOrder(7L, 9L);
        assertThat(a.getStatus()).isEqualTo(SettlementStatus.VOIDED);
        assertThat(b.getStatus()).isEqualTo(SettlementStatus.VOIDED);
        assertThat(a.getVoidingSagaId()).isEqualTo(SAGA);
        verify(settlementRepo).saveAll(any());
    }

    /*
     * The veto, and the reason this flow is a saga at all. Nothing may be
     * written: a partially voided expense is worse than an un-reversed one.
     */
    @Test
    void aCompletedSettlementRefusesTheReversalAndWritesNothing() {
        SettlementEntity open = settlement(7, SettlementStatus.PENDING, null);
        SettlementEntity paid = settlement(9, SettlementStatus.COMPLETED, null);
        when(settlementRepo.findByExpenseId(EXPENSE_ID)).thenReturn(List.of(open, paid));

        ExpenseReversalDecided decision = service.decide(SAGA, EXPENSE_ID);

        assertThat(decision.getOutcome()).isEqualTo(ReversalOutcome.REFUSED);
        assertThat(decision.getReason()).contains("settled");
        assertThat(decision.getSettlementIds()).containsExactly(9L);
        assertThat(open.getStatus()).isEqualTo(SettlementStatus.PENDING);
        verify(settlementRepo, never()).saveAll(any());
        verify(settlementRepo, never()).save(any());
    }

    /*
     * Replay. A lost reply leaves expense-service re-requesting the same sagaId;
     * it has to get the same answer back rather than a refusal for work this
     * saga itself did. This branch is what makes the re-request sweep safe.
     */
    @Test
    void aRepeatedRequestForTheSameSagaIsAcceptedAgainWithoutVoidingTwice() {
        SettlementEntity ours = settlement(7, SettlementStatus.VOIDED, SAGA);
        when(settlementRepo.findByExpenseId(EXPENSE_ID)).thenReturn(List.of(ours));

        ExpenseReversalDecided decision = service.decide(SAGA, EXPENSE_ID);

        assertThat(decision.getOutcome()).isEqualTo(ReversalOutcome.ACCEPTED);
        assertThat(decision.getSettlementIds()).containsExactly(7L);
        verify(settlementRepo, never()).saveAll(any());
    }

    @Test
    void aDifferentSagaCannotReverseWhatIsAlreadyReversed() {
        SettlementEntity theirs = settlement(7, SettlementStatus.VOIDED, "saga-other");
        when(settlementRepo.findByExpenseId(EXPENSE_ID)).thenReturn(List.of(theirs));

        ExpenseReversalDecided decision = service.decide(SAGA, EXPENSE_ID);

        assertThat(decision.getOutcome()).isEqualTo(ReversalOutcome.REFUSED);
        assertThat(decision.getReason()).contains("already been reversed");
        verify(settlementRepo, never()).saveAll(any());
    }

    /** An expense whose splits produced no debts still reverses cleanly. */
    @Test
    void anExpenseWithNoSettlementsIsAccepted() {
        when(settlementRepo.findByExpenseId(EXPENSE_ID)).thenReturn(List.of());

        ExpenseReversalDecided decision = service.decide(SAGA, EXPENSE_ID);

        assertThat(decision.getOutcome()).isEqualTo(ReversalOutcome.ACCEPTED);
        assertThat(decision.getSettlementIds()).isEmpty();
    }
}
