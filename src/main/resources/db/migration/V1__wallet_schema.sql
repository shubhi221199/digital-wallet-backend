CREATE TABLE users (
    user_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name TEXT NOT NULL,
    email TEXT NOT NULL UNIQUE,
    phone TEXT NOT NULL UNIQUE,
    kyc_status TEXT NOT NULL CHECK (kyc_status IN ('PENDING', 'VERIFIED', 'REJECTED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE accounts (
    account_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    owner_user_id BIGINT REFERENCES users(user_id),
    account_type TEXT NOT NULL CHECK (account_type IN ('WALLET', 'SYSTEM')),
    system_key TEXT UNIQUE,
    display_name TEXT NOT NULL,
    currency CHAR(3) NOT NULL DEFAULT 'INR' CHECK (currency = 'INR'),
    balance_paise BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (owner_user_id IS NULL OR balance_paise >= 0),
    CHECK ((account_type = 'WALLET' AND owner_user_id IS NOT NULL AND system_key IS NULL)
        OR (account_type = 'SYSTEM' AND owner_user_id IS NULL AND system_key IS NOT NULL))
);
CREATE INDEX accounts_owner_idx ON accounts(owner_user_id, account_id) WHERE owner_user_id IS NOT NULL;

CREATE TABLE transfers (
    transfer_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    transfer_type TEXT NOT NULL CHECK (transfer_type IN ('TOP_UP', 'PEER', 'WITHDRAWAL', 'FEE', 'REVERSAL')),
    status TEXT NOT NULL CHECK (status IN ('POSTED', 'HELD', 'REJECTED')),
    source_account_id BIGINT NOT NULL REFERENCES accounts(account_id),
    target_account_id BIGINT NOT NULL REFERENCES accounts(account_id),
    amount_paise BIGINT NOT NULL CHECK (amount_paise > 0),
    note TEXT,
    reverses_transfer_id BIGINT REFERENCES transfers(transfer_id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (source_account_id <> target_account_id)
);
CREATE INDEX transfers_source_created_idx ON transfers(source_account_id, created_at DESC, transfer_id DESC);
CREATE INDEX transfers_target_created_idx ON transfers(target_account_id, created_at DESC, transfer_id DESC);
CREATE UNIQUE INDEX transfers_one_reversal_idx ON transfers(reverses_transfer_id) WHERE transfer_type = 'REVERSAL';

CREATE TABLE ledger_entries (
    ledger_entry_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    transfer_id BIGINT NOT NULL REFERENCES transfers(transfer_id),
    account_id BIGINT NOT NULL REFERENCES accounts(account_id),
    direction TEXT NOT NULL CHECK (direction IN ('DEBIT', 'CREDIT')),
    amount_paise BIGINT NOT NULL CHECK (amount_paise > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ledger_account_created_idx ON ledger_entries(account_id, created_at DESC, ledger_entry_id DESC);
CREATE INDEX ledger_transfer_idx ON ledger_entries(transfer_id);

CREATE TABLE idempotency_keys (
    idempotency_key_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(user_id),
    key UUID NOT NULL,
    request_hash CHAR(64) NOT NULL,
    response_code INTEGER,
    response_body TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, key)
);
CREATE INDEX idempotency_created_idx ON idempotency_keys(created_at);

CREATE TABLE transfer_status_audit (
    transfer_status_audit_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    transfer_id BIGINT NOT NULL REFERENCES transfers(transfer_id),
    old_status TEXT,
    new_status TEXT NOT NULL,
    changed_by TEXT NOT NULL,
    changed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE risk_rules (
    risk_rule_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    rule_name TEXT NOT NULL UNIQUE,
    minimum_amount_paise BIGINT NOT NULL CHECK (minimum_amount_paise > 0),
    action TEXT NOT NULL CHECK (action IN ('HOLD', 'FLAG')),
    active BOOLEAN NOT NULL DEFAULT TRUE
);
CREATE TABLE risk_flags (
    risk_flag_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    transfer_id BIGINT NOT NULL REFERENCES transfers(transfer_id),
    risk_rule_id BIGINT NOT NULL REFERENCES risk_rules(risk_rule_id),
    explanation TEXT,
    label TEXT CHECK (label IN ('likely_ok', 'review', 'likely_fraud')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (transfer_id, risk_rule_id)
);

CREATE TABLE reconciliation_runs (
    reconciliation_run_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    started_at TIMESTAMPTZ NOT NULL,
    ended_at TIMESTAMPTZ,
    status TEXT NOT NULL CHECK (status IN ('RUNNING', 'CLEAN', 'MISMATCH')),
    issue_count INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE reconciliation_issues (
    reconciliation_issue_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    reconciliation_run_id BIGINT NOT NULL REFERENCES reconciliation_runs(reconciliation_run_id),
    check_name TEXT NOT NULL,
    entity_id BIGINT,
    expected_value TEXT,
    actual_value TEXT
);
CREATE TABLE job_locks (
    job_name TEXT PRIMARY KEY
);
INSERT INTO job_locks(job_name) VALUES ('nightly-reconciliation');

INSERT INTO accounts(account_type, system_key, display_name) VALUES
    ('SYSTEM', 'CASH_IN', 'Cash-in'),
    ('SYSTEM', 'CASH_OUT', 'Cash-out'),
    ('SYSTEM', 'FEE_REVENUE', 'Fee revenue');
INSERT INTO users(name, email, phone, kyc_status)
VALUES ('Wallet Operations', 'wallet-ops@example.test', '+910000000000', 'VERIFIED');
INSERT INTO risk_rules(rule_name, minimum_amount_paise, action) VALUES
    ('large_transfer', 10000000, 'HOLD');

CREATE FUNCTION reject_ledger_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'ledger entries are immutable';
END;
$$;
CREATE TRIGGER ledger_entries_immutable
    BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION reject_ledger_mutation();

CREATE FUNCTION audit_transfer_status() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status IS DISTINCT FROM NEW.status THEN
        INSERT INTO transfer_status_audit(transfer_id, old_status, new_status, changed_by)
        VALUES (NEW.transfer_id, OLD.status, NEW.status, COALESCE(current_setting('wallet.actor', true), 'system'));
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER transfers_status_audit
    AFTER UPDATE OF status ON transfers
    FOR EACH ROW EXECUTE FUNCTION audit_transfer_status();

CREATE FUNCTION assert_transfer_balanced() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    checked_transfer_id BIGINT;
    transfer_status TEXT;
    debit_total BIGINT;
    credit_total BIGINT;
    entry_count BIGINT;
BEGIN
    checked_transfer_id := COALESCE(NEW.transfer_id, OLD.transfer_id);
    SELECT status INTO transfer_status FROM transfers WHERE transfer_id = checked_transfer_id;
    IF transfer_status = 'POSTED' THEN
        SELECT COUNT(*),
               COALESCE(SUM(amount_paise) FILTER (WHERE direction = 'DEBIT'), 0),
               COALESCE(SUM(amount_paise) FILTER (WHERE direction = 'CREDIT'), 0)
          INTO entry_count, debit_total, credit_total
          FROM ledger_entries WHERE transfer_id = checked_transfer_id;
        IF entry_count < 2 OR debit_total <> credit_total OR debit_total = 0 THEN
            RAISE EXCEPTION 'posted transfer % must have balanced ledger entries', checked_transfer_id;
        END IF;
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER ledger_transfer_balanced
    AFTER INSERT OR UPDATE ON ledger_entries DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION assert_transfer_balanced();
CREATE CONSTRAINT TRIGGER posted_transfer_has_ledger
    AFTER INSERT OR UPDATE OF status ON transfers DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION assert_transfer_balanced();