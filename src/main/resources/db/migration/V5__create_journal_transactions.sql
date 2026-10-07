CREATE TABLE journal_transactions (
    id UUID PRIMARY KEY,
    currency_code VARCHAR(3) NOT NULL,
    status VARCHAR(20) NOT NULL,
    reference VARCHAR(128),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    posted_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uq_journal_transactions_id_currency UNIQUE (id, currency_code),
    CONSTRAINT ck_journal_transactions_currency CHECK (currency_code ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_journal_transactions_status CHECK (status IN ('DRAFT', 'POSTED')),
    CONSTRAINT ck_journal_transactions_reference CHECK (reference IS NULL OR reference !~ '^[[:space:]]*$'),
    CONSTRAINT ck_journal_transactions_posted_at CHECK (
        (status = 'DRAFT' AND posted_at IS NULL)
        OR (status = 'POSTED' AND posted_at IS NOT NULL AND posted_at >= created_at)
    )
);

CREATE INDEX ix_journal_transactions_posted_at ON journal_transactions(posted_at, id);
