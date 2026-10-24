CREATE OR REPLACE FUNCTION audit_transfer_status() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        INSERT INTO transfer_status_audit(transfer_id, old_status, new_status, changed_by)
        VALUES (NEW.transfer_id, NULL, NEW.status, COALESCE(current_setting('wallet.actor', true), 'system'));
    ELSIF OLD.status IS DISTINCT FROM NEW.status THEN
        INSERT INTO transfer_status_audit(transfer_id, old_status, new_status, changed_by)
        VALUES (NEW.transfer_id, OLD.status, NEW.status, COALESCE(current_setting('wallet.actor', true), 'system'));
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER transfers_status_audit ON transfers;
CREATE TRIGGER transfers_status_audit
    AFTER INSERT OR UPDATE OF status ON transfers
    FOR EACH ROW EXECUTE FUNCTION audit_transfer_status();

CREATE OR REPLACE FUNCTION assert_transfer_balanced() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    checked_transfer_id BIGINT;
    transfer_status TEXT;
    expected_amount BIGINT;
    debit_total BIGINT;
    credit_total BIGINT;
    entry_count BIGINT;
BEGIN
    checked_transfer_id := COALESCE(NEW.transfer_id, OLD.transfer_id);
    SELECT status, amount_paise INTO transfer_status, expected_amount
      FROM transfers WHERE transfer_id = checked_transfer_id;
    IF transfer_status = 'POSTED' THEN
        SELECT COUNT(*),
               COALESCE(SUM(amount_paise) FILTER (WHERE direction = 'DEBIT'), 0),
               COALESCE(SUM(amount_paise) FILTER (WHERE direction = 'CREDIT'), 0)
          INTO entry_count, debit_total, credit_total
          FROM ledger_entries WHERE transfer_id = checked_transfer_id;
        IF entry_count < 2 OR debit_total <> credit_total OR debit_total <> expected_amount THEN
            RAISE EXCEPTION 'posted transfer % must have balanced ledger entries matching its amount',
                checked_transfer_id;
        END IF;
    END IF;
    RETURN NULL;
END;
$$;
