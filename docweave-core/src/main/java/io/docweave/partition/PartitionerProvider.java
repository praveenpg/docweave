package io.docweave.partition;

import java.nio.charset.Charset;

/**
 * Service-provider contract for independently installed format modules.
 * Default registries discover providers through {@link java.util.ServiceLoader}; each module
 * registers its provider in {@code META-INF/services/io.docweave.partition.PartitionerProvider}.
 * Providers must have stable keys and create stateless/thread-safe strategies. Duplicate installed
 * keys are rejected; explicit {@link PartitionerRegistry.Builder#register} calls can still replace
 * a discovered strategy. Discovery uses the calling thread's context class loader.
 *
 * <p>JDK classpath discovery requires public provider classes with public no-argument constructors.
 * These small infrastructure bridges are not the parser implementations: {@link #create()} returns
 * a hidden implementation of the public {@link Partitioner} interface. Applications use the facade
 * or registry rather than constructing discovery bridges.</p>
 */
public interface PartitionerProvider {
    /**
     * Returns this installed module's format key.
     * @return nonnull, stable format
     */
    DocumentFormat format();
    /**
     * Creates a strategy safe for concurrent document processing.
     * @return nonnull strategy whose format matches {@link #format()}
     */
    Partitioner create();

    /**
     * Suggests an encoding from a bounded prefix when no explicit charset or BOM was supplied.
     * The default supplies no suggestion. Implementations must not fetch external resources,
     * retain/mutate the input, or infer a charset from data beyond this prefix. Native loading
     * currently consults installed HTML providers for head-meta charset declarations.
     * @param prefix at most 8 KiB of original encoded input
     * @return suggested charset, or null for no declaration
     * @throws PartitionException if a declared charset is unsupported
     */
    default Charset detectCharset(final byte[] prefix) throws PartitionException {
        return null;
    }
}
