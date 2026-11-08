package io.docweave.partition;

import io.docweave.core.Element;
import io.docweave.core.ElementType;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Decorates a Markdown document context so strategies see the body after its front matter and every emitted element
 * carries the parsed values as {@code front_matter}. All other calls are forwarded unchanged.
 */
final class FrontMatterContext implements PartitionContext {
    /** Metadata key holding the parsed values. */
    static final String KEY = "front_matter";
    /** Decorated context. */
    private final PartitionContext delegate;
    /** Markdown after the front matter. */
    private final String body;
    /** Parsed values; empty adds no metadata. */
    private final Map<String, Object> values;

    /**
     * Creates a decorator.
     * @param delegate document context
     * @param body Markdown after the front matter
     * @param values parsed values, possibly empty
     */
    FrontMatterContext(final PartitionContext delegate, final String body, final Map<String, Object> values) {
        this.delegate = delegate;
        this.body = body;
        this.values = values;
    }

    @Override
    public String text() {
        return body;
    }

    @Override
    public byte[] binaryContent() {
        return delegate.binaryContent();
    }

    @Override
    public ByteBuffer binaryBuffer() {
        return delegate.binaryBuffer();
    }

    @Override
    public PartitionProgressReporter progress() {
        return delegate.progress();
    }

    @Override
    public String filename() {
        return delegate.filename();
    }

    @Override
    public DocumentFormat format() {
        return delegate.format();
    }

    @Override
    public PartitionOptions options() {
        return delegate.options();
    }

    @Override
    public int elementCount() {
        return delegate.elementCount();
    }

    @Override
    public Element elementAt(final int index) {
        return delegate.elementAt(index);
    }

    @Override
    public Element emit(final ElementType type, final String text, final Map<String, Object> metadata)
            throws IOException {
        if (values.isEmpty()) {
            return delegate.emit(type, text, metadata);
        }
        final var combined = new LinkedHashMap<String, Object>(metadata);
        combined.put(KEY, values);
        return delegate.emit(type, text, combined);
    }

    @Override
    public void warn(final PartitionResult.WarningCode code, final String message) {
        delegate.warn(code, message);
    }
}
