package io.github.alejandro117b.fincore.transfer;

import java.sql.Timestamp;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import io.github.alejandro117b.fincore.api.ApiException;
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
    Optional<UUID> reserve(UUID requester, String key, String hash, Instant at) {
        UUID id = UUID.randomUUID();
        try {
            return jdbc.query("""
                    INSERT INTO transfer_idempotency_records
                        (id, requester_user_id, idempotency_key, request_hash, hash_version, status, created_at)
                    VALUES (?, ?, ?, ?, ?, 'RESERVED', ?)
                    ON CONFLICT (requester_user_id, idempotency_key) DO NOTHING RETURNING id
                    """, (rs, row) -> rs.getObject("id", UUID.class),
                    id, requester, key, hash, TransferRequestHasher.HASH_VERSION, Timestamp.from(at)).stream().findFirst();
        } catch (DataAccessException failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof SQLException sql && "P7001".equals(sql.getSQLState())) {
                    throw unavailable();
                }
            }
            throw failure;
        }
    }

    void requireAvailableKey(String key) {
        if (Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM transfer_idempotency_records
                    WHERE requester_user_id IS NULL AND idempotency_key = ?)
                """, Boolean.class, key))) {
            throw unavailable();
        }
    }

    private ApiException unavailable() {
        return new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_UNAVAILABLE", "Idempotency key is unavailable.");
    }

    Reservation findByRequesterAndKey(UUID requester, String key) {
        return jdbc.queryForObject("""
                SELECT id, request_hash, hash_version, status, transfer_id, failure_code
                FROM transfer_idempotency_records WHERE requester_user_id = ? AND idempotency_key = ?
                """, (rs, row) -> new Reservation(rs.getObject("id", UUID.class), rs.getString("request_hash"),
                rs.getInt("hash_version"), TransferIdempotencyStatus.valueOf(rs.getString("status")),
                rs.getObject("transfer_id", UUID.class), rs.getString("failure_code") == null ? null
                : TransferErrorCode.valueOf(rs.getString("failure_code"))), requester, key);
    }

    void succeed(UUID id, UUID requester, UUID transferId, Instant at) {
        requireOne(jdbc.update("""
                UPDATE transfer_idempotency_records SET status = 'SUCCEEDED', transfer_id = ?, resolved_at = ?
                WHERE id = ? AND requester_user_id = ? AND status = 'RESERVED'
                """, transferId, Timestamp.from(at), id, requester));
    }

    void reject(UUID id, UUID requester, TransferErrorCode code, Instant at) {
        requireOne(jdbc.update("""
                UPDATE transfer_idempotency_records SET status = 'REJECTED', failure_code = ?, resolved_at = ?
                WHERE id = ? AND requester_user_id = ? AND status = 'RESERVED'
                """, code.name(), Timestamp.from(at), id, requester));
    }

    private void requireOne(int rows) {
        if (rows != 1) {
            throw new IllegalStateException("Idempotency reservation could not be finalized");
        }
    }
}
