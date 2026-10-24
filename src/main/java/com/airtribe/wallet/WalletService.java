package com.airtribe.wallet;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

@Service
public class WalletService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final StatementQuestionParser questionParser;
    private final PlatformTransactionManager transactionManager;

    public WalletService(JdbcTemplate jdbc, ObjectMapper objectMapper, StatementQuestionParser questionParser,
                         PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.questionParser = questionParser;
        this.transactionManager = manager;
    }

    public SavedResponse openWallet(long userId, UUID key, WalletController.OpenWalletRequest request) {
        return idempotent(userId, key, "OPEN_WALLET", request, () -> {
            if (request.displayName() == null || request.displayName().isBlank()) {
                throw new WalletException("INVALID_REQUEST", "displayName is required", HttpStatus.BAD_REQUEST);
            }
            long accountId = jdbc.queryForObject("""
                    INSERT INTO accounts(owner_user_id, account_type, display_name)
                    VALUES (?, 'WALLET', ?) RETURNING account_id
                    """, Long.class, userId, request.displayName());
            return jsonResponse(201, Map.of("accountId", accountId, "currency", "INR", "balancePaise", 0));
        });
    }

    public SavedResponse move(long userId, long sourceId, Long targetId, String type, UUID key,
                             WalletController.MoneyRequest request) {
        return idempotent(userId, key, type + ":" + sourceId + ":" + targetId, request,
                () -> executeMove(userId, sourceId, targetId, type, request));
    }

    public Map<String, Object> balance(long userId, long accountId) {
        verifyWalletOwner(userId, accountId);
        Long amount = jdbc.queryForObject("SELECT balance_paise FROM accounts WHERE account_id = ?", Long.class, accountId);
        return Map.of("accountId", accountId, "currency", "INR", "balancePaise", amount);
    }

    public Map<String, Object> statement(long userId, long accountId, int limit, int offset) {
        verifyWalletOwner(userId, accountId);
        if (limit < 1 || limit > 200 || offset < 0) {
            throw new WalletException("INVALID_PAGINATION", "limit must be 1..200 and offset must be non-negative", HttpStatus.BAD_REQUEST);
        }
        List<Map<String, Object>> entries = jdbc.query("""
                SELECT l.ledger_entry_id AS entry_id, l.transfer_id, t.transfer_type, l.direction,
                       l.amount_paise, l.created_at, t.note
                FROM ledger_entries l JOIN transfers t ON t.transfer_id = l.transfer_id
                WHERE l.account_id = ? ORDER BY l.created_at DESC, l.ledger_entry_id DESC
                LIMIT ? OFFSET ?
                """, (rs, rowNum) -> entryMap(rs), accountId, limit, offset);
        return Map.of("accountId", accountId, "limit", limit, "offset", offset, "entries", entries);
    }

    private SavedResponse executeMove(long userId, long sourceId, Long requestedTargetId, String type,
                                      WalletController.MoneyRequest request) {
        if (request.amountPaise() <= 0) {
            throw new WalletException("INVALID_AMOUNT", "amountPaise must be positive", HttpStatus.BAD_REQUEST);
        }
        long targetId = switch (type) {
            case "TOP_UP" -> systemAccount("CASH_IN");
            case "WITHDRAWAL" -> systemAccount("CASH_OUT");
            case "PEER" -> requestedTargetId == null ? 0 : requestedTargetId;
            default -> throw new IllegalArgumentException("Unsupported transfer type");
        };
        long debitAccount = type.equals("TOP_UP") ? targetId : sourceId;
        long creditAccount = type.equals("TOP_UP") ? sourceId : targetId;
        if (type.equals("WITHDRAWAL")) {
            creditAccount = targetId;
        }
        verifyWalletOwner(userId, sourceId);
        if (type.equals("PEER")) {
            Integer walletCount = jdbc.queryForObject("SELECT COUNT(*) FROM accounts WHERE account_id = ? AND account_type = 'WALLET'", Integer.class, targetId);
            if (walletCount == null || walletCount != 1) {
                throw new WalletException("WALLET_NOT_FOUND", "Target wallet was not found", HttpStatus.NOT_FOUND);
            }
        }
        Map<Long, Long> balances = lockAccounts(debitAccount, creditAccount);
        Long sourceBalance = balances.get(debitAccount);
        if (sourceBalance == null) {
            throw new WalletException("WALLET_NOT_FOUND", "Source wallet was not found", HttpStatus.NOT_FOUND);
        }
        if (isUserWallet(debitAccount) && sourceBalance < request.amountPaise()) {
            throw new WalletException("INSUFFICIENT_FUNDS", "Wallet balance is too low", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        List<RiskRule> firedRules = jdbc.query("""
                SELECT risk_rule_id, rule_name, action FROM risk_rules
                WHERE active AND minimum_amount_paise <= ?
                ORDER BY minimum_amount_paise DESC, risk_rule_id
                """, (rs, rowNum) -> new RiskRule(rs.getLong("risk_rule_id"), rs.getString("rule_name"),
                rs.getString("action")), request.amountPaise());
        String status = firedRules.stream().anyMatch(rule -> "HOLD".equals(rule.action())) ? "HELD" : "POSTED";
        Long transferId = jdbc.queryForObject("""
                INSERT INTO transfers(transfer_type, status, source_account_id, target_account_id, amount_paise, note)
                VALUES (?, ?, ?, ?, ?, ?) RETURNING transfer_id
                """, Long.class, type, status, debitAccount, creditAccount, request.amountPaise(), request.note());
        for (RiskRule rule : firedRules) {
            jdbc.update("INSERT INTO risk_flags(transfer_id, risk_rule_id) VALUES (?, ?)", transferId, rule.id());
        }
        if (status.equals("HELD")) {
            return jsonResponse(202, Map.of("transferId", transferId, "status", status, "amountPaise", request.amountPaise()));
        }
        postLedger(transferId, debitAccount, creditAccount, request.amountPaise());
        return jsonResponse(201, Map.of("transferId", transferId, "status", status, "amountPaise", request.amountPaise()));
    }

    private void postLedger(long transferId, long debitAccount, long creditAccount, long amount) {
        jdbc.update("INSERT INTO ledger_entries(transfer_id, account_id, direction, amount_paise) VALUES (?, ?, 'DEBIT', ?)", transferId, debitAccount, amount);
        jdbc.update("INSERT INTO ledger_entries(transfer_id, account_id, direction, amount_paise) VALUES (?, ?, 'CREDIT', ?)", transferId, creditAccount, amount);
        jdbc.update("UPDATE accounts SET balance_paise = balance_paise - ? WHERE account_id = ?", amount, debitAccount);
        jdbc.update("UPDATE accounts SET balance_paise = balance_paise + ? WHERE account_id = ?", amount, creditAccount);
    }

    private Map<Long, Long> lockAccounts(long firstId, long secondId) {
        List<Map<String, Object>> locked = jdbc.query("""
                SELECT account_id, balance_paise FROM accounts
                WHERE account_id IN (?, ?) ORDER BY account_id FOR UPDATE
                """, (rs, rowNum) -> Map.of("id", rs.getLong("account_id"), "balance", rs.getLong("balance_paise")), firstId, secondId);
        return locked.stream().collect(java.util.stream.Collectors.toMap(row -> (Long) row.get("id"), row -> (Long) row.get("balance")));
    }

    private long systemAccount(String key) {
        Long accountId = jdbc.queryForObject("SELECT account_id FROM accounts WHERE system_key = ?", Long.class, key);
        return accountId;
    }

    private boolean isUserWallet(long accountId) {
        Boolean result = jdbc.queryForObject("SELECT owner_user_id IS NOT NULL FROM accounts WHERE account_id = ?", Boolean.class, accountId);
        return Boolean.TRUE.equals(result);
    }

    private void verifyWalletOwner(long userId, long accountId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM accounts WHERE account_id = ? AND owner_user_id = ? AND account_type = 'WALLET'
                """, Integer.class, accountId, userId);
        if (count == null || count != 1) {
            throw new WalletException("WALLET_NOT_FOUND", "Wallet was not found for this user", HttpStatus.NOT_FOUND);
        }
    }

    private SavedResponse idempotent(long userId, UUID key, String operation, Object body, Supplier<SavedResponse> action) {
        return idempotent(userId, key, operation, body, TransactionDefinition.ISOLATION_READ_COMMITTED, action);
    }

    private SavedResponse idempotent(long userId, UUID key, String operation, Object body, int isolationLevel, Supplier<SavedResponse> action) {
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        transactionTemplate.setIsolationLevel(isolationLevel);
        return transactionTemplate.execute(transaction -> {
            String hash = sha256(operation + ":" + writeJson(body));
            int inserted = jdbc.update("""
                    INSERT INTO idempotency_keys(user_id, key, request_hash)
                    VALUES (?, ?, ?) ON CONFLICT (user_id, key) DO NOTHING
                    """, userId, key, hash);
            if (inserted == 0) {
                ExistingKey existing = jdbc.queryForObject("""
                        SELECT request_hash, response_code, response_body FROM idempotency_keys WHERE user_id = ? AND key = ?
                        """, (rs, rowNum) -> new ExistingKey(rs.getString(1), (Integer) rs.getObject(2), rs.getString(3)), userId, key);
                if (!existing.hash().equals(hash)) {
                    throw new WalletException("IDEMPOTENCY_KEY_REUSED", "This key was already used with a different request", HttpStatus.UNPROCESSABLE_ENTITY);
                }
                if (existing.statusCode() == null || existing.responseBody() == null) {
                    throw new WalletException("REQUEST_IN_PROGRESS", "Request with this key is still in progress", HttpStatus.CONFLICT);
                }
                return new SavedResponse(existing.statusCode(), existing.responseBody());
            }
            SavedResponse result = action.get();
            jdbc.update("UPDATE idempotency_keys SET response_code = ?, response_body = ? WHERE user_id = ? AND key = ?",
                    result.statusCode(), result.body(), userId, key);
            return result;
        });
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private SavedResponse jsonResponse(int status, Object value) {
        return new SavedResponse(status, writeJson(value));
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private Map<String, Object> entryMap(ResultSet rs) throws SQLException {
        return Map.of("entryId", rs.getLong("entry_id"), "transferId", rs.getLong("transfer_id"),
                "type", rs.getString("transfer_type"), "direction", rs.getString("direction"),
                "amountPaise", rs.getLong("amount_paise"), "createdAt", rs.getTimestamp("created_at").toInstant(),
                "note", rs.getString("note") == null ? "" : rs.getString("note"));
    }

    public Map<String, Object> runReconciliation() {
        TransactionTemplate repeatableRead = new TransactionTemplate(transactionManager);
        repeatableRead.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return repeatableRead.execute(status -> runReconciliationCore());
        }

        public SavedResponse requestReconciliation(UUID key) {
        Long adminId = jdbc.queryForObject("SELECT user_id FROM users WHERE email = 'wallet-ops@example.test'", Long.class);
        return idempotent(adminId, key, "RUN_RECONCILIATION", Map.of("job", "nightly-reconciliation"),
            TransactionDefinition.ISOLATION_REPEATABLE_READ, () -> jsonResponse(200, runReconciliationCore()));
        }

        private Map<String, Object> runReconciliationCore() {
            Integer locked = jdbc.query("SELECT 1 FROM job_locks WHERE job_name = 'nightly-reconciliation' FOR UPDATE NOWAIT",
                    rs -> rs.next() ? 1 : null);
            if (locked == null) {
                throw new WalletException("RECONCILIATION_LOCK_MISSING", "Reconciliation lock row is missing", HttpStatus.INTERNAL_SERVER_ERROR);
            }
            Long runId = jdbc.queryForObject("INSERT INTO reconciliation_runs(started_at, status) VALUES (now(), 'RUNNING') RETURNING reconciliation_run_id", Long.class);
            int issues = 0;
            issues += recordIssues(runId, "transfer_balance", """
                    SELECT transfer_id, '0', SUM(CASE WHEN direction = 'DEBIT' THEN amount_paise ELSE -amount_paise END)::text
                    FROM ledger_entries GROUP BY transfer_id HAVING SUM(CASE WHEN direction = 'DEBIT' THEN amount_paise ELSE -amount_paise END) <> 0
                    """);
            issues += recordIssues(runId, "stored_balance", """
                    SELECT a.account_id, COALESCE(SUM(CASE WHEN l.direction = 'CREDIT' THEN l.amount_paise ELSE -l.amount_paise END), 0)::text, a.balance_paise::text
                    FROM accounts a LEFT JOIN ledger_entries l ON l.account_id = a.account_id
                    GROUP BY a.account_id, a.balance_paise
                    HAVING a.balance_paise <> COALESCE(SUM(CASE WHEN l.direction = 'CREDIT' THEN l.amount_paise ELSE -l.amount_paise END), 0)
                    """);
            issues += recordIssues(runId, "overdraft", """
                    SELECT a.account_id, '>= 0', a.balance_paise::text FROM accounts a
                    WHERE a.owner_user_id IS NOT NULL AND a.balance_paise < 0
                    """);
            issues += recordIssues(runId, "transfer_without_entries", """
                    SELECT t.transfer_id, 'at least 2', COUNT(l.ledger_entry_id)::text FROM transfers t LEFT JOIN ledger_entries l ON l.transfer_id = t.transfer_id
                    GROUP BY t.transfer_id HAVING COUNT(l.ledger_entry_id) < 2 AND t.status = 'POSTED'
                    """);
            issues += recordIssues(runId, "orphan_ledger", """
                    SELECT l.ledger_entry_id, 'transfer exists', 'missing transfer' FROM ledger_entries l LEFT JOIN transfers t ON t.transfer_id = l.transfer_id
                    WHERE t.transfer_id IS NULL
                    """);
            String finalStatus = issues == 0 ? "CLEAN" : "MISMATCH";
            jdbc.update("UPDATE reconciliation_runs SET ended_at = now(), status = ?, issue_count = ? WHERE reconciliation_run_id = ?", finalStatus, issues, runId);
            return Map.of("runId", runId, "status", finalStatus, "issueCount", issues);
    }

    public SavedResponse reverse(long userId, long originalTransferId, UUID key) {
        return idempotent(userId, key, "REVERSAL:" + originalTransferId, Map.of("transferId", originalTransferId), () -> {
            OriginalTransfer original = jdbc.queryForObject("""
                    SELECT transfer_id, source_account_id, target_account_id, amount_paise, status
                    FROM transfers WHERE transfer_id = ?
                    """, (rs, rowNum) -> new OriginalTransfer(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getString(5)), originalTransferId);
            if (original == null || !"POSTED".equals(original.status())) {
                throw new WalletException("TRANSFER_NOT_REVERSIBLE", "Only a posted transfer can be reversed", HttpStatus.UNPROCESSABLE_ENTITY);
            }
            verifyWalletOwner(userId, original.sourceAccountId());
            Integer existing = jdbc.queryForObject("SELECT COUNT(*) FROM transfers WHERE reverses_transfer_id = ?", Integer.class, originalTransferId);
            if (existing != null && existing > 0) {
                throw new WalletException("ALREADY_REVERSED", "Transfer already has a reversal", HttpStatus.CONFLICT);
            }
            Map<Long, Long> balances = lockAccounts(original.sourceAccountId(), original.targetAccountId());
                if (isUserWallet(original.targetAccountId())
                    && balances.getOrDefault(original.targetAccountId(), 0L) < original.amountPaise()) {
                throw new WalletException("INSUFFICIENT_FUNDS", "Counterparty wallet cannot fund this reversal", HttpStatus.UNPROCESSABLE_ENTITY);
            }
            Long reversalId = jdbc.queryForObject("""
                    INSERT INTO transfers(transfer_type, status, source_account_id, target_account_id, amount_paise, reverses_transfer_id)
                    VALUES ('REVERSAL', 'POSTED', ?, ?, ?, ?) RETURNING transfer_id
                    """, Long.class, original.targetAccountId(), original.sourceAccountId(), original.amountPaise(), originalTransferId);
            postLedger(reversalId, original.targetAccountId(), original.sourceAccountId(), original.amountPaise());
            return jsonResponse(201, Map.of("transferId", reversalId, "reversesTransferId", originalTransferId, "status", "POSTED"));
        });
    }

    public SavedResponse releaseHeld(long actorUserId, long transferId, UUID key) {
        return idempotent(actorUserId, key, "RELEASE:" + transferId, Map.of("transferId", transferId), () -> {
            HeldTransfer transfer = jdbc.queryForObject("""
                    SELECT transfer_id, source_account_id, target_account_id, amount_paise, status
                    FROM transfers WHERE transfer_id = ? FOR UPDATE
                    """, (rs, rowNum) -> new HeldTransfer(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getString(5)), transferId);
            if (transfer == null || !"HELD".equals(transfer.status())) {
                throw new WalletException("TRANSFER_NOT_HELD", "Transfer is not held for review", HttpStatus.CONFLICT);
            }
            Map<Long, Long> balances = lockAccounts(transfer.sourceAccountId(), transfer.targetAccountId());
            if (isUserWallet(transfer.sourceAccountId())
                    && balances.getOrDefault(transfer.sourceAccountId(), 0L) < transfer.amountPaise()) {
                throw new WalletException("INSUFFICIENT_FUNDS", "Wallet balance is too low to release this transfer", HttpStatus.UNPROCESSABLE_ENTITY);
            }
            jdbc.queryForObject("SELECT set_config('wallet.actor', ?, true)", String.class, "admin-user:" + actorUserId);
            jdbc.update("UPDATE transfers SET status = 'POSTED' WHERE transfer_id = ?", transferId);
            postLedger(transferId, transfer.sourceAccountId(), transfer.targetAccountId(), transfer.amountPaise());
            return jsonResponse(200, Map.of("transferId", transferId, "status", "POSTED"));
        });
    }

    public SavedResponse askStatement(long userId, UUID key, WalletController.StatementQuestionRequest request) {
        return idempotent(userId, key, "STATEMENT_QUESTION", request, () -> {
            if (request.question() == null || request.question().isBlank()) {
                throw new WalletException("INVALID_REQUEST", "question is required", HttpStatus.BAD_REQUEST);
            }
            StatementQuestion parsed;
            try {
                parsed = objectMapper.readValue(questionParser.toJson(request.question()), StatementQuestion.class);
            } catch (Exception exception) {
                throw new WalletException("UNSUPPORTED_QUESTION", "I can answer only the four supported statement question types", HttpStatus.UNPROCESSABLE_ENTITY);
            }
            if (parsed.type() < 1 || parsed.type() > 4 || parsed.from() == null || parsed.to() == null
                    || parsed.from().isAfter(parsed.to()) || ((parsed.type() == 1 || parsed.type() == 2)
                    && (parsed.counterparty() == null || parsed.counterparty().isBlank()))) {
                throw new WalletException("UNSUPPORTED_QUESTION", "I can answer only the four supported statement question types", HttpStatus.UNPROCESSABLE_ENTITY);
            }
            java.time.Instant from = parsed.from().atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
            java.time.Instant to = parsed.to().plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
            Map<String, Object> answer = statementAnswer(userId, parsed, from, to);
            return jsonResponse(200, answer);
        });
    }

    private Map<String, Object> statementAnswer(long userId, StatementQuestion question, java.time.Instant from, java.time.Instant to) {
        if (question.type() == 1 || question.type() == 2) {
            String direction = question.type() == 1 ? "DEBIT" : "CREDIT";
            String counterpartySide = question.type() == 1 ? "target_account_id" : "source_account_id";
            String sql = """
                    SELECT COALESCE(SUM(l.amount_paise), 0), COALESCE(array_agg(DISTINCT l.transfer_id), ARRAY[]::bigint[])
                    FROM ledger_entries l JOIN accounts mine ON mine.account_id = l.account_id
                    JOIN transfers t ON t.transfer_id = l.transfer_id
                    JOIN accounts other_account ON other_account.account_id = t.%s
                    JOIN users other_user ON other_user.user_id = other_account.owner_user_id
                    WHERE mine.owner_user_id = ? AND l.direction = ? AND t.transfer_type = 'PEER'
                      AND lower(other_user.name) = lower(?) AND l.created_at >= ? AND l.created_at < ?
                    """.formatted(counterpartySide);
            return jdbc.queryForObject(sql, (rs, rowNum) -> Map.of("type", question.type(), "amountPaise", rs.getLong(1),
                    "transferIds", (Long[]) rs.getArray(2).getArray(), "from", question.from(), "to", question.to()),
                    userId, direction, question.counterparty(), java.sql.Timestamp.from(from), java.sql.Timestamp.from(to));
        }
        if (question.type() == 3) {
            return jdbc.queryForObject("""
                    SELECT COALESCE((SELECT t.amount_paise FROM transfers t JOIN ledger_entries l ON l.transfer_id = t.transfer_id
                        JOIN accounts a ON a.account_id = l.account_id WHERE a.owner_user_id = ? AND t.created_at >= ? AND t.created_at < ?
                        GROUP BY t.transfer_id ORDER BY t.amount_paise DESC, t.transfer_id DESC LIMIT 1), 0),
                       COALESCE((SELECT ARRAY[t.transfer_id] FROM transfers t JOIN ledger_entries l ON l.transfer_id = t.transfer_id
                        JOIN accounts a ON a.account_id = l.account_id WHERE a.owner_user_id = ? AND t.created_at >= ? AND t.created_at < ?
                        GROUP BY t.transfer_id ORDER BY t.amount_paise DESC, t.transfer_id DESC LIMIT 1), ARRAY[]::bigint[])
                    """, (rs, rowNum) -> Map.of("type", 3, "amountPaise", rs.getLong(1), "transferIds", (Long[]) rs.getArray(2).getArray(),
                    "from", question.from(), "to", question.to()), userId, java.sql.Timestamp.from(from), java.sql.Timestamp.from(to),
                    userId, java.sql.Timestamp.from(from), java.sql.Timestamp.from(to));
        }
        return jdbc.queryForObject("""
                SELECT COUNT(DISTINCT t.transfer_id), COALESCE(array_agg(DISTINCT t.transfer_id), ARRAY[]::bigint[])
                FROM transfers t JOIN ledger_entries l ON l.transfer_id = t.transfer_id JOIN accounts a ON a.account_id = l.account_id
                WHERE a.owner_user_id = ? AND t.created_at >= ? AND t.created_at < ?
                """, (rs, rowNum) -> Map.of("type", 4, "count", rs.getLong(1), "transferIds", (Long[]) rs.getArray(2).getArray(),
                "from", question.from(), "to", question.to()), userId, java.sql.Timestamp.from(from), java.sql.Timestamp.from(to));
    }

    private int recordIssues(long runId, String checkName, String sql) {
        List<Object[]> rows = jdbc.query(sql, (rs, rowNum) -> new Object[]{rs.getLong(1), rs.getString(2), rs.getString(3)});
        for (Object[] row : rows) {
            jdbc.update("INSERT INTO reconciliation_issues(reconciliation_run_id, check_name, entity_id, expected_value, actual_value) VALUES (?, ?, ?, ?, ?)",
                    runId, checkName, row[0], row[1], row[2]);
        }
        return rows.size();
    }

    public record SavedResponse(int statusCode, String body) {}
    private record ExistingKey(String hash, Integer statusCode, String responseBody) {}
    private record OriginalTransfer(long transferId, long sourceAccountId, long targetAccountId, long amountPaise, String status) {}
    private record HeldTransfer(long transferId, long sourceAccountId, long targetAccountId, long amountPaise, String status) {}
    private record RiskRule(long id, String name, String action) {}
    public record StatementQuestion(int type, String counterparty, java.time.LocalDate from, java.time.LocalDate to) {}
}