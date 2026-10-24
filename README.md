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

The schema rationale, noun inventory, relationship cardinalities, ER diagram, money model, and indexes are in [docs/schema.md](docs/schema.md). The two-session isolation lab is in [docs/isolation.md](docs/isolation.md).

The service stores balances for fast reads and also recomputes them during nightly reconciliation. Reads are cheap; writes require one locked balance update in the same transaction as the ledger. Read Committed plus deterministic `FOR UPDATE` locks protects wallet spends with less retry overhead than Serializable.

The same `accounts` table represents user wallets and system accounts so every movement uses the same ledger foreign keys and double-entry flow. System accounts have no user owner and can go negative. Current authorization is intentionally a teaching/demo boundary: route `userId` values are not authenticated. Do not expose this service publicly without authentication and per-request authorization.

Large configured risk thresholds create `HELD` transfers without ledger movement. An admin release posts the ledger inside the release transaction. Statement Q&A has a mock parser by default and an OpenAI-compatible parser under the `llm` Spring profile; parsed dates and question types are validated before parameterized SQL scoped to the caller's accounts. Fee policy, LLM explanation generation for risk flags, high-volume seed generation, and the full concurrency/corruption acceptance suite remain follow-up work.

## Verification and Known Gaps

`mvn test` compiles and runs the offline parser test. For database integration, run `docker compose up --build`; verify readiness from container logs or `docker compose ps`. Apply `sql/seed_large.sql` with `docker compose exec -T postgres psql -U wallet -d wallet < sql/seed_large.sql` for 1,000 users, 2,000 wallets, and 100,000 peer transfers. PostgreSQL acceptance tests should cover concurrent spend stampedes, idempotency replay races, exact reconciliation corruption reporting, and held transfer release before treating this as production-ready. A single-user demo seed is at `sql/seed_demo.sql`.