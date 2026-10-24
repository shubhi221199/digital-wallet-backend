# Digital Wallet Backend

Java 21, Spring Boot, PostgreSQL 16, and explicit JDBC SQL for the money path. Amounts are integer paise in `BIGINT` columns. The API and database start together with:

```powershell
docker compose up --build
```

The API listens on `http://localhost:8080`; Flyway applies migrations at startup. For local Maven builds use `mvn test` from this folder. User records are provisioned outside the wallet service; the SQL seed inserts a demo user and wallet.

## Implemented API

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/users/{userId}/wallets` | Open a wallet |
| `GET` | `/users/{userId}/wallets/{accountId}/balance` | Read paise balance |
| `GET` | `/users/{userId}/wallets/{accountId}/statement?limit=50&offset=0` | Paginated ledger statement |
| `POST` | `/users/{userId}/wallets/{accountId}/top-ups` | Top up from cash-in system account |
| `POST` | `/users/{userId}/wallets/{accountId}/withdrawals` | Withdraw to cash-out system account |
| `POST` | `/users/{userId}/wallets/{accountId}/transfers` | Peer transfer |
| `POST` | `/users/{userId}/transfers/{transferId}/reversals` | Reverse a posted outgoing transfer |
| `POST` | `/admin/users/{userId}/transfers/{transferId}/release` | Release a held transfer |
| `POST` | `/users/{userId}/statement/ask` | Ask a supported statement question |
| `POST` | `/admin/reconciliation/run` | Run reconciliation synchronously |

Every `POST` requires `Idempotency-Key: <UUID>`. Replays with the same operation and body return the saved status and JSON bytes; a different body or operation with the same user/key returns 422. Errors use `{ "code", "message", "request_id" }`.

Example peer transfer body:

```json
{"targetWalletId": 2, "amountPaise": 5000, "note": "Dinner"}
```

## Design Decisions

The schema rationale, noun inventory, relationship cardinalities, ER diagram, money model, and indexes are in [docs/schema.md](docs/schema.md). The two-session isolation lab is in [docs/isolation.md](docs/isolation.md). Ten statement-Q&A examples are in [docs/statement-questions.md](docs/statement-questions.md).

The service stores balances for fast reads and also recomputes them during nightly reconciliation. Reads are cheap; writes require one locked balance update in the same transaction as the ledger. Read Committed plus deterministic `FOR UPDATE` locks protects wallet spends with less retry overhead than Serializable.

The same `accounts` table represents user wallets and system accounts so every movement uses the same ledger foreign keys and double-entry flow. System accounts have no user owner and can go negative. Current authorization is intentionally a teaching/demo boundary: route `userId` values are not authenticated. Do not expose this service publicly without authentication and per-request authorization.

Risk rules are evaluated inside the transfer transaction. Every matching rule is recorded, `HOLD` rules keep money from moving until release, and `FLAG` rules allow the transfer with a review explanation. The fee policy is intentionally disabled: the fee-revenue account and `FEE` transfer type are modeled, but the application does not charge fees until a pricing policy is agreed.

Statement Q&A has a network-free mock parser by default and an OpenAI-compatible parser under the `llm` Spring profile; parsed dates and question types are validated before parameterized SQL scoped to the caller's accounts. Risk-flag explanations currently use a transparent rule-based review explanation. Sending a user's recent wallet activity to an external LLM is deliberately not enabled; connect a privacy-approved, self-hosted model before enabling LLM-generated explanations.

## Verification and Known Gaps

`mvn test` runs the offline parser test and PostgreSQL acceptance tests when Docker is available; the container tests are skipped when Docker is unavailable. They cover same-key retries and races, a 100-request spend stampede, flag-only rules, held-transfer release, caller-scoped statement answers, and exact reconciliation of representative corruption. CI runs `mvn --batch-mode verify` with Docker available.

Start the API and PostgreSQL with `docker compose up --build`. Apply `sql/seed_large.sql` with `docker compose exec -T postgres psql -U wallet -d wallet < sql/seed_large.sql` for the required 1,000 seed users, 2,000 wallets, and 100,000 peer transfers. For a one-million-ledger-row query-plan exercise, run `sql/seed_performance.sql` after the large seed, then run `sql/performance_explain.sql` with psql. The global reconciliation, per-transfer checks, and stored-balance reconciliation aggregate across the ledger and are intentionally nightly full-scan candidates; the account/created-time statement path uses `ledger_account_created_idx`, while transfer reconciliation joins use `ledger_transfer_idx`. Large `OFFSET` pagination must still walk and discard earlier rows, so page 100,001 is slower than a cursor query on `(created_at, ledger_entry_id)`.

The measured `EXPLAIN ANALYZE` output must be captured from the target PostgreSQL environment; it is not included here because Docker was unavailable during this implementation. The assignment's five-minute demo video remains a submission artifact to record and attach separately. A single-user demo seed is at `sql/seed_demo.sql`.