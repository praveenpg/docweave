package io.docweave.validation;

import io.docweave.DocWeave;
import io.docweave.core.Element;
import io.docweave.partition.*;
import java.nio.file.*;
import java.util.*;
/**
 * Checks, on every page of each given original, that {@code SMALLER_TYPE_BLOCKS_CONTIGUOUS} output differs from
 * {@code SMALLER_TYPE_BLOCKS} output only by the position of side-stream elements: the same multiset of elements
 * (type, text, page, coordinates) and the same main-flow sequence, under three text layouts. Prints counts only.
 */
public final class CheckContiguousSideStreamInvariants {
    /** Prevents construction. */
    private CheckContiguousSideStreamInvariants() {
    }

    /**
     * Extracts with one layout and side-stream policy.
     * @param bytes source
     * @param layout layout
     * @param s policy
     * @return elements
     * @throws Exception on extraction failure
     */
    static List<Element> run(final byte[] bytes, final PdfOptions.TextLayout layout, final PdfOptions.SideStreams s) throws Exception {
        return DocWeave.create(PartitionerRegistry.of(List.of(PdfPartitioner.create(PdfOptions.defaults().withTextLayout(layout).withSideStreams(s)))))
                .partition(DocumentSource.bytes(bytes, "p.pdf")).elements();
    }
    /**
     * Returns whether an element is a side-stream element.
     * @param e element
     * @return true for side-stream output
     */
    static boolean side(final Element e) { return String.valueOf(e.metadata().get("pdf_reading_order")).startsWith("side_stream"); }
    /**
     * Returns an element's identity for comparison.
     * @param e element
     * @return type, text, page and coordinates
     */
    static String key(final Element e) { return e.type() + "|" + e.text() + "|" + e.metadata().get("page_number") + "|" + e.metadata().get("coordinates"); }
    /**
     * Runs the check.
     * @param args original PDF paths
     * @throws Exception on extraction failure
     */
    public static void main(final String[] args) throws Exception {
        int sources = 0, pages = 0, moved = 0, failures = 0;
        for (final String path : args) {
            final byte[] bytes = Files.readAllBytes(Path.of(path));
            for (final var layout : List.of(PdfOptions.TextLayout.COLUMN_PARAGRAPHS, PdfOptions.TextLayout.PARAGRAPHS, PdfOptions.TextLayout.LINES)) {
                final var a = run(bytes, layout, PdfOptions.SideStreams.SMALLER_TYPE_BLOCKS);
                final var b = run(bytes, layout, PdfOptions.SideStreams.SMALLER_TYPE_BLOCKS_CONTIGUOUS);
                final var ka = new ArrayList<String>(); a.forEach(e -> ka.add(key(e)));
                final var kb = new ArrayList<String>(); b.forEach(e -> kb.add(key(e)));
                final var sa = new ArrayList<>(ka); Collections.sort(sa); final var sb = new ArrayList<>(kb); Collections.sort(sb);
                final var mainA = a.stream().filter(e -> !side(e)).map(CheckContiguousSideStreamInvariants::key).toList();
                final var mainB = b.stream().filter(e -> !side(e)).map(CheckContiguousSideStreamInvariants::key).toList();
                final boolean ok = sa.equals(sb) && mainA.equals(mainB);
                if (!ok) { failures++; System.out.println("FAIL " + path + " " + layout); }
                if (!ka.equals(kb)) moved++;
            }
            sources++;
            pages += (int) run(bytes, PdfOptions.TextLayout.LINES, PdfOptions.SideStreams.PRESERVE).stream().map(e -> e.metadata().get("page_number")).distinct().count();
        }
        System.out.println("sources=" + sources + " pages=" + pages + " profile_runs=" + (sources * 3) + " outputs_with_moved_side_blocks=" + moved + " invariant_failures=" + failures);
    }
}
