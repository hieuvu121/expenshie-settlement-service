package com.be9expensphie.settlement.dto;

import com.be9expensphie.settlement.enums.SettlementStatus;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

public class SettlementDTO {

    @Data
    @Builder
    public static class SettlementResponseDTO {
        private Long id;
        private Long fromMemberId;
        private Long toMemberId;
        private String fromMemberName;
        private String toMemberName;
        private Long expense_split_details_id;
        private String expenseCategory;
        private BigDecimal amount;
        private String date;
        private String currency;
        private SettlementStatus status;
    }

    @Data
    @Builder
    public static class SettlementStatsDTO {
        private List<SettlementResponseDTO> pendingSettlements;
        private BigDecimal totalPendingAmount;
    }

    @Data
    @Builder
    public static class CursorPageDTO<T> {
        private List<T> data;
        private boolean hasMore;
        private Long nextCursor;
    }
}
