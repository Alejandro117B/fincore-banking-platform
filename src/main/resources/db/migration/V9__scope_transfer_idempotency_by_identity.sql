-- NULL is retained only for records created before identity-scoped idempotency.
ALTER TABLE transfer_idempotency_records ADD COLUMN requester_user_id UUID;
ALTER TABLE transfer_idempotency_records
    ADD CONSTRAINT fk_transfer_idempotency_requester FOREIGN KEY (requester_user_id)
        REFERENCES auth_users (id) ON DELETE RESTRICT;
ALTER TABLE transfer_idempotency_records
    DROP CONSTRAINT transfer_idempotency_records_idempotency_key_key;
ALTER TABLE transfer_idempotency_records
    ADD CONSTRAINT uq_transfer_idempotency_requester_key
        UNIQUE NULLS NOT DISTINCT (requester_user_id, idempotency_key);

CREATE OR REPLACE FUNCTION transfer_guard_idempotency_write() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    retired BOOLEAN;
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'RESERVED' OR NEW.requester_user_id IS NULL THEN
            RAISE EXCEPTION 'New reservations require an authenticated requester and RESERVED status'
                USING ERRCODE = '23514';
        END IF;
        EXECUTE format('SELECT EXISTS (SELECT 1 FROM %I.transfer_idempotency_records
            WHERE requester_user_id IS NULL AND idempotency_key = $1)', TG_TABLE_SCHEMA)
            INTO retired USING NEW.idempotency_key;
        IF retired THEN
            -- Dedicated internal SQLSTATE; no historical record data in the diagnostic.
            RAISE EXCEPTION 'Idempotency key is unavailable' USING ERRCODE = 'P7001';
        END IF;
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Idempotency records cannot be deleted' USING ERRCODE = '23514';
    END IF;
    IF OLD.status <> 'RESERVED' OR NEW.status NOT IN ('SUCCEEDED', 'REJECTED')
        OR ROW(NEW.id, NEW.requester_user_id, NEW.idempotency_key, NEW.request_hash, NEW.hash_version, NEW.created_at)
            IS DISTINCT FROM ROW(OLD.id, OLD.requester_user_id, OLD.idempotency_key, OLD.request_hash, OLD.hash_version, OLD.created_at) THEN
        RAISE EXCEPTION 'Only reservation finalization is permitted; terminal records are immutable'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
