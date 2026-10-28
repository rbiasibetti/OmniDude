package com.omnidude.notification;

/** Falha de envio. {@code retryable}: vale tentar de novo (rede, 5xx, 429); erro de configuração não. */
public class NotifyException extends Exception {

    private final boolean retryable;

    public NotifyException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    public boolean retryable() {
        return retryable;
    }
}
