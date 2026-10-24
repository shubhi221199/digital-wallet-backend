package com.airtribe.wallet;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ReconciliationScheduler {
    private final WalletService walletService;

    public ReconciliationScheduler(WalletService walletService) {
        this.walletService = walletService;
    }

    @Scheduled(cron = "${wallet.reconciliation.cron}")
    public void reconcileNightly() {
        walletService.runReconciliation();
    }
}