# Isolation Lab

Run PostgreSQL with `docker compose up -d postgres`, then connect two terminals with `docker compose exec postgres psql -U wallet -d wallet`. The following scratch table is independent of the application schema:

```sql
CREATE TABLE isolation_demo(id integer PRIMARY KEY, balance integer NOT NULL);
INSERT INTO isolation_demo VALUES (1, 100);
```

Run each case after `UPDATE isolation_demo SET balance = 100 WHERE id = 1;`.

## Read Committed

Session A:

```sql
BEGIN ISOLATION LEVEL READ COMMITTED;
SELECT balance FROM isolation_demo WHERE id = 1;
-- 100
-- Wait for session B to commit.
SELECT balance FROM isolation_demo WHERE id = 1;
-- 90
COMMIT;
```

Session B, after A's first read:

```sql
UPDATE isolation_demo SET balance = 90 WHERE id = 1;
COMMIT;
```

Each statement sees a fresh committed snapshot under Read Committed.

## Repeatable Read

Reset the row. Start A with `BEGIN ISOLATION LEVEL REPEATABLE READ;`, read the balance, then let B update it to 90 and commit. A's second `SELECT` still returns 100. This also holds when the read joins another table: the transaction snapshot applies to the whole query, not only one table.

## Serializable

Reset to 100. In both sessions run `BEGIN ISOLATION LEVEL SERIALIZABLE; SELECT balance FROM isolation_demo WHERE id = 1;`. Have A subtract 80 and B subtract 30 based on the read value, then commit A followed by B. PostgreSQL aborts one transaction with SQLSTATE `40001` (`could not serialize access due to read/write dependencies among transactions`). Retry the full transaction after that error.

## Wallet Write Isolation

The API uses Read Committed with explicit ordered account row locks. It reads the spendable balance only after acquiring the source row lock and commits the transfer, entries, and stored balance together. A user wallet's nonnegative database check is a second line of defense. System account balances intentionally have no nonnegative check.