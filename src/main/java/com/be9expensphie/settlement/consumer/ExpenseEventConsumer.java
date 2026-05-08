package com.be9expensphie.settlement.consumer;

import com.be9expensphie.common.event.ExpenseEvent;
import com.be9expensphie.settlement.service.SettlementService;
import com.be9expensphie.settlement.service.SettlementService.SplitInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class ExpenseEventConsumer {

    private final SettlementService settlementService;

    @RetryableTopic(
            attempts = "3",
            backoff = @Backoff(delay = 1000, multiplier = 2),
            dltTopicSuffix = ".DLT",
            kafkaTemplate = "retryKafkaTemplate"
    )
    @KafkaListener(topics = "expense-events", containerFactory = "expenseEventKafkaListenerContainerFactory")
    public void consume(ExpenseEvent event) {
        if (!"EXPENSE_APPROVED".equals(event.getEventType())) return;

        if (event.getSplits() == null || event.getSplits().isEmpty()) {
            log.warn("EXPENSE_APPROVED event has no splits: expenseId={}", event.getExpenseId());
            return;
        }

        log.info("Received EXPENSE_APPROVED: expenseId={}, householdId={}", event.getExpenseId(), event.getHouseholdId());

        List<SplitInfo> splits = event.getSplits().stream()
                .map(s -> new SplitInfo(s.getMemberId(), s.getAmount()))
                .toList();

        settlementService.createSettlementsForExpense(
                event.getExpenseId(),
                event.getHouseholdId(),
                event.getCreatedByMemberId(),
                splits
        );
    }

    @DltHandler
    public void handleDlt(ExpenseEvent event) {
        log.error("expense-events exhausted retries — moved to DLT: expenseId={}, householdId={}",
                event.getExpenseId(), event.getHouseholdId());
    }
}
