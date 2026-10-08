-- Development metadata only; opening funds remain exclusively in the ledger.
CREATE TABLE IF NOT EXISTS demo_seed_runs (
    scenario_key VARCHAR(64) PRIMARY KEY,
    definition_hash VARCHAR(64) NOT NULL,
    alejandro_customer_id UUID REFERENCES customers(id) ON DELETE RESTRICT,
    fernando_customer_id UUID REFERENCES customers(id) ON DELETE RESTRICT,
    alejandro_account_id UUID REFERENCES accounts(id) ON DELETE RESTRICT,
    fernando_account_id UUID REFERENCES accounts(id) ON DELETE RESTRICT,
    funding_journal_id UUID REFERENCES journal_transactions(id) ON DELETE RESTRICT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_demo_seed_hash CHECK (definition_hash ~ '^[0-9a-f]{64}$'),
    -- IDs are filled before commit, in the same transaction as the reservation.
    CONSTRAINT ck_demo_seed_ids CHECK (
        (alejandro_customer_id IS NULL AND fernando_customer_id IS NULL
         AND alejandro_account_id IS NULL AND fernando_account_id IS NULL
         AND funding_journal_id IS NULL)
        OR
        (alejandro_customer_id IS NOT NULL AND fernando_customer_id IS NOT NULL
         AND alejandro_account_id IS NOT NULL AND fernando_account_id IS NOT NULL
         AND funding_journal_id IS NOT NULL)
    )
);
