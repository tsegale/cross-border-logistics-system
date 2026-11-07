package com.freightflow.banking.transfer;

import java.util.UUID;

public class TransferNotFoundException extends RuntimeException {

    private final UUID transferId;

    public TransferNotFoundException(UUID transferId) {
        super("Transfer '" + transferId + "' was not found");
        this.transferId = transferId;
    }

    public UUID getTransferId() {
        return transferId;
    }
}
