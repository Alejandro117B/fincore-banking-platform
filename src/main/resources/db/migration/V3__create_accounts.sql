CREATE TABLE accounts (
    id UUID PRIMARY KEY,
    customer_id UUID NOT NULL,
    type VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    currency_code VARCHAR(3) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    closed_at TIMESTAMP WITH TIME ZONE,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_accounts_customer FOREIGN KEY (customer_id) REFERENCES customers(id) ON DELETE RESTRICT,
    CONSTRAINT ck_accounts_type CHECK (type IN ('CHECKING', 'SAVINGS')),
    CONSTRAINT ck_accounts_status CHECK (status IN ('ACTIVE', 'BLOCKED', 'CLOSED')),
    CONSTRAINT ck_accounts_currency_code CHECK (currency_code ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_accounts_timestamps CHECK (updated_at >= created_at),
    CONSTRAINT ck_accounts_closed_at CHECK (
        (status = 'CLOSED' AND closed_at IS NOT NULL AND closed_at >= created_at AND closed_at <= updated_at)
        OR (status <> 'CLOSED' AND closed_at IS NULL)
    ),
    CONSTRAINT ck_accounts_version CHECK (version >= 0)
);

CREATE INDEX ix_accounts_customer_id ON accounts(customer_id);
