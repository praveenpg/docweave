package io.docweave.partition;

import io.docweave.core.ElementType;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Document-confined emission adapter; optional linking buffers one page, never rewrites an emitted Element. */
final class PdfPageOutput {
    /** Fixed opt-in pending-element ceiling, additional to the document's maxElements. */
    private static final int MAX_PENDING = 4096;
    /** Raw page events retained by an isolated parallel page, null on the ordered writer. */
    private final List<Event> recorded;
    /** Raw elements admitted on an isolated page; warnings do not consume element slots. */
    private int recordedElements;
    /** Per-page admission cannot exceed the document element ceiling. */
    private final int maxElements;
    /** Central source metadata, ID and emission policy. */
    private final PartitionContext context;
    /** Null on the historical direct path; otherwise one page of pending output. */
    private final List<Pending> pending;
    /** Previous-page evidence and real extraction IDs, at most one eligible Table. */
    private Previous previous;
    /** Whether repeated-header source evidence is requested independently of buffering. */
    private final boolean linkTables;
    /** Whether explicitly declared provider headers may contribute matching evidence. */
    private final boolean linkProviders;
    /** Whether bounded multi-row provider header prefixes may contribute evidence. */
    private final boolean providerHeaderPrefixes;
    /** Explicit support for declared merged provider prefixes. */
    private final boolean providerHeaderLayouts;
    /** Whether stable geometric order is explicitly selected. */
    private final boolean geometric;

    /**
     * Selects direct or one-page buffered emission without changing recognition strategies.
     * @param context central document-local sink
     * @param policy explicit continuation policy
     */
    PdfPageOutput(final PartitionContext context, final PdfOptions.TableContinuation policy) {
        this(context, policy, PdfOcrOptions.ReadingOrder.SOURCE_ORDER);
    }

    /**
     * Selects independent continuation and mixed-source ordering policies with one bounded page buffer.
     * @param context document sink
     * @param policy continuation policy
     * @param order native/OCR ordering policy
     */
    PdfPageOutput(final PartitionContext context, final PdfOptions.TableContinuation policy,
            final PdfOcrOptions.ReadingOrder order) {
        this.context = context;
        this.recorded = null;
        this.maxElements = context.options().maxElements();
        linkTables = policy != PdfOptions.TableContinuation.NONE;
        providerHeaderPrefixes = policy.providerPrefixes();
        providerHeaderLayouts = policy.providerLayouts();
        linkProviders = policy.providerHeaders();
        geometric = order == PdfOcrOptions.ReadingOrder.GEOMETRIC;
        pending = linkTables || geometric ? new ArrayList<>() : null;
    }

    /**
     * Copies immutable policy only; parallel pages never touch the central context or previous-page state.
     * @param source ordered document output
     */
    private PdfPageOutput(final PdfPageOutput source) {
        context = source.context;
        maxElements = source.maxElements;
        linkTables = source.linkTables;
        linkProviders = source.linkProviders;
        providerHeaderPrefixes = source.providerHeaderPrefixes;
        providerHeaderLayouts = source.providerHeaderLayouts;
        geometric = source.geometric;
        pending = null;
        recorded = new ArrayList<>();
    }

    /**
     * Creates a page-local raw event buffer with the same table and ordering policy.
     * @return isolated page buffer
     */
    PdfPageOutput pageBuffer() {
        return new PdfPageOutput(this);
    }

    /**
     * Replays a completed page through the single ordered writer, retaining IDs and cross-page links.
     * @param destination ordered document output
     * @param page one-based page number
     * @throws IOException if cancellation or an output budget prevents emission
     */
    void replay(final PdfPageOutput destination, final int page) throws IOException {
        for (final var event : recorded) {
            ElementCollector.checkInterrupted();
            if (event instanceof final Pending item) {
                destination.emit(item.type(), item.text(), item.metadata(), item.signature());
            } else if (event instanceof final Warning warning) {
                destination.warn(warning.code(), warning.message());
            }
        }
        destination.finishPage(page);
        recorded.clear();
    }

    /**
     * Indicates whether source writers should build capped private header evidence.
     * @return true only for explicit continuation inference
     */
    boolean continuations() {
        return linkTables;
    }

    /**
     * Enables provider evidence only under the extended explicit continuation policy.
     * @return whether declared provider headers may be compared across adjacent pages
     */
    boolean providerContinuations() {
        return linkProviders;
    }

    /**
     * Indicates whether explicit multi-row provider prefixes may contribute matching evidence.
     * @return true for explicit prefix or merged-layout policies
     */
    boolean providerHeaderPrefixes() {
        return providerHeaderPrefixes;
    }

    /**
     * Indicates whether exact merged header topology may contribute matching evidence.
     * @return true only for the dedicated layout policy
     */
    boolean providerHeaderLayouts() {
        return providerHeaderLayouts;
    }

    /**
     * Emits ordinary output with no continuation candidate.
     * @param type semantic category
     * @param text extracted content
     * @param metadata final page-local metadata
     * @throws IOException if cancelled, limited or central emission fails
     */
    void emit(final ElementType type, final String text, final Map<String, Object> metadata) throws IOException {
        emit(type, text, metadata, null);
    }

    /**
     * Emits directly by default, or admits one pending output entry under both page and document limits.
     * @param type semantic category
     * @param text extracted content
     * @param metadata final page-local metadata
     * @param signature private bounded ruled-table evidence, otherwise null
     * @throws IOException if cancelled, over element limits or central emission fails
     */
    void emit(final ElementType type, final String text, final Map<String, Object> metadata,
            final PdfTableSignature signature) throws IOException {
        if (recorded != null) {
            ElementCollector.checkInterrupted();
            if (recordedElements >= (linkTables || geometric ? Math.min(MAX_PENDING, maxElements) : maxElements)) {
                throw new PartitionException(
                        PartitionException.Code.ELEMENT_LIMIT, "PDF page exceeds document element budget");
            }
            recordedElements++;
            recorded.add(new Pending(type, text, java.util.Collections.unmodifiableMap(new LinkedHashMap<>(metadata)), signature));
            return;
        }
        if (pending == null) {
            context.emit(type, text, metadata);
            return;
        }
        ElementCollector.checkInterrupted();
        if (pending.size() >= MAX_PENDING
                || pending.size() >= context.options().maxElements() - context.elementCount()) {
            throw new PartitionException(
                    PartitionException.Code.ELEMENT_LIMIT, "PDF buffered page exceeds pending-element budget");
        }
        pending.add(new Pending(type, text, java.util.Collections.unmodifiableMap(new LinkedHashMap<>(metadata)), signature));
    }

    /**
     * Keeps diagnostics in original encounter order even on the optional delayed-emission path.
     * @param code warning category
     * @param message content-free diagnostic
     */
    void warn(final PartitionResult.WarningCode code, final String message) {
        if (recorded != null) {
            recorded.add(new Warning(code, message));
        } else {
            context.warn(code, message);
        }
    }

    /**
     * Matches one isolated table to the previous physical page, then flushes output in original order.
     * Roots remain unmodified; only policy-matching candidates receive backward source-ID links, without semantic
     * certainty.
     * @param page one-based physical source page, including pages with no extracted elements
     * @throws IOException if cancelled, limited or central emission fails
     */
    void finishPage(final int page) throws IOException {
        if (recorded != null || pending == null) {
            return;
        }
        if (geometric) {
            ElementCollector.checkInterrupted();
            pending.sort(Comparator.comparingDouble((Pending item) -> coordinate(item, 1))
                            .thenComparingDouble(item -> coordinate(item, 0)));
        }
        int tables = 0;
        int candidate = -1;
        for (int index = 0; index < pending.size(); index++) {
            ElementCollector.checkInterrupted();
            if (pending.get(index).type() == ElementType.Table) {
                tables++;
                candidate = index;
            }
        }
        final var signature = linkTables && tables == 1 ? pending.get(candidate).signature() : null;
        boolean entryClear = true;
        boolean exitClear = true;
        if (signature != null) {
            for (final var item : pending) {
                ElementCollector.checkInterrupted();
                if (item.type() == ElementType.Table || item.text().isBlank()) {
                    continue;
                }
                if (!(item.metadata().get("coordinates") instanceof final Map<?, ?> coordinates)
                        || !(coordinates.get("points") instanceof final List<?> points) || points.size() != 4) {
                    entryClear = false;
                    exitClear = false;
                    continue;
                }
                final var first = (List<?>) points.getFirst();
                final var third = (List<?>) points.get(2);
                entryClear &= ((Number) first.get(1)).doubleValue() >= signature.top();
                exitClear &= ((Number) third.get(1)).doubleValue() <= signature.bottom();
            }
        }
        final boolean linked = signature != null && previous != null && previous.page() + 1 == page
                               && previous.exitClear() && entryClear && signature.follows(previous.signature());
        Previous current = null;
        for (int index = 0; index < pending.size(); index++) {
            ElementCollector.checkInterrupted();
            final var item = pending.get(index);
            final var metadata = new LinkedHashMap<>(item.metadata());
            if (geometric) {
                metadata.put("pdf_reading_order", "page_top_left_v1");
                metadata.put("pdf_reading_index", index + 1);
            }
            if (linked && index == candidate) {
                metadata.put("pdf_table_continuation",
                        Map.of("version", 1, "policy", "adjacent_repeated_header_v1", "root_element_id",
                                previous.rootId(), "root_page_number", previous.rootPage(), "previous_element_id",
                                previous.elementId(), "previous_page_number", previous.page(), "part_index",
                                previous.part() + 1));
            }
            final var emitted = context.emit(item.type(), item.text(), metadata);
            if (signature != null && index == candidate) {
                current = new Previous(signature, emitted.elementId(), linked ? previous.rootId() : emitted.elementId(),
                        linked ? previous.rootPage() : page, linked ? previous.part() + 1 : 0, page, exitClear);
            }
        }
        previous = current;
        pending.clear();
    }

    /**
     * Reads the minimum displayed coordinate without assuming a source strategy's corner orientation.
     * Malformed/missing coordinates sort last, retaining stable encounter order.
     * @param item pending output
     * @param axis zero for horizontal, one for vertical
     * @return finite minimum or positive infinity for absent/invalid geometry
     */
    private static double coordinate(final Pending item, final int axis) {
        if (!(item.metadata().get("coordinates") instanceof final Map<?, ?> coordinates)
                || !(coordinates.get("points") instanceof final List<?> points) || points.size() != 4) {
            return Double.POSITIVE_INFINITY;
        }
        double minimum = Double.POSITIVE_INFINITY;
        for (final var value : points) {
            if (!(value instanceof final List<?> point) || point.size() != 2
                    || !(point.get(0) instanceof final Number x) || !(point.get(1) instanceof final Number y)
                    || !Double.isFinite(x.doubleValue()) || !Double.isFinite(y.doubleValue())) {
                return Double.POSITIVE_INFINITY;
            }
            minimum = Math.min(minimum, axis == 0 ? x.doubleValue() : y.doubleValue());
        }
        return minimum;
    }

    /**
     * One pending source element; final IDs are generated only by the central sink during flush.
     * @param type semantic category
     * @param text original content
     * @param metadata original page-local properties
     * @param signature private candidate evidence
     */
    private record Pending(ElementType type, String text, Map<String, Object> metadata, PdfTableSignature signature)
            implements Event {}

    /** One raw emission or warning, replayed in page encounter order. */
    private sealed interface Event permits Pending, Warning {}

    /**
     * Page-local diagnostic retained until ordered output.
     * @param code diagnostic category
     * @param message content-free diagnostic
     */
    private record Warning(PartitionResult.WarningCode code, String message) implements Event {}

    /**
     * Constant-size previous-page state; repeated header evidence is capped before admission.
     * @param signature prior table evidence
     * @param elementId real previous source Table ID
     * @param rootId real first source Table ID
     * @param rootPage first source page in chain
     * @param part zero for root, increasing for linked parts
     * @param page prior physical page
     * @param exitClear whether nonblank narrative leaves the outgoing table edge unobstructed
     */
    private record Previous(PdfTableSignature signature, String elementId, String rootId, int rootPage, int part,
            int page, boolean exitClear) {}
}
