CREATE TABLE transfers (
    id UUID PRIMARY KEY,
    source_account_id UUID NOT NULL,
    destination_account_id UUID NOT NULL,
    amount NUMERIC(19,4) NOT NULL,
    currency_code VARCHAR(3) NOT NULL,
    reference VARCHAR(128),
    journal_transaction_id UUID NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_transfer_source FOREIGN KEY (source_account_id, currency_code)
        REFERENCES accounts (id, currency_code) ON DELETE RESTRICT,
    CONSTRAINT fk_transfer_destination FOREIGN KEY (destination_account_id, currency_code)
        REFERENCES accounts (id, currency_code) ON DELETE RESTRICT,
    CONSTRAINT fk_transfer_journal FOREIGN KEY (journal_transaction_id, currency_code)
        REFERENCES journal_transactions (id, currency_code) ON DELETE RESTRICT,
    CONSTRAINT ck_transfer_distinct_accounts CHECK (source_account_id <> destination_account_id),
    CONSTRAINT ck_transfer_currency CHECK (currency_code IN ('MXN', 'USD', 'JPY')),
    CONSTRAINT ck_transfer_amount CHECK (amount > 0 AND amount <> 'NaN'::NUMERIC
        AND amount = trunc(amount, CASE WHEN currency_code = 'JPY' THEN 0 ELSE 2 END)),
    CONSTRAINT ck_transfer_reference CHECK (reference IS NULL OR length(btrim(reference)) > 0),
    CONSTRAINT ck_transfer_time CHECK (completed_at >= created_at)
);

CREATE INDEX ix_transfers_source_time ON transfers (source_account_id, completed_at, id);
CREATE INDEX ix_transfers_destination_time ON transfers (destination_account_id, completed_at, id);

CREATE TABLE transfer_idempotency_records (
    id UUID PRIMARY KEY,
    idempotency_key VARCHAR(128) NOT NULL UNIQUE,
    request_hash VARCHAR(64) NOT NULL,
    hash_version INTEGER NOT NULL,
    status VARCHAR(20) NOT NULL,
    transfer_id UUID UNIQUE REFERENCES transfers (id) ON DELETE RESTRICT,
    failure_code VARCHAR(40),
    created_at TIMESTAMPTZ NOT NULL,
    resolved_at TIMESTAMPTZ,
    CONSTRAINT ck_transfer_key CHECK (idempotency_key ~ '^[A-Za-z0-9._:-]{1,128}$'),
    CONSTRAINT ck_transfer_hash CHECK (request_hash ~ '^[0-9a-f]{64}$' AND hash_version = 1),
    CONSTRAINT ck_transfer_idempotency_state CHECK (
        (status = 'RESERVED' AND transfer_id IS NULL AND failure_code IS NULL AND resolved_at IS NULL)
        OR (status = 'SUCCEEDED' AND transfer_id IS NOT NULL AND failure_code IS NULL AND resolved_at IS NOT NULL)
        OR (status = 'REJECTED' AND transfer_id IS NULL AND resolved_at IS NOT NULL
            AND failure_code IS NOT NULL AND failure_code IN ('SAME_ACCOUNT', 'ACCOUNT_NOT_FOUND',
                'ACCOUNT_BLOCKED', 'ACCOUNT_CLOSED', 'CURRENCY_MISMATCH',
                'LEDGER_ACCOUNT_NOT_FOUND', 'INSUFFICIENT_FUNDS'))),
    CONSTRAINT ck_transfer_idempotency_time CHECK (resolved_at IS NULL OR resolved_at >= created_at)
);

CREATE FUNCTION transfer_reject_mutation() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Completed transfers cannot be updated, deleted or truncated' USING ERRCODE = '23514';
END;
$$;

CREATE FUNCTION transfer_guard_idempotency_write() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'RESERVED' THEN
            RAISE EXCEPTION 'Idempotency records must be inserted as RESERVED' USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Idempotency records cannot be deleted' USING ERRCODE = '23514';
    END IF;
    IF OLD.status <> 'RESERVED' OR NEW.status NOT IN ('SUCCEEDED', 'REJECTED')
        OR ROW(NEW.id, NEW.idempotency_key, NEW.request_hash, NEW.hash_version, NEW.created_at)
            IS DISTINCT FROM ROW(OLD.id, OLD.idempotency_key, OLD.request_hash, OLD.hash_version, OLD.created_at) THEN
        RAISE EXCEPTION 'Only reservation finalization is permitted; terminal records are immutable'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION transfer_validate_idempotency() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    final_status VARCHAR(20);
BEGIN
    EXECUTE format('SELECT status FROM %I.transfer_idempotency_records WHERE id = $1', TG_TABLE_SCHEMA)
        INTO final_status USING NEW.id;
    IF final_status IS NULL OR final_status = 'RESERVED' THEN
        RAISE EXCEPTION 'A RESERVED idempotency record cannot be committed' USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;

CREATE FUNCTION transfer_validate_journal() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    journal_status VARCHAR(20);
    entry_count BIGINT;
    matching_debits BIGINT;
    matching_credits BIGINT;
    succeeded_records BIGINT;
BEGIN
    EXECUTE format('SELECT status FROM %I.journal_transactions WHERE id = $1', TG_TABLE_SCHEMA)
        INTO journal_status USING NEW.journal_transaction_id;
    EXECUTE format(
        'SELECT count(*),
            count(*) FILTER (WHERE e.side = ''DEBIT'' AND a.account_id = $2
                AND a.category = ''LIABILITY'' AND e.amount = $4
                AND e.currency_code = $5 AND a.currency_code = $5),
            count(*) FILTER (WHERE e.side = ''CREDIT'' AND a.account_id = $3
                AND a.category = ''LIABILITY'' AND e.amount = $4
                AND e.currency_code = $5 AND a.currency_code = $5)
         FROM %I.ledger_entries e JOIN %I.ledger_accounts a ON a.id = e.ledger_account_id
         WHERE e.journal_transaction_id = $1', TG_TABLE_SCHEMA, TG_TABLE_SCHEMA)
        INTO entry_count, matching_debits, matching_credits
        USING NEW.journal_transaction_id, NEW.source_account_id, NEW.destination_account_id,
            NEW.amount, NEW.currency_code;
    IF journal_status IS DISTINCT FROM 'POSTED' OR entry_count <> 2
        OR matching_debits <> 1 OR matching_credits <> 1 THEN
        RAISE EXCEPTION 'Transfer requires exactly its source DEBIT and destination CREDIT in a POSTED journal'
            USING ERRCODE = '23514';
    END IF;
    EXECUTE format('SELECT count(*) FROM %I.transfer_idempotency_records
        WHERE transfer_id = $1 AND status = ''SUCCEEDED''', TG_TABLE_SCHEMA)
        INTO succeeded_records USING NEW.id;
    IF succeeded_records <> 1 THEN
        RAISE EXCEPTION 'Transfer must be linked to exactly one successful idempotency record'
            USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;

CREATE TRIGGER tr_transfer_mutation BEFORE UPDATE OR DELETE ON transfers
    FOR EACH ROW EXECUTE FUNCTION transfer_reject_mutation();
CREATE TRIGGER tr_transfer_truncate BEFORE TRUNCATE ON transfers
    FOR EACH STATEMENT EXECUTE FUNCTION transfer_reject_mutation();
CREATE CONSTRAINT TRIGGER ct_transfer_journal AFTER INSERT ON transfers
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION transfer_validate_journal();
CREATE TRIGGER tr_transfer_idempotency_write BEFORE INSERT OR UPDATE OR DELETE ON transfer_idempotency_records
    FOR EACH ROW EXECUTE FUNCTION transfer_guard_idempotency_write();
CREATE TRIGGER tr_transfer_idempotency_truncate BEFORE TRUNCATE ON transfer_idempotency_records
    FOR EACH STATEMENT EXECUTE FUNCTION transfer_reject_mutation();
CREATE CONSTRAINT TRIGGER ct_transfer_idempotency_terminal AFTER INSERT OR UPDATE ON transfer_idempotency_records
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION transfer_validate_idempotency();
