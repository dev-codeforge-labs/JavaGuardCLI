package com.devmanchego.javaguard.adapter;

/**
 * The hook payload could not be read or understood. Callers in hook mode
 * never let this escape to the agent: they fail open (contracts &sect;1.5).
 */
public class PayloadException extends Exception {

    public PayloadException(String message) {
        super(message);
    }

    public PayloadException(String message, Throwable cause) {
        super(message, cause);
    }
}
