CREATE TABLE auth_users (
    id UUID PRIMARY KEY,
    login_email VARCHAR(254) NOT NULL UNIQUE,
    password_hash VARCHAR(512) NOT NULL,
    status VARCHAR(20) NOT NULL,
    customer_id UUID UNIQUE REFERENCES customers (id) ON DELETE RESTRICT,
    security_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_auth_email CHECK (login_email = lower(btrim(login_email)) AND length(login_email) > 0),
    CONSTRAINT ck_auth_password_hash CHECK (password_hash LIKE '{argon2id}$argon2id$v=19$%'),
    CONSTRAINT ck_auth_status CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT ck_auth_versions CHECK (security_version >= 0 AND version >= 0),
    CONSTRAINT ck_auth_timestamps CHECK (updated_at >= created_at)
);

CREATE TABLE auth_user_roles (
    auth_user_id UUID NOT NULL REFERENCES auth_users (id) ON DELETE CASCADE,
    role VARCHAR(20) NOT NULL,
    PRIMARY KEY (auth_user_id, role),
    CONSTRAINT ck_auth_role CHECK (role IN ('USER', 'ADMIN'))
);
