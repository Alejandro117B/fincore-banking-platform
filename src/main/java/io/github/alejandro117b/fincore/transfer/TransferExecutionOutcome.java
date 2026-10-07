package io.github.alejandro117b.fincore.transfer;

sealed interface TransferExecutionOutcome {
    record Success(TransferResult transfer) implements TransferExecutionOutcome {
    }

    record Rejected(TransferErrorCode code) implements TransferExecutionOutcome {
    }
}
