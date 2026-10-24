package com.airtribe.wallet;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping
public class WalletController {
    private final WalletService walletService;

    public WalletController(WalletService walletService) {
        this.walletService = walletService;
    }

    @PostMapping("/users/{userId}/wallets")
    ResponseEntity<byte[]> openWallet(@PathVariable long userId, @RequestHeader("Idempotency-Key") UUID key,
                                      @RequestBody OpenWalletRequest request) {
        return response(walletService.openWallet(userId, key, request));
    }

    @GetMapping("/users/{userId}/wallets/{accountId}/balance")
    Map<String, Object> balance(@PathVariable long userId, @PathVariable long accountId) {
        return walletService.balance(userId, accountId);
    }

    @GetMapping("/users/{userId}/wallets/{accountId}/statement")
    Map<String, Object> statement(@PathVariable long userId, @PathVariable long accountId,
                                  @RequestParam(defaultValue = "50") int limit,
                                  @RequestParam(defaultValue = "0") int offset) {
        return walletService.statement(userId, accountId, limit, offset);
    }

    @PostMapping("/users/{userId}/wallets/{accountId}/top-ups")
    ResponseEntity<byte[]> topUp(@PathVariable long userId, @PathVariable long accountId,
                                 @RequestHeader("Idempotency-Key") UUID key, @RequestBody MoneyRequest request) {
        return response(walletService.move(userId, accountId, null, "TOP_UP", key, request));
    }

    @PostMapping("/users/{userId}/wallets/{accountId}/withdrawals")
    ResponseEntity<byte[]> withdraw(@PathVariable long userId, @PathVariable long accountId,
                                    @RequestHeader("Idempotency-Key") UUID key, @RequestBody MoneyRequest request) {
        return response(walletService.move(userId, accountId, null, "WITHDRAWAL", key, request));
    }

    @PostMapping("/users/{userId}/wallets/{accountId}/transfers")
    ResponseEntity<byte[]> transfer(@PathVariable long userId, @PathVariable long accountId,
                                    @RequestHeader("Idempotency-Key") UUID key, @RequestBody PeerTransferRequest request) {
        return response(walletService.move(userId, accountId, request.targetWalletId(), "PEER", key,
                new MoneyRequest(request.amountPaise(), request.note())));
    }

    @PostMapping("/users/{userId}/transfers/{transferId}/reversals")
    ResponseEntity<byte[]> reverse(@PathVariable long userId, @PathVariable long transferId,
                                   @RequestHeader("Idempotency-Key") UUID key) {
        return response(walletService.reverse(userId, transferId, key));
    }

    @PostMapping("/admin/users/{userId}/transfers/{transferId}/release")
    ResponseEntity<byte[]> release(@PathVariable long userId, @PathVariable long transferId,
                                   @RequestHeader("Idempotency-Key") UUID key) {
        return response(walletService.releaseHeld(userId, transferId, key));
    }

    @PostMapping("/users/{userId}/statement/ask")
    ResponseEntity<byte[]> askStatement(@PathVariable long userId, @RequestHeader("Idempotency-Key") UUID key,
                                        @RequestBody StatementQuestionRequest request) {
        return response(walletService.askStatement(userId, key, request));
    }

    @PostMapping("/admin/reconciliation/run")
    ResponseEntity<byte[]> reconcileNow(@RequestHeader("Idempotency-Key") UUID key) {
        return response(walletService.requestReconciliation(key));
    }

    private ResponseEntity<byte[]> response(WalletService.SavedResponse saved) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new ResponseEntity<>(saved.body().getBytes(StandardCharsets.UTF_8), headers, HttpStatus.valueOf(saved.statusCode()));
    }

    public record OpenWalletRequest(String displayName) {}
    public record MoneyRequest(long amountPaise, String note) {}
    public record PeerTransferRequest(long targetWalletId, long amountPaise, String note) {}
    public record StatementQuestionRequest(String question) {}
}