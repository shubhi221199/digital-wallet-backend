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

Reset the row. Start A:

```sql
BEGIN ISOLATION LEVEL REPEATABLE READ;
SELECT balance FROM isolation_demo WHERE id = 1;
-- 100
```

In session B, update the row to 90 and commit. In session A:

```sql
SELECT balance FROM isolation_demo WHERE id = 1;
-- 100
COMMIT;
```

The same snapshot applies to joins. Create a second row and run the joined query in a new Repeatable Read transaction:

```sql
CREATE TABLE isolation_note(id integer PRIMARY KEY, label text NOT NULL);
INSERT INTO isolation_note VALUES (1, 'initial');

BEGIN ISOLATION LEVEL REPEATABLE READ;
SELECT d.balance, n.label
FROM isolation_demo d JOIN isolation_note n USING (id)
WHERE d.id = 1;
-- 90 | initial
-- Have session B update both rows and commit.
SELECT d.balance, n.label
FROM isolation_demo d JOIN isolation_note n USING (id)
WHERE d.id = 1;
-- 90 | initial
COMMIT;
```

Session B's update during A:

```sql
BEGIN;
UPDATE isolation_demo SET balance = 80 WHERE id = 1;
UPDATE isolation_note SET label = 'changed' WHERE id = 1;
COMMIT;
```

Both joined values remain from A's original snapshot.

## Serializable

Reset to 100. Both sessions begin Serializable transactions and read before either writes:

```sql
BEGIN ISOLATION LEVEL SERIALIZABLE;
SELECT balance FROM isolation_demo WHERE id = 1;
-- 100
```

Session A subtracts 80 and commits:

```sql
UPDATE isolation_demo SET balance = balance - 80 WHERE id = 1;
COMMIT;
-- COMMIT
```

Session B, which also read 100, attempts to subtract 30:

```sql
UPDATE isolation_demo SET balance = balance - 30 WHERE id = 1;
COMMIT;
-- ERROR: could not serialize access due to concurrent update
-- SQLSTATE: 40001
```

Retry the full transaction after that error.

## Break It, Then Lock It

This scratch experiment deliberately has no nonnegative constraint and represents the unsafe read/check/write pattern. Reset the row to 500 before running it.

Session A:

```sql
BEGIN;
SELECT balance FROM isolation_demo WHERE id = 1;
-- 500; application check for a 300-unit spend passes
-- Wait for session B's read.
UPDATE isolation_demo SET balance = balance - 300 WHERE id = 1;
COMMIT;
```

Session B reads 500 before A commits, then performs the same update:

```sql
BEGIN;
SELECT balance FROM isolation_demo WHERE id = 1;
-- 500; application check for a 300-unit spend passes
-- After A commits:
UPDATE isolation_demo SET balance = balance - 300 WHERE id = 1;
COMMIT;
SELECT balance FROM isolation_demo WHERE id = 1;
-- -100: both application checks passed against stale reads
```

With a locked wallet row, each transaction instead locks both account IDs in ascending order and checks the current balance after obtaining the lock:

```sql
BEGIN ISOLATION LEVEL READ COMMITTED;
SELECT account_id, balance_paise
FROM accounts
WHERE account_id IN (1, 2)
ORDER BY account_id
FOR UPDATE;
-- Re-check the source balance from this locked result.
-- If insufficient, ROLLBACK; otherwise insert transfer and ledger entries,
-- update both balances, then COMMIT.
COMMIT;
```

The API uses this ordered lock protocol; no unlocked money-write endpoint is exposed.

## Wallet Write Isolation

The API uses Read Committed with explicit ordered account row locks. It reads the spendable balance only after acquiring the source row lock and commits the transfer, entries, and stored balance together. A user wallet's nonnegative database check is a second line of defense. System account balances intentionally have no nonnegative check.