CREATE TABLE ledger_entries (
    id UUID PRIMARY KEY,
    journal_transaction_id UUID NOT NULL,
    ledger_account_id UUID NOT NULL,
    line_number INTEGER NOT NULL,
    side VARCHAR(10) NOT NULL,
    amount NUMERIC(19,4) NOT NULL,
    currency_code VARCHAR(3) NOT NULL,
    CONSTRAINT uq_ledger_entries_journal_line UNIQUE (journal_transaction_id, line_number),
    CONSTRAINT fk_ledger_entries_journal FOREIGN KEY (journal_transaction_id, currency_code)
        REFERENCES journal_transactions(id, currency_code) ON DELETE RESTRICT,
    CONSTRAINT fk_ledger_entries_account FOREIGN KEY (ledger_account_id, currency_code)
        REFERENCES ledger_accounts(id, currency_code) ON DELETE RESTRICT,
    CONSTRAINT ck_ledger_entries_line_number CHECK (line_number > 0),
    CONSTRAINT ck_ledger_entries_side CHECK (side IN ('DEBIT', 'CREDIT')),
    CONSTRAINT ck_ledger_entries_amount CHECK (amount > 0 AND amount <> 'NaN'::numeric),
    CONSTRAINT ck_ledger_entries_currency CHECK (currency_code ~ '^[A-Z]{3}$')
);

CREATE INDEX ix_ledger_entries_account ON ledger_entries(ledger_account_id);

-- Functions qualify related tables using the triggering schema, not the caller's search_path.
CREATE FUNCTION ledger_guard_entry_insert() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    journal_status VARCHAR(20);
BEGIN
    EXECUTE format('SELECT status FROM %I.journal_transactions WHERE id = $1 FOR UPDATE', TG_TABLE_SCHEMA)
        INTO journal_status USING NEW.journal_transaction_id;
    IF journal_status IS NULL THEN
        RAISE EXCEPTION 'Journal does not exist' USING ERRCODE = '23503';
    END IF;
    IF journal_status <> 'DRAFT' THEN
        RAISE EXCEPTION 'Entries can only be inserted into a DRAFT journal' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION ledger_reject_entry_mutation() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Ledger entries cannot be updated or deleted; roll back or create a compensating journal'
        USING ERRCODE = '23514';
END;
$$;

CREATE FUNCTION ledger_guard_journal_write() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'DRAFT' OR NEW.posted_at IS NOT NULL THEN
            RAISE EXCEPTION 'A journal must be inserted as DRAFT' USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Journals cannot be deleted' USING ERRCODE = '23514';
    END IF;
    IF OLD.status <> 'DRAFT' OR NEW.status <> 'POSTED'
        OR ROW(NEW.id, NEW.currency_code, NEW.reference, NEW.created_at)
            IS DISTINCT FROM ROW(OLD.id, OLD.currency_code, OLD.reference, OLD.created_at) THEN
        RAISE EXCEPTION 'Only the DRAFT to POSTED transition is allowed; journal metadata is immutable'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION ledger_validate_journal() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    journal_status VARCHAR(20);
    entry_count BIGINT;
    account_count BIGINT;
    debit_count BIGINT;
    credit_count BIGINT;
    debit_total NUMERIC;
    credit_total NUMERIC;
BEGIN
    -- Read final persisted state: this trigger may have been queued by an INSERT of DRAFT.
    EXECUTE format('SELECT status FROM %I.journal_transactions WHERE id = $1', TG_TABLE_SCHEMA)
        INTO journal_status USING NEW.id;
    IF journal_status IS DISTINCT FROM 'POSTED' THEN
        RAISE EXCEPTION 'A DRAFT journal cannot be committed' USING ERRCODE = '23514';
    END IF;
    EXECUTE format(
        'SELECT count(*), count(DISTINCT ledger_account_id),
         count(*) FILTER (WHERE side = ''DEBIT''), count(*) FILTER (WHERE side = ''CREDIT''),
         COALESCE(sum(amount) FILTER (WHERE side = ''DEBIT''), 0),
         COALESCE(sum(amount) FILTER (WHERE side = ''CREDIT''), 0)
         FROM %I.ledger_entries WHERE journal_transaction_id = $1', TG_TABLE_SCHEMA)
        INTO entry_count, account_count, debit_count, credit_count, debit_total, credit_total USING NEW.id;
    IF entry_count < 2 OR account_count < 2 OR debit_count = 0 OR credit_count = 0
        OR debit_total <> credit_total THEN
        RAISE EXCEPTION 'Journal must balance DEBIT and CREDIT across at least two ledger accounts'
            USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;

CREATE FUNCTION ledger_reject_account_update() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Ledger account identity, currency and category are immutable' USING ERRCODE = '23514';
END;
$$;

CREATE FUNCTION ledger_reject_truncate() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Ledger tables cannot be truncated' USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER tr_ledger_entry_insert BEFORE INSERT ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION ledger_guard_entry_insert();
CREATE TRIGGER tr_ledger_entry_mutation BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION ledger_reject_entry_mutation();
CREATE TRIGGER tr_journal_write BEFORE INSERT OR UPDATE OR DELETE ON journal_transactions
    FOR EACH ROW EXECUTE FUNCTION ledger_guard_journal_write();
CREATE CONSTRAINT TRIGGER ct_journal_complete AFTER INSERT OR UPDATE ON journal_transactions
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION ledger_validate_journal();
CREATE TRIGGER tr_ledger_account_update BEFORE UPDATE ON ledger_accounts
    FOR EACH ROW EXECUTE FUNCTION ledger_reject_account_update();
CREATE TRIGGER tr_ledger_entries_truncate BEFORE TRUNCATE ON ledger_entries
    FOR EACH STATEMENT EXECUTE FUNCTION ledger_reject_truncate();
CREATE TRIGGER tr_journals_truncate BEFORE TRUNCATE ON journal_transactions
    FOR EACH STATEMENT EXECUTE FUNCTION ledger_reject_truncate();
CREATE TRIGGER tr_ledger_accounts_truncate BEFORE TRUNCATE ON ledger_accounts
    FOR EACH STATEMENT EXECUTE FUNCTION ledger_reject_truncate();
