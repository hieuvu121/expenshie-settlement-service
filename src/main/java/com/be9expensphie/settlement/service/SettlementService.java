package com.be9expensphie.settlement.service;

import com.be9expensphie.settlement.dto.SettlementDTO.SettlementResponseDTO;
import com.be9expensphie.settlement.entity.SettlementEntity;
import com.be9expensphie.settlement.enums.SettlementStatus;
import com.be9expensphie.settlement.exception.NotFoundException;
import com.be9expensphie.settlement.producer.WebSocketEventProducer;
import com.be9expensphie.settlement.repository.SettlementRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class SettlementService {

    private final SettlementRepository settlementRepo;
    private final WebSocketEventProducer webSocketEventProducer;

    @Transactional
    public void createSettlementsForExpense(Long expenseId, Long householdId,
                                            Long createdByMemberId, List<SplitInfo> splits) {
        for (SplitInfo split : splits) {
            if (split.memberId().equals(createdByMemberId)) continue;

            if (settlementRepo.existsByExpenseIdAndFromMemberId(expenseId, split.memberId())) {
                log.info("Settlement already exists for expenseId={}, fromMemberId={} — skipping", expenseId, split.memberId());
                continue;
            }

            settlementRepo.save(SettlementEntity.builder()
                    .expenseId(expenseId)
                    .householdId(householdId)
                    .fromMemberId(split.memberId())
                    .toMemberId(createdByMemberId)
                    .amount(split.amount())
                    .status(SettlementStatus.PENDING)
                    .createdAt(LocalDateTime.now())
                    .build());

            log.info("Created settlement: expenseId={}, fromMemberId={}, amount={}", expenseId, split.memberId(), split.amount());
        }
    }

    public List<SettlementResponseDTO> getSettlementsByHousehold(Long householdId, SettlementStatus status) {
        List<SettlementEntity> settlements = (status == null)
                ? settlementRepo.findByHouseholdId(householdId)
                : settlementRepo.findByHouseholdIdAndStatus(householdId, status);
        return settlements.stream().map(this::toDTO).toList();
    }

    public SettlementResponseDTO getSettlement(Long settlementId) {
        return toDTO(findOrThrow(settlementId));
    }

    @Transactional
    public SettlementResponseDTO paySettlement(Long settlementId) {
        SettlementEntity settlement = findOrThrow(settlementId);
        if (settlement.getStatus() == SettlementStatus.PAID) {
            throw new RuntimeException("Settlement is already paid");
        }
        settlement.setStatus(SettlementStatus.PAID);
        settlement.setPaidAt(LocalDateTime.now());
        SettlementEntity saved = settlementRepo.save(settlement);
        webSocketEventProducer.publishSettlementPaid(saved);
        return toDTO(saved);
    }

    private SettlementEntity findOrThrow(Long id) {
        return settlementRepo.findById(id)
                .orElseThrow(() -> new NotFoundException("Settlement not found: " + id));
    }

    private SettlementResponseDTO toDTO(SettlementEntity s) {
        return SettlementResponseDTO.builder()
                .id(s.getId())
                .expenseId(s.getExpenseId())
                .householdId(s.getHouseholdId())
                .fromMemberId(s.getFromMemberId())
                .toMemberId(s.getToMemberId())
                .amount(s.getAmount())
                .status(s.getStatus())
                .createdAt(s.getCreatedAt())
                .paidAt(s.getPaidAt())
                .build();
    }

    public record SplitInfo(Long memberId, BigDecimal amount) {}
}
