package io.github.alejandro117b.fincore.transfer;

import java.util.UUID;
import io.github.alejandro117b.fincore.api.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransferQueryService {
    private final TransferRepository transfers;

    public TransferQueryService(TransferRepository transfers) { this.transfers = transfers; }

    @Transactional(readOnly = true)
    public TransferResult get(UUID id) {
        return TransferResult.from(transfers.findById(id).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "TRANSFER_NOT_FOUND", "Transfer was not found.")));
    }
}
