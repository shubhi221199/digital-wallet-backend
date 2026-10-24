-- Run after seed_large.sql and seed_performance.sql with psql.
-- Each EXPLAIN ANALYZE executes the query; do not run against production.

EXPLAIN (ANALYZE, BUFFERS)
SELECT risk_rule_id, rule_name, action
FROM risk_rules
WHERE active AND minimum_amount_paise <= 10000000
ORDER BY minimum_amount_paise DESC, risk_rule_id;

EXPLAIN (ANALYZE, BUFFERS)
SELECT COALESCE(SUM(amount_paise) FILTER (WHERE direction = 'DEBIT'), 0),
       COALESCE(SUM(amount_paise) FILTER (WHERE direction = 'CREDIT'), 0)
FROM ledger_entries;

EXPLAIN (ANALYZE, BUFFERS)
SELECT t.transfer_id
FROM transfers t
LEFT JOIN ledger_entries l ON l.transfer_id = t.transfer_id
WHERE t.status = 'POSTED'
GROUP BY t.transfer_id, t.amount_paise
HAVING COUNT(l.ledger_entry_id) < 2
    OR COALESCE(SUM(l.amount_paise) FILTER (WHERE l.direction = 'DEBIT'), 0)
       <> COALESCE(SUM(l.amount_paise) FILTER (WHERE l.direction = 'CREDIT'), 0)
    OR COALESCE(SUM(l.amount_paise) FILTER (WHERE l.direction = 'DEBIT'), 0) <> t.amount_paise;

EXPLAIN (ANALYZE, BUFFERS)
SELECT a.account_id
FROM accounts a
LEFT JOIN ledger_entries l ON l.account_id = a.account_id
GROUP BY a.account_id, a.balance_paise
HAVING a.balance_paise <> COALESCE(SUM(
    CASE WHEN l.direction = 'CREDIT' THEN l.amount_paise ELSE -l.amount_paise END
), 0);

EXPLAIN (ANALYZE, BUFFERS)
SELECT a.account_id
FROM accounts a
CROSS JOIN LATERAL (
    SELECT COALESCE(SUM(
        CASE WHEN l.direction = 'CREDIT' THEN l.amount_paise ELSE -l.amount_paise END
    ), 0) AS computed_balance
    FROM ledger_entries l
    WHERE l.account_id = a.account_id
) computed
WHERE a.owner_user_id IS NOT NULL AND computed.computed_balance < 0;

EXPLAIN (ANALYZE, BUFFERS)
SELECT t.transfer_id
FROM transfers t
LEFT JOIN ledger_entries l ON l.transfer_id = t.transfer_id
WHERE t.status = 'POSTED'
GROUP BY t.transfer_id
HAVING COUNT(l.ledger_entry_id) < 2;

EXPLAIN (ANALYZE, BUFFERS)
SELECT l.ledger_entry_id
FROM ledger_entries l
LEFT JOIN transfers t ON t.transfer_id = l.transfer_id
WHERE t.transfer_id IS NULL;

EXPLAIN (ANALYZE, BUFFERS)
SELECT l.ledger_entry_id, l.transfer_id, t.transfer_type, l.direction,
       l.amount_paise, l.created_at, t.note
FROM ledger_entries l
JOIN transfers t ON t.transfer_id = l.transfer_id
WHERE l.account_id = (
    SELECT account_id FROM accounts
    WHERE owner_user_id IS NOT NULL
    ORDER BY account_id LIMIT 1
)
ORDER BY l.created_at DESC, l.ledger_entry_id DESC
LIMIT 50 OFFSET 100000;
