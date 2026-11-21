package com.freightflow.notification.notify;

import java.util.UUID;

/** Why an operator's resend of an email was refused (ADR 0027). */
public final class NotificationExceptions {

    private NotificationExceptions() {
    }

    /** No such email, or (for a customer) not its organisation's (ADR 0018). */
    public static class NotificationNotFoundException extends RuntimeException {
        public NotificationNotFoundException(UUID id) {
            super("No notification " + id);
        }
    }

    /** Only an UNKNOWN email is sent again by hand; the others are sent, waiting, or refused by the server. */
    public static class NotificationNotResendableException extends RuntimeException {
        private final String status;

        public NotificationNotResendableException(UUID id, String status) {
            super("Notification " + id + " is " + status + "; only an UNKNOWN email can be sent again");
            this.status = status;
        }

        public String status() {
            return status;
        }
    }

    /** The operator did not acknowledge that the customer may receive the email twice. */
    public static class ResendNotAcknowledgedException extends RuntimeException {
        public ResendNotAcknowledgedException() {
            super("This email may have arrived already; set acknowledgeDuplicate to true to send it again anyway");
        }
    }
}
