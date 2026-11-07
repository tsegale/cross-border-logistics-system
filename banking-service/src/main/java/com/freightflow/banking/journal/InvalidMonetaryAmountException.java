package com.freightflow.banking.journal;

public class InvalidMonetaryAmountException extends RuntimeException {

    public InvalidMonetaryAmountException(String message) {
        super(message);
    }
}
