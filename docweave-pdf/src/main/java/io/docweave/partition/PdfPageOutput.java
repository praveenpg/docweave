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
    private static final int MAX_PENDING = StandardPdfBudget.MAX_PENDING;
    /** Raw page events retained by an isolated parallel page, null on the ordered writer. */
    private final List<Event> recorded;
    /** Raw elements admitted on an isolated page; warnings do not consume element slots. */
    private int recordedElements;
    /** Per-page admission cannot exceed the document element ceiling. */
    private final int maxElements;
    /** Central source metadata, ID and emission policy. */
    private final PdfPageSink sink;
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
    /** Call budget charged with admitted elements; null keeps the historical context-based checks. */
    private final PdfBudget budget;
    /** One-based page of an isolated page buffer, or the page being replayed into the ordered writer. */
    private int page;
    /** Whether elements are only charged and counted, never retained, linked or emitted. */
    private final boolean counting;

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
        this(PdfPageSink.of(context), policy, order, null);
    }

    /**
     * Creates the ordered writer of a partition call whose element admissions are charged to a call budget.
     * @param sink document or page-range sink, notified at the end of every page
     * @param policy continuation policy
     * @param order native/OCR ordering policy
     * @param budget call budget, or null for the historical context-based element checks
     */
    PdfPageOutput(final PdfPageSink sink, final PdfOptions.TableContinuation policy,
            final PdfOcrOptions.ReadingOrder order, final PdfBudget budget) {
        this(sink, policy, order, budget, false);
    }

    /**
     * Creates the ordered writer of a partition call, optionally in counting mode, where each element is charged
     * exactly as on the retaining path and then dropped: pages keep only their warnings and usage.
     * @param sink document or page-range sink, notified at the end of every page
     * @param policy continuation policy
     * @param order native/OCR ordering policy
     * @param budget nonnull call budget in counting mode, otherwise null for the historical checks
     * @param counting whether elements are counted instead of retained
     */
    PdfPageOutput(final PdfPageSink sink, final PdfOptions.TableContinuation policy,
            final PdfOcrOptions.ReadingOrder order, final PdfBudget budget, final boolean counting) {
        this.counting = counting;
        this.budget = budget;
        this.sink = sink;
        this.recorded = null;
        this.maxElements = sink.options().maxElements();
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
     * @param page one-based physical page buffered by the copy
     */
    private PdfPageOutput(final PdfPageOutput source, final int page) {
        this.page = page;
        counting = source.counting;
        budget = source.budget;
        sink = source.sink;
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
     * Creates a page-local raw event buffer with the same table, ordering and budget policy.
     * @param page one-based physical page the buffer belongs to
     * @return isolated page buffer
     */
    PdfPageOutput pageBuffer(final int page) {
        return new PdfPageOutput(this, page);
    }

    /**
     * Replays a completed page through the single ordered writer, retaining IDs and cross-page links.
     * @param destination ordered document output
     * @param page one-based page number
     * @throws IOException if cancellation or an output budget prevents emission
     */
    void replay(final PdfPageOutput destination, final int page) throws IOException {
        destination.page = page;
        if (counting && recordedElements > 0) {
            // Counting mode: one ordered charge per kind with the page total, not one per element.
            ElementCollector.checkInterrupted();
            if (linkTables || geometric) {
                budget.charge(PdfBudget.Kind.PAGE_PENDING, recordedElements, page);
            }
            budget.charge(PdfBudget.Kind.ELEMENTS, recordedElements, page);
        }
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
            if (counting) {
                budget.charge(PdfBudget.Kind.PAGE_ELEMENTS, 1, page);
                recordedElements++;
                return;
            }
            if (budget != null) {
                // Budget-independent memory ceiling: one page never retains more than the document may hold.
                if (recordedElements >= maxElements) {
                    throw new PartitionException(
                            PartitionException.Code.ELEMENT_LIMIT, "PDF page exceeds document element budget");
                }
                budget.charge(PdfBudget.Kind.PAGE_ELEMENTS, 1, page);
            } else if (recordedElements
                       >= (linkTables || geometric ? Math.min(MAX_PENDING, maxElements) : maxElements)) {
                throw new PartitionException(
                        PartitionException.Code.ELEMENT_LIMIT, "PDF page exceeds document element budget");
            }
            recordedElements++;
            recorded.add(new Pending(
                    type, text, java.util.Collections.unmodifiableMap(new LinkedHashMap<>(metadata)), signature));
            return;
        }
        if (pending == null) {
            if (budget != null) {
                ElementCollector.checkInterrupted();
                budget.charge(PdfBudget.Kind.ELEMENTS, 1, page);
            }
            sink.emit(type, text, metadata);
            return;
        }
        ElementCollector.checkInterrupted();
        if (budget != null) {
            budget.charge(PdfBudget.Kind.PAGE_PENDING, 1, page);
            budget.charge(PdfBudget.Kind.ELEMENTS, 1, page);
        } else if (pending.size() >= MAX_PENDING
                   || pending.size() >= sink.options().maxElements() - sink.elementCount()) {
            throw new PartitionException(
                    PartitionException.Code.ELEMENT_LIMIT, "PDF buffered page exceeds pending-element budget");
        }
        pending.add(new Pending(
                type, text, java.util.Collections.unmodifiableMap(new LinkedHashMap<>(metadata)), signature));
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
            sink.warn(code, message);
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
        if (recorded != null) {
            return;
        }
        if (pending == null) {
            sink.pageFinished(page, null);
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
        PdfTableBoundary boundary = null;
        for (int index = 0; index < pending.size(); index++) {
            ElementCollector.checkInterrupted();
            final var item = pending.get(index);
            final var metadata = new LinkedHashMap<>(item.metadata());
            if (geometric) {
                metadata.put("pdf_reading_order", "page_top_left_v1");
                metadata.put("pdf_reading_index", index + 1);
            }
            if (linked && index == candidate) {
                metadata.put(
                        "pdf_table_continuation", continuation(previous.rootId(), previous.rootPage(),
                                                          previous.elementId(), previous.page(), previous.part() + 1));
            }
            final var emitted = sink.emit(item.type(), item.text(), metadata);
            if (signature != null && index == candidate) {
                current = new Previous(signature, emitted.elementId(), linked ? previous.rootId() : emitted.elementId(),
                        linked ? previous.rootPage() : page, linked ? previous.part() + 1 : 0, page, exitClear);
                boundary = new PdfTableBoundary(
                        page, emitted.elementId(), PdfTableBoundary.Evidence.of(signature), entryClear, exitClear);
            }
        }
        previous = current;
        pending.clear();
        sink.pageFinished(page, boundary);
    }

    /**
     * Builds the continuation link of a table that continues the previous page's table. Shared by the ordered
     * writer and range assembly so both produce identical metadata.
     * @param rootId ID of the chain's first table
     * @param rootPage page of the chain's first table
     * @param previousId ID of the continued table
     * @param previousPage page of the continued table
     * @param part part index of the linking table
     * @return immutable link metadata
     */
    static Map<String, Object> continuation(
            final String rootId, final int rootPage, final String previousId, final int previousPage, final int part) {
        return Map.of("version", 1, "policy", "adjacent_repeated_header_v1", "root_element_id", rootId,
                "root_page_number", rootPage, "previous_element_id", previousId, "previous_page_number", previousPage,
                "part_index", part);
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
