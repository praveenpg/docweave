package io.docweave.partition;

import java.io.IOException;
import java.util.Objects;

/**
 * Typed failure to acquire remote content, distinct from an invalid location ({@link IllegalArgumentException}),
 * an oversized input ({@link PartitionException.Code#INPUT_TOO_LARGE}) and caller cancellation
 * ({@link java.io.InterruptedIOException}). Messages are sanitized: they never contain credentials, signed URLs,
 * temporary paths or raw provider responses. Use {@link #reason()} for programmatic handling.
 */
public final class RemoteSourceException extends IOException {
    /** Stable acquisition failure categories. */
    public enum Reason {
        /** The named object, version or container does not exist. */ NOT_FOUND,
        /** The configured identity may not read the object. */ ACCESS_DENIED,
        /** The acquisition deadline passed before the content was complete. */ DEADLINE_EXCEEDED,
        /** The transfer ended with fewer or more bytes than the provider declared. */ INCOMPLETE,
        /** The provider or network failed, including after bounded retries. */ UNAVAILABLE,
        /** The provider returned a response the resolver cannot use. */ INVALID_RESPONSE
    }

    /**
     * Retains the category when the exception is serialized.
     * @serial Machine-readable acquisition failure category.
     */
    private final Reason reason;

    /**
     * Creates a failure without an underlying exception.
     * @param reason failure category
     * @param message sanitized description
     * @throws NullPointerException if reason is null
     */
    public RemoteSourceException(final Reason reason, final String message) {
        this(reason, message, null);
    }

    /**
     * Creates a failure retaining its cause. Callers must ensure the cause's own message is safe to expose.
     * @param reason failure category
     * @param message sanitized description
     * @param cause original exception, or null
     * @throws NullPointerException if reason is null
     */
    public RemoteSourceException(final Reason reason, final String message, final Throwable cause) {
        super(message, cause);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    /**
     * Returns the machine-readable failure category.
     * @return acquisition failure category
     */
    public Reason reason() {
        return reason;
    }
}
