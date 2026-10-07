CREATE TABLE customers (
    id UUID PRIMARY KEY,
    first_name VARCHAR(100) NOT NULL,
    last_name VARCHAR(150) NOT NULL,
    email VARCHAR(254),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_customers_first_name_not_blank CHECK (first_name !~ '^[[:space:]]*$'),
    CONSTRAINT ck_customers_last_name_not_blank CHECK (last_name !~ '^[[:space:]]*$'),
    CONSTRAINT ck_customers_email_not_blank CHECK (email IS NULL OR email !~ '^[[:space:]]*$'),
    CONSTRAINT ck_customers_timestamps CHECK (updated_at >= created_at),
    CONSTRAINT ck_customers_version CHECK (version >= 0)
);
