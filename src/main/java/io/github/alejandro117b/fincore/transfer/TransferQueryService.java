package io.github.alejandro117b.fincore.transfer;

import java.util.UUID;
import io.github.alejandro117b.fincore.api.ApiException;
import io.github.alejandro117b.fincore.security.ResourceAccessPolicy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransferQueryService {
    private final TransferRepository transfers;
    private final ResourceAccessPolicy access;

    public TransferQueryService(TransferRepository transfers, ResourceAccessPolicy access) {
        this.transfers = transfers;
        this.access = access;
    }

    @Transactional(readOnly = true)
    public TransferResult get(UUID id) {
        var actor = access.requireUser();
        return transfers.findVisibleResult(id, actor.customerId()).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "TRANSFER_NOT_FOUND", "Transfer was not found."));
    }
}
