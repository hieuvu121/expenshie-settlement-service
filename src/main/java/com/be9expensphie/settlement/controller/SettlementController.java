package com.be9expensphie.settlement.controller;

import com.be9expensphie.settlement.dto.SettlementDTO.CursorPageDTO;
import com.be9expensphie.settlement.dto.SettlementDTO.SettlementResponseDTO;
import com.be9expensphie.settlement.dto.SettlementDTO.SettlementStatsDTO;
import com.be9expensphie.settlement.enums.SettlementStatus;
import com.be9expensphie.settlement.service.SettlementService;
import com.be9expensphie.settlement.service.SettlementService.SplitInfo;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/settlements")
@RequiredArgsConstructor
public class SettlementController {

    private final SettlementService settlementService;

    // Paginated settlements for a member in a household
    // GET /settlements/{memberId}/{householdId}?limit=3&cursor=42
    @GetMapping("/{memberId}/{householdId}")
    public ResponseEntity<Map<String, Object>> getMemberSettlements(
            @PathVariable Long memberId,
            @PathVariable Long householdId,
            @RequestParam(defaultValue = "3") int limit,
            @RequestParam(required = false) Long cursor
    ) {
        CursorPageDTO<SettlementResponseDTO> page = settlementService.getMemberSettlements(memberId, householdId, cursor, limit);
        return ResponseEntity.ok(Map.of("settlements", page));
    }

    // Pending stats for current month
    // GET /settlements/pending/{memberId}/{householdId}/current-month
    @GetMapping("/pending/{memberId}/{householdId}/current-month")
    public ResponseEntity<Map<String, Object>> getCurrentMonthStats(
            @PathVariable Long memberId,
            @PathVariable Long householdId
    ) {
        LocalDateTime startOfMonth = LocalDateTime.now().withDayOfMonth(1).withHour(0).withMinute(0).withSecond(0).withNano(0);
        SettlementStatsDTO stats = settlementService.getPendingStats(memberId, householdId, startOfMonth);
        return ResponseEntity.ok(Map.of("data", stats));
    }

    // Pending stats for last 3 months
    // GET /settlements/pending/{memberId}/{householdId}/last-three-months
    @GetMapping("/pending/{memberId}/{householdId}/last-three-months")
    public ResponseEntity<Map<String, Object>> getLastThreeMonthsStats(
            @PathVariable Long memberId,
            @PathVariable Long householdId
    ) {
        LocalDateTime threeMonthsAgo = LocalDateTime.now().minusMonths(3).withHour(0).withMinute(0).withSecond(0).withNano(0);
        SettlementStatsDTO stats = settlementService.getPendingStats(memberId, householdId, threeMonthsAgo);
        return ResponseEntity.ok(Map.of("data", stats));
    }

    // Toggle PENDING <-> AWAITING_APPROVAL (called by fromMember)
    // PUT /settlements/{settlementId}/toggle/{memberId}
    @PutMapping("/{settlementId}/toggle/{memberId}")
    public ResponseEntity<Map<String, Object>> toggle(
            @PathVariable Long settlementId,
            @PathVariable Long memberId
    ) {
        SettlementResponseDTO dto = settlementService.toggleStatus(settlementId, memberId);
        return ResponseEntity.ok(Map.of("settlement", dto));
    }

    // Settlements awaiting approval by this member (toMemberId = memberId)
    // GET /settlements/awaiting/{memberId}/{householdId}
    @GetMapping("/awaiting/{memberId}/{householdId}")
    public ResponseEntity<Map<String, Object>> getAwaitingApprovals(
            @PathVariable Long memberId,
            @PathVariable Long householdId
    ) {
        List<SettlementResponseDTO> list = settlementService.getAwaitingApprovals(memberId, householdId);
        return ResponseEntity.ok(Map.of("settlements", list));
    }

    // Approve settlement (called by toMember) -> COMPLETED
    // PUT /settlements/{settlementId}/approve/{memberId}
    @PutMapping("/{settlementId}/approve/{memberId}")
    public ResponseEntity<Map<String, Object>> approve(
            @PathVariable Long settlementId,
            @PathVariable Long memberId
    ) {
        SettlementResponseDTO dto = settlementService.approveSettlement(settlementId, memberId);
        return ResponseEntity.ok(Map.of("settlement", dto));
    }

    // Reject settlement (called by toMember) -> PENDING
    // PUT /settlements/{settlementId}/reject/{memberId}
    @PutMapping("/{settlementId}/reject/{memberId}")
    public ResponseEntity<Map<String, Object>> reject(
            @PathVariable Long settlementId,
            @PathVariable Long memberId
    ) {
        SettlementResponseDTO dto = settlementService.rejectSettlement(settlementId, memberId);
        return ResponseEntity.ok(Map.of("settlement", dto));
    }

    // All settlements for a household (admin/internal use)
    @GetMapping("/households/{householdId}")
    public ResponseEntity<List<SettlementResponseDTO>> getByHousehold(
            @PathVariable Long householdId,
            @RequestParam(required = false) SettlementStatus status
    ) {
        return ResponseEntity.ok(settlementService.getSettlementsByHousehold(householdId, status));
    }

    // Manual trigger for Kafka-less testing
    @PostMapping("/households/{householdId}/expenses/{expenseId}")
    public ResponseEntity<?> createForExpense(
            @PathVariable Long householdId,
            @PathVariable Long expenseId,
            @RequestParam Long createdByMemberId,
            @RequestBody List<Map<String, Object>> splits
    ) {
        List<SplitInfo> splitInfos = splits.stream()
                .map(s -> new SplitInfo(
                        Long.valueOf(s.get("memberId").toString()),
                        new BigDecimal(s.get("amount").toString())))
                .toList();
        settlementService.createSettlementsForExpense(expenseId, householdId, createdByMemberId, splitInfos);
        return ResponseEntity.ok().build();
    }
}
