package com.be9expensphie.settlement.dto;

import com.be9expensphie.settlement.enums.SettlementStatus;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class SettlementDTO {

    @Data
    @Builder
    public static class SettlementResponseDTO {
        private Long id;
        private Long expenseId;
        private Long householdId;
        private Long fromMemberId;
        private Long toMemberId;
        private BigDecimal amount;
        private SettlementStatus status;
        private LocalDateTime createdAt;
        private LocalDateTime paidAt;
    }
}
