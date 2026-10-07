package io.github.alejandro117b.fincore.account;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import io.github.alejandro117b.fincore.account.api.AccountTransactionResponse;
import io.github.alejandro117b.fincore.account.api.AccountTransactionsCursor;
import io.github.alejandro117b.fincore.account.api.AccountTransactionsResponse;
import io.github.alejandro117b.fincore.api.ApiException;
import io.github.alejandro117b.fincore.api.ApiInputs;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountStatementQueryService {
    private final AccountService accounts;
    private final JdbcTemplate jdbc;

    public AccountStatementQueryService(AccountService accounts, JdbcTemplate jdbc) {
        this.accounts = accounts;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public AccountTransactionsResponse get(UUID accountId, String requestedLimit, String token) {
        int limit = parseLimit(requestedLimit);
        AccountTransactionsCursor cursor = token == null ? null : AccountTransactionsCursor.decode(token, accountId);
        accounts.requireAccount(accountId);
        var ledger = accounts.requireLedger(accountId);
        List<Object> arguments = new ArrayList<>();
        arguments.add(ledger.getId());
        String bounds = "";
        if (cursor != null) {
            bounds = """
                    AND (j.posted_at, j.id) <= (?::timestamptz, ?::uuid)
                    AND (j.posted_at, j.id) < (?::timestamptz, ?::uuid)
                    """;
            arguments.add(Timestamp.from(cursor.upperAt()));
            arguments.add(cursor.upperId());
            arguments.add(Timestamp.from(cursor.afterAt()));
            arguments.add(cursor.afterId());
        }
        arguments.add(limit + 1);
        // Parameters bind all client input. No entities, collection fetches, OFFSET or COUNT query.
        List<AccountTransactionResponse> rows = jdbc.query("""
                SELECT j.id AS journal_id, t.id AS transfer_id, j.posted_at, j.currency_code, j.reference,
                    COALESCE(sum(e.amount) FILTER (WHERE e.side = 'DEBIT'), 0) AS debits,
                    COALESCE(sum(e.amount) FILTER (WHERE e.side = 'CREDIT'), 0) AS credits
                FROM ledger_entries e
                JOIN journal_transactions j ON j.id = e.journal_transaction_id
                LEFT JOIN transfers t ON t.journal_transaction_id = j.id
                WHERE e.ledger_account_id = ? AND j.status = 'POSTED'
                """ + bounds + """
                GROUP BY j.id, t.id
                ORDER BY j.posted_at DESC, j.id DESC
                LIMIT ?
                """, (rs, index) -> {
                    BigDecimal debits = rs.getBigDecimal("debits");
                    BigDecimal credits = rs.getBigDecimal("credits");
                    return new AccountTransactionResponse(rs.getObject("journal_id", UUID.class),
                            rs.getObject("transfer_id", UUID.class), rs.getTimestamp("posted_at").toInstant(),
                            rs.getString("currency_code"), ApiInputs.money(debits), ApiInputs.money(credits),
                            ApiInputs.money(credits.subtract(debits)), rs.getString("reference"));
                }, arguments.toArray());
        boolean more = rows.size() > limit;
        List<AccountTransactionResponse> items = rows.subList(0, Math.min(limit, rows.size()));
        String next = null;
        if (more) {
            AccountTransactionResponse top = items.getFirst();
            AccountTransactionResponse last = items.getLast();
            next = new AccountTransactionsCursor(accountId, cursor == null ? top.postedAt() : cursor.upperAt(),
                    cursor == null ? top.journalTransactionId() : cursor.upperId(),
                    last.postedAt(), last.journalTransactionId()).encode();
        }
        return new AccountTransactionsResponse(items, more, next);
    }

    private int parseLimit(String value) {
        if (value == null || !value.matches("[0-9]{1,3}")) { throw invalidLimit(); }
        int limit = Integer.parseInt(value);
        if (limit < 1 || limit > 100) { throw invalidLimit(); }
        return limit;
    }

    private ApiException invalidLimit() {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_LIMIT", "Limit must be an integer between 1 and 100.");
    }
}
