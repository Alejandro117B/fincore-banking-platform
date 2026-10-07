ALTER TABLE accounts ADD CONSTRAINT uq_accounts_id_currency UNIQUE (id, currency_code);

CREATE TABLE ledger_accounts (
    id UUID PRIMARY KEY,
    account_id UUID UNIQUE,
    system_code VARCHAR(50),
    category VARCHAR(20) NOT NULL,
    currency_code VARCHAR(3) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_ledger_accounts_id_currency UNIQUE (id, currency_code),
    CONSTRAINT uq_ledger_accounts_system_currency UNIQUE (system_code, currency_code),
    CONSTRAINT fk_ledger_accounts_account FOREIGN KEY (account_id, currency_code)
        REFERENCES accounts(id, currency_code) ON DELETE RESTRICT,
    CONSTRAINT ck_ledger_accounts_identity CHECK ((account_id IS NOT NULL) <> (system_code IS NOT NULL)),
    CONSTRAINT ck_ledger_accounts_category CHECK (category IN ('ASSET', 'LIABILITY')),
    CONSTRAINT ck_ledger_accounts_customer_liability CHECK (account_id IS NULL OR category = 'LIABILITY'),
    CONSTRAINT ck_ledger_accounts_system_code CHECK (system_code IS NULL OR system_code ~ '^[A-Z][A-Z0-9_]{0,49}$'),
    CONSTRAINT ck_ledger_accounts_currency CHECK (currency_code ~ '^[A-Z]{3}$')
);
