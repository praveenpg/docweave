package io.docweave.partition;

import io.docweave.core.Element;
import io.docweave.core.ElementType;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Stateless assembler; each session validates identity and drives the shared {@link PdfRangeAssembly}. */
final class DefaultPdfRangeAssembler implements PdfRangeAssembler {
    /** Creates the assembler. */
    DefaultPdfRangeAssembler() {
    }

    @Override
    public PartitionResult assemble(final List<PdfRangeResult> ranges) throws IOException {
        Objects.requireNonNull(ranges, "ranges");
        if (ranges.isEmpty()) {
            throw new IllegalArgumentException("No PDF range results to assemble");
        }
        final long start = System.nanoTime();
        final var elements = new ArrayList<Element>();
        final var session = open(elements::add);
        for (final var range : ranges) {
            session.accept(Objects.requireNonNull(range, "range"));
        }
        final var warnings = session.finish();
        final var first = ranges.getFirst();
        return new PartitionResult(elements, DocumentFormat.PDF, "binary", first.sourceBytes(),
                Duration.ofNanos(System.nanoTime() - start), warnings);
    }

    @Override
    public Session open(final ElementSink sink) {
        return new DefaultSession(Objects.requireNonNull(sink, "sink"));
    }

    /** One single-use streaming assembly. */
    private static final class DefaultSession implements Session {
        /** Consumer of final elements. */
        private final ElementSink sink;
        /** Document warnings, then page warnings. */
        private final List<PartitionResult.Warning> warnings = new ArrayList<>();
        /** Shared assembly path. */
        private final PdfRangeAssembly assembly;
        /** Document-wide ID generator. */
        private final ElementIdGenerator ids = new ElementIdGenerator();
        /** Document-wide element sequence. */
        private int sequence;
        /** Identity of the first accepted result, which every later one must match; no elements are retained. */
        private Identity identity;
        /** Whether the session finished or failed. */
        private boolean closed;

        /**
         * Creates a session.
         * @param sink element consumer
         */
        DefaultSession(final ElementSink sink) {
            this.sink = sink;
            assembly = new PdfRangeAssembly(new PdfRangeAssembly.Output() {
                @Override
                public Element emit(final ElementType type, final String text, final Map<String, Object> metadata)
                        throws IOException {
                    final var element =
                            new Element(ids.create(identity.filename(), text, sequence), type, text, metadata);
                    sequence++;
                    DefaultSession.this.sink.accept(element);
                    return element;
                }

                @Override
                public void warn(final PartitionResult.Warning warning) {
                    warnings.add(warning);
                }
            });
        }

        @Override
        public void accept(final PdfRangeResult range) throws IOException {
            Objects.requireNonNull(range, "range");
            if (closed) {
                throw new IllegalStateException("PDF range assembly session is closed");
            }
            boolean completed = false;
            try {
                check(range);
                if (identity == null) {
                    identity = Identity.of(range);
                }
                assembly.begin(
                        range.coverage(), range.documentPages(), range.documentWarnings(), range.firstPageTable());
                for (final var page : range.pages()) {
                    assembly.page(page);
                }
                assembly.end(range.coverage(), range.lastPageTable());
                completed = true;
            } finally {
                if (!completed) {
                    closed = true;
                }
            }
        }

        @Override
        public List<PartitionResult.Warning> finish() {
            if (closed) {
                throw new IllegalStateException("PDF range assembly session is closed");
            }
            closed = true;
            assembly.finish();
            return List.copyOf(warnings);
        }

        /**
         * Rejects results from another format, library, source, configuration or document description.
         * @param range candidate result
         * @throws IllegalArgumentException if it does not belong with the earlier results
         */
        private void check(final PdfRangeResult range) {
            if (range.formatVersion() != PdfRangeResult.FORMAT_VERSION
                    || !range.libraryVersion().equals(PdfRangeResult.currentLibraryVersion())) {
                throw new IllegalArgumentException("PDF range result format " + range.formatVersion() + " from "
                                                   + range.libraryVersion() + " cannot be assembled by "
                                                   + PdfRangeResult.currentLibraryVersion());
            }
            if (!range.idReferenceFields().equals(PdfRangeResult.ID_REFERENCE_FIELDS)) {
                throw new IllegalArgumentException("PDF range result declares unsupported ID reference fields");
            }
            if (identity == null) {
                return;
            }
            identity.check(range);
        }
    }

    /**
     * The identity fields of the first accepted result; holding only these keeps a session's memory to the range
     * being assembled plus the continuation state.
     * @param sourceSha256 source digest
     * @param sourceBytes source size
     * @param filename optional filename
     * @param optionsDigest options digest
     * @param documentPages document page count
     * @param documentWarnings document warnings
     * @param libraryVersion producing library version
     */
    private record Identity(String sourceSha256, int sourceBytes, String filename, String optionsDigest,
            int documentPages, List<PartitionResult.Warning> documentWarnings, String libraryVersion) {
        /**
         * Copies the identity of a result.
         * @param range result
         * @return identity
         */
        static Identity of(final PdfRangeResult range) {
            return new Identity(range.sourceSha256(), range.sourceBytes(), range.filename(), range.optionsDigest(),
                    range.documentPages(), range.documentWarnings(), range.libraryVersion());
        }

        /**
         * Rejects a result that does not belong with the first one.
         * @param range later result
         * @throws IllegalArgumentException if it differs
         */
        void check(final PdfRangeResult range) {
            if (!sourceSha256().equals(range.sourceSha256()) || sourceBytes() != range.sourceBytes()) {
                throw new IllegalArgumentException("PDF range results come from different sources");
            }
            if (!optionsDigest().equals(range.optionsDigest())) {
                throw new IllegalArgumentException("PDF range results were produced with different options");
            }
            if (!Objects.equals(filename(), range.filename()) || documentPages() != range.documentPages()
                    || !documentWarnings().equals(range.documentWarnings())
                    || !libraryVersion().equals(range.libraryVersion())) {
                throw new IllegalArgumentException("PDF range results describe different documents");
            }
        }
    }
}
