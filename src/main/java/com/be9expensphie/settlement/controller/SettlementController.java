package com.be9expensphie.settlement.controller;

import com.be9expensphie.settlement.dto.SettlementDTO.CursorPageDTO;
import com.be9expensphie.settlement.dto.SettlementDTO.SettlementResponseDTO;
import com.be9expensphie.settlement.dto.SettlementDTO.SettlementStatsDTO;
import com.be9expensphie.settlement.enums.SettlementStatus;
import com.be9expensphie.settlement.security.SettlementAuthorizer;
import com.be9expensphie.settlement.service.SettlementService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/*
 * memberId stays in the path so the web and mobile clients keep working, but it
 * is now verified against X-User-Id rather than trusted. The gateway derives
 * that header from a verified JWT; nothing here may take it from a body or a
 * path segment.
 */
@RestController
@RequestMapping("/settlements")
@RequiredArgsConstructor
public class SettlementController {

    private final SettlementService settlementService;
    private final SettlementAuthorizer authorizer;

    // Paginated settlements for a member in a household
    // GET /settlements/{memberId}/{householdId}?limit=3&cursor=42
    @GetMapping("/{memberId}/{householdId}")
    public ResponseEntity<Map<String, Object>> getMemberSettlements(
            @RequestHeader("X-User-Id") Long userId,
            @PathVariable Long memberId,
            @PathVariable Long householdId,
            @RequestParam(defaultValue = "3") int limit,
            @RequestParam(required = false) Long cursor
    ) {
        authorizer.requireOwnMember(userId, memberId);
        CursorPageDTO<SettlementResponseDTO> page = settlementService.getMemberSettlements(memberId, householdId, cursor, limit);
        return ResponseEntity.ok(Map.of("settlements", page));
    }

    // Pending stats for current month
    // GET /settlements/pending/{memberId}/{householdId}/current-month
    @GetMapping("/pending/{memberId}/{householdId}/current-month")
    public ResponseEntity<Map<String, Object>> getCurrentMonthStats(
            @RequestHeader("X-User-Id") Long userId,
            @PathVariable Long memberId,
            @PathVariable Long householdId
    ) {
        authorizer.requireOwnMember(userId, memberId);
        LocalDateTime startOfMonth = LocalDateTime.now().withDayOfMonth(1).withHour(0).withMinute(0).withSecond(0).withNano(0);
        SettlementStatsDTO stats = settlementService.getPendingStats(memberId, householdId, startOfMonth);
        return ResponseEntity.ok(Map.of("data", stats));
    }

    // Pending stats for last 3 months
    // GET /settlements/pending/{memberId}/{householdId}/last-three-months
    @GetMapping("/pending/{memberId}/{householdId}/last-three-months")
    public ResponseEntity<Map<String, Object>> getLastThreeMonthsStats(
            @RequestHeader("X-User-Id") Long userId,
            @PathVariable Long memberId,
            @PathVariable Long householdId
    ) {
        authorizer.requireOwnMember(userId, memberId);
        LocalDateTime threeMonthsAgo = LocalDateTime.now().minusMonths(3).withHour(0).withMinute(0).withSecond(0).withNano(0);
        SettlementStatsDTO stats = settlementService.getPendingStats(memberId, householdId, threeMonthsAgo);
        return ResponseEntity.ok(Map.of("data", stats));
    }

    // Toggle PENDING <-> AWAITING_APPROVAL (called by fromMember)
    // PUT /settlements/{settlementId}/toggle/{memberId}
    @PutMapping("/{settlementId}/toggle/{memberId}")
    public ResponseEntity<Map<String, Object>> toggle(
            @RequestHeader("X-User-Id") Long userId,
            @PathVariable Long settlementId,
            @PathVariable Long memberId
    ) {
        authorizer.requireOwnMember(userId, memberId);
        SettlementResponseDTO dto = settlementService.toggleStatus(settlementId, memberId);
        return ResponseEntity.ok(Map.of("settlement", dto));
    }

    // Settlements awaiting approval by this member (toMemberId = memberId)
    // GET /settlements/awaiting/{memberId}/{householdId}
    @GetMapping("/awaiting/{memberId}/{householdId}")
    public ResponseEntity<Map<String, Object>> getAwaitingApprovals(
            @RequestHeader("X-User-Id") Long userId,
            @PathVariable Long memberId,
            @PathVariable Long householdId
    ) {
        authorizer.requireOwnMember(userId, memberId);
        List<SettlementResponseDTO> list = settlementService.getAwaitingApprovals(memberId, householdId);
        return ResponseEntity.ok(Map.of("settlements", list));
    }

    // Approve settlement (called by toMember) -> COMPLETED
    // PUT /settlements/{settlementId}/approve/{memberId}
    @PutMapping("/{settlementId}/approve/{memberId}")
    public ResponseEntity<Map<String, Object>> approve(
            @RequestHeader("X-User-Id") Long userId,
            @PathVariable Long settlementId,
            @PathVariable Long memberId
    ) {
        authorizer.requireOwnMember(userId, memberId);
        SettlementResponseDTO dto = settlementService.approveSettlement(settlementId, memberId);
        return ResponseEntity.ok(Map.of("settlement", dto));
    }

    // Reject settlement (called by toMember) -> PENDING
    // PUT /settlements/{settlementId}/reject/{memberId}
    @PutMapping("/{settlementId}/reject/{memberId}")
    public ResponseEntity<Map<String, Object>> reject(
            @RequestHeader("X-User-Id") Long userId,
            @PathVariable Long settlementId,
            @PathVariable Long memberId
    ) {
        authorizer.requireOwnMember(userId, memberId);
        SettlementResponseDTO dto = settlementService.rejectSettlement(settlementId, memberId);
        return ResponseEntity.ok(Map.of("settlement", dto));
    }

    // All settlements for a household, for any member of it
    @GetMapping("/households/{householdId}")
    public ResponseEntity<List<SettlementResponseDTO>> getByHousehold(
            @RequestHeader("X-User-Id") Long userId,
            @PathVariable Long householdId,
            @RequestParam(required = false) SettlementStatus status
    ) {
        authorizer.requireHouseholdMember(userId, householdId);
        return ResponseEntity.ok(settlementService.getSettlementsByHousehold(householdId, status));
    }

    /*
     * POST /households/{householdId}/expenses/{expenseId} is gone. It was
     * labelled "manual trigger for Kafka-less testing" and took the household,
     * the expense, the creditor and the split amounts straight from the
     * request, with no authorization of any kind -- so any caller could invent
     * debts between arbitrary members. No client ever used it; settlements are
     * created by consuming EXPENSE_APPROVED, which is the only path that can
     * verify the expense was actually approved.
     */
}
