package com.be9expensphie.settlement.controller;

import com.be9expensphie.settlement.dto.SettlementDTO.SettlementResponseDTO;
import com.be9expensphie.settlement.enums.SettlementStatus;
import com.be9expensphie.settlement.service.SettlementService;
import com.be9expensphie.settlement.service.SettlementService.SplitInfo;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/settlements")
@RequiredArgsConstructor
public class SettlementController {

    private final SettlementService settlementService;

    @GetMapping("/households/{householdId}")
    public ResponseEntity<List<SettlementResponseDTO>> getByHousehold(
            @PathVariable Long householdId,
            @RequestParam(required = false) SettlementStatus status
    ) {
        return ResponseEntity.ok(settlementService.getSettlementsByHousehold(householdId, status));
    }

    @GetMapping("/{settlementId}")
    public ResponseEntity<SettlementResponseDTO> getOne(@PathVariable Long settlementId) {
        return ResponseEntity.ok(settlementService.getSettlement(settlementId));
    }

    @PatchMapping("/{settlementId}/pay")
    public ResponseEntity<SettlementResponseDTO> pay(@PathVariable Long settlementId) {
        return ResponseEntity.ok(settlementService.paySettlement(settlementId));
    }

    // Manual trigger for testing before Kafka consumer is wired up
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
