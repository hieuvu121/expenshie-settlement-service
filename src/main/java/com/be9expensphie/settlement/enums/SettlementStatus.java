package com.be9expensphie.settlement.enums;

public enum SettlementStatus {
    PENDING,
    AWAITING_APPROVAL,
    COMPLETED,
    /* Terminal. Set only by a reversal; nothing un-voids. */
    VOIDED
}
