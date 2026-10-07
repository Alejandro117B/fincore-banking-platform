package io.github.alejandro117b.fincore.transfer;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class TransferIdempotencyRepository {
    private final JdbcTemplate jdbc;

    TransferIdempotencyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    record Reservation(UUID id, String hash, int hashVersion, TransferIdempotencyStatus status,
                       UUID transferId, TransferErrorCode failureCode) {
    }

    // The unique index waits for a concurrent owner. After its rollback this INSERT can win.
    // After its commit, a separate SELECT at READ_COMMITTED sees the terminal result.
    Optional<UUID> reserve(String key, String hash, Instant at) {
        UUID id = UUID.randomUUID();
        return jdbc.query("""
                INSERT INTO transfer_idempotency_records
                    (id, idempotency_key, request_hash, hash_version, status, created_at)
                VALUES (?, ?, ?, ?, 'RESERVED', ?)
                ON CONFLICT (idempotency_key) DO NOTHING RETURNING id
                """, (rs, row) -> rs.getObject("id", UUID.class),
                id, key, hash, TransferRequestHasher.HASH_VERSION, Timestamp.from(at)).stream().findFirst();
    }

    Reservation find(String key) {
        return jdbc.queryForObject("""
                SELECT id, request_hash, hash_version, status, transfer_id, failure_code
                FROM transfer_idempotency_records WHERE idempotency_key = ?
                """, (rs, row) -> new Reservation(rs.getObject("id", UUID.class), rs.getString("request_hash"),
                rs.getInt("hash_version"), TransferIdempotencyStatus.valueOf(rs.getString("status")),
                rs.getObject("transfer_id", UUID.class), rs.getString("failure_code") == null ? null
                : TransferErrorCode.valueOf(rs.getString("failure_code"))), key);
    }

    void succeed(UUID id, UUID transferId, Instant at) {
        requireOne(jdbc.update("""
                UPDATE transfer_idempotency_records SET status = 'SUCCEEDED', transfer_id = ?, resolved_at = ?
                WHERE id = ? AND status = 'RESERVED'
                """, transferId, Timestamp.from(at), id));
    }

    void reject(UUID id, TransferErrorCode code, Instant at) {
        requireOne(jdbc.update("""
                UPDATE transfer_idempotency_records SET status = 'REJECTED', failure_code = ?, resolved_at = ?
                WHERE id = ? AND status = 'RESERVED'
                """, code.name(), Timestamp.from(at), id));
    }

    private void requireOne(int rows) {
        if (rows != 1) {
            throw new IllegalStateException("Idempotency reservation could not be finalized");
        }
    }
}
