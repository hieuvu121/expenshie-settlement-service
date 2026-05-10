package com.be9expensphie.settlement.service;

import com.be9expensphie.settlement.dto.SettlementDTO.CursorPageDTO;
import com.be9expensphie.settlement.dto.SettlementDTO.SettlementResponseDTO;
import com.be9expensphie.settlement.dto.SettlementDTO.SettlementStatsDTO;
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
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class SettlementService {

    private final SettlementRepository settlementRepo;
    private final WebSocketEventProducer webSocketEventProducer;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final List<SettlementStatus> OPEN_STATUSES = List.of(SettlementStatus.PENDING, SettlementStatus.AWAITING_APPROVAL);

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

    public CursorPageDTO<SettlementResponseDTO> getMemberSettlements(Long memberId, Long householdId, Long cursor, int limit) {
        List<SettlementEntity> page;
        if (cursor == null) {
            page = settlementRepo.findByFromMemberIdAndHouseholdIdOrderByIdDesc(memberId, householdId);
        } else {
            page = settlementRepo.findByFromMemberIdAndHouseholdIdAndIdLessThanOrderByIdDesc(memberId, householdId, cursor);
        }
        boolean hasMore = page.size() > limit;
        List<SettlementEntity> slice = hasMore ? page.subList(0, limit) : page;
        Long nextCursor = hasMore ? slice.get(slice.size() - 1).getId() : null;
        return CursorPageDTO.<SettlementResponseDTO>builder()
                .data(slice.stream().map(this::toDTO).toList())
                .hasMore(hasMore)
                .nextCursor(nextCursor)
                .build();
    }

    public SettlementStatsDTO getPendingStats(Long memberId, Long householdId, LocalDateTime from) {
        List<SettlementEntity> open = settlementRepo
                .findByFromMemberIdAndHouseholdIdAndStatusInAndCreatedAtGreaterThanEqual(memberId, householdId, OPEN_STATUSES, from);
        BigDecimal total = open.stream().map(SettlementEntity::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        return SettlementStatsDTO.builder()
                .pendingSettlements(open.stream().map(this::toDTO).toList())
                .totalPendingAmount(total)
                .build();
    }

    public List<SettlementResponseDTO> getAwaitingApprovals(Long memberId, Long householdId) {
        return settlementRepo.findByToMemberIdAndHouseholdIdAndStatus(memberId, householdId, SettlementStatus.AWAITING_APPROVAL)
                .stream().map(this::toDTO).toList();
    }

    @Transactional
    public SettlementResponseDTO toggleStatus(Long settlementId, Long memberId) {
        SettlementEntity s = findOrThrow(settlementId);
        if (!s.getFromMemberId().equals(memberId)) {
            throw new RuntimeException("Only the paying member can toggle settlement status");
        }
        if (s.getStatus() == SettlementStatus.PENDING) {
            s.setStatus(SettlementStatus.AWAITING_APPROVAL);
        } else if (s.getStatus() == SettlementStatus.AWAITING_APPROVAL) {
            s.setStatus(SettlementStatus.PENDING);
        } else {
            throw new RuntimeException("Cannot toggle a completed settlement");
        }
        return toDTO(settlementRepo.save(s));
    }

    @Transactional
    public SettlementResponseDTO approveSettlement(Long settlementId, Long memberId) {
        SettlementEntity s = findOrThrow(settlementId);
        if (!s.getToMemberId().equals(memberId)) {
            throw new RuntimeException("Only the receiving member can approve the settlement");
        }
        if (s.getStatus() != SettlementStatus.AWAITING_APPROVAL) {
            throw new RuntimeException("Settlement is not awaiting approval");
        }
        s.setStatus(SettlementStatus.COMPLETED);
        s.setPaidAt(LocalDateTime.now());
        SettlementEntity saved = settlementRepo.save(s);
        webSocketEventProducer.publishSettlementPaid(saved);
        return toDTO(saved);
    }

    @Transactional
    public SettlementResponseDTO rejectSettlement(Long settlementId, Long memberId) {
        SettlementEntity s = findOrThrow(settlementId);
        if (!s.getToMemberId().equals(memberId)) {
            throw new RuntimeException("Only the receiving member can reject the settlement");
        }
        if (s.getStatus() != SettlementStatus.AWAITING_APPROVAL) {
            throw new RuntimeException("Settlement is not awaiting approval");
        }
        s.setStatus(SettlementStatus.PENDING);
        return toDTO(settlementRepo.save(s));
    }

    public List<SettlementResponseDTO> getSettlementsByHousehold(Long householdId, SettlementStatus status) {
        List<SettlementEntity> settlements = (status == null)
                ? settlementRepo.findByHouseholdId(householdId)
                : settlementRepo.findByHouseholdIdAndStatus(householdId, status);
        return settlements.stream().map(this::toDTO).toList();
    }

    private SettlementEntity findOrThrow(Long id) {
        return settlementRepo.findById(id)
                .orElseThrow(() -> new NotFoundException("Settlement not found: " + id));
    }

    private SettlementResponseDTO toDTO(SettlementEntity s) {
        return SettlementResponseDTO.builder()
                .id(s.getId())
                .fromMemberId(s.getFromMemberId())
                .toMemberId(s.getToMemberId())
                .fromMemberName(null)
                .toMemberName(null)
                .expense_split_details_id(s.getExpenseId())
                .expenseCategory(null)
                .amount(s.getAmount())
                .date(s.getCreatedAt() != null ? s.getCreatedAt().format(DATE_FMT) : null)
                .currency(null)
                .status(s.getStatus())
                .build();
    }

    public record SplitInfo(Long memberId, BigDecimal amount) {}
}
