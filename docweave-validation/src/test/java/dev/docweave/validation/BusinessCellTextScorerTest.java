package dev.docweave.validation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Offline guards for the text-free business comparison, migrated from {@code test_business_cell_text.py}.
 *
 * <p>Each test keeps the retired Python method's name in camel case and its assertions; no OCR runs.
 */
class BusinessCellTextScorerTest {
    @TempDir Path temporary;

    /** Creates the guards. */
    BusinessCellTextScorerTest() {
    }

    /**
     * Builds one emitted table fixture, as the retired {@code table()} helper.
     *
     * @param text escaped cell text
     * @param rows grid rows
     * @param columns grid columns
     * @param rowSpan anchor row span
     * @param columnSpan anchor column span
     * @param box left, top, right, bottom
     * @return mutable table metadata
     */
    private static Map<String, Object> table(final String text, final long rows, final long columns, final long rowSpan,
            final long columnSpan, final long... box) {
        final long[] bounds = box.length == 0 ? new long[] {0, 0, 10, 10} : box;
        final var coordinates = new LinkedHashMap<String, Object>();
        coordinates.put("system", "PDFUserSpace");
        coordinates.put("points", List.of(List.of(bounds[0], bounds[1]), List.of(bounds[2], bounds[1]),
                                          List.of(bounds[2], bounds[3]), List.of(bounds[0], bounds[3])));
        final var anchor = new LinkedHashMap<String, Object>();
        anchor.put("row_index", 0L);
        anchor.put("column_index", 0L);
        anchor.put("row_span", rowSpan);
        anchor.put("column_span", columnSpan);
        final var grid = new LinkedHashMap<String, Object>();
        grid.put("row_count", rows);
        grid.put("column_count", columns);
        grid.put("cells", new ArrayList<Object>(List.of(anchor)));
        final var result = new LinkedHashMap<String, Object>();
        result.put("coordinates", coordinates);
        result.put("text_as_html", "<table><tr><td>" + text + "</td></tr></table>");
        result.put("pdf_table_cells", grid);
        return result;
    }

    @Test
    void missingTableDoesNotMatchBlankExpectations() {
        final var result = BusinessCellTextScorer.compare(List.of(List.of("A", "")), null);
        assertEquals(0L, result.get("matched_positions"));
        assertEquals(false, result.get("full_grid_text_match"));
    }

    @Test
    void uncoveredSlotsDoNotMatchBlanks() {
        final var result = BusinessCellTextScorer.compare(List.of(List.of("A", "")), table("A", 1, 2, 1, 1));
        assertEquals(1L, result.get("matched_positions"));
        assertEquals(0L, result.get("matched_empty"));
    }

    @Test
    void mergedAnchorProjectionIsEvaluationOnly() {
        final var prediction = table("A", 1, 2, 1, 2);
        assertEquals(true,
                BusinessCellTextScorer.compare(List.of(List.of("A", "")), prediction).get("full_grid_text_match"));
        assertEquals(
                1, PythonJson.array(PythonJson.object(prediction.get("pdf_table_cells"), "grid").get("cells"), "cells")
                           .size());
    }

    @Test
    void geometrySelectionDoesNotChooseBetterText() {
        final var wrong = table("wrong", 1, 1, 1, 1);
        final var right = table("A", 1, 1, 1, 1, 0, 0, 9, 9);
        final var selection = BusinessCellTextScorer.select(List.of(wrong, right), new TableGeometry.Box(0, 0, 10, 10));
        assertSame(wrong, selection.table());
        assertEquals(1.0, selection.overlap().doubleValue());
        assertNull(BusinessCellTextScorer.select(List.of(right), new TableGeometry.Box(20, 20, 30, 30)).table());
    }

    @Test
    void profileNamesCannotEscapeCaptureDirectoryOrDuplicateCases() {
        for (final var profiles : List.of(
                     List.<String>of(), List.of("../outside"), List.of("default", "default"), List.of("/absolute"))) {
            final var failure = assertThrows(IllegalArgumentException.class,
                    ()
                            -> BusinessCellTextScorer.run(
                                    Map.of(), Path.of("/unused-cache"), Path.of("/unused-capture"), profiles),
                    profiles::toString);
            assertEquals("Expected unique simple profile names", failure.getMessage());
        }
    }

    @Test
    void explicitNativeProfilePreservesReferenceDenominator() throws IOException {
        final Path root = temporary;
        Files.writeString(root.resolve("expected.csv"), "A,\n");
        Files.writeString(root.resolve("runtime.json"), "{}");
        Files.writeString(root.resolve("source.json"), "{}");
        final var capture = new LinkedHashMap<String, Object>();
        capture.put("outcome", "SUCCESS");
        capture.put("tables", List.of(table("A", 1, 2, 1, 1)));
        capture.put("page_calls", 0L);
        capture.put("cell_calls", 0L);
        capture.put("layout_calls", 0L);
        capture.put("layout_tables", 0L);
        capture.put("page_raster_sha256", List.of());
        Files.writeString(root.resolve("default.json"), PythonJson.dumps(capture, false));
        final var entry = new LinkedHashMap<String, Object>();
        entry.put("id", ".");
        entry.put("csv", "expected.csv");
        entry.put("expected_rows", 1L);
        entry.put("expected_columns", 2L);
        entry.put("domain", "owned-control");
        entry.put("roi_left_top_right_bottom", List.of(0L, 0L, 10L, 10L));
        final var manifest = new LinkedHashMap<String, Object>();
        manifest.put("inputs", List.of());
        manifest.put("cases", List.of(entry));
        final var result = PythonJson.object(
                PythonJson
                        .array(BusinessCellTextScorer.run(manifest, root, root, List.of("default")).get("cases"),
                                "cases")
                        .getFirst(),
                "case");
        assertEquals("default", result.get("profile"));
        assertEquals(2L, result.get("reference_positions"));
        assertEquals(1L, result.get("matched_positions"));
        assertEquals(0L, result.get("page_calls"));
    }

    @Test
    void asciiWhitespaceOnly() {
        assertEquals("A B", BusinessCellTextScorer.normalize(" A\t\n B "));
        assertNotEquals(BusinessCellTextScorer.normalize("a"), BusinessCellTextScorer.normalize("A"));
        assertEquals("A B", BusinessCellTextScorer.normalize("A B"));
    }

    @Test
    void escapedHtmlTextAndOverlappingAnchors() {
        final var prediction = table("A &amp; B&lt;C", 1, 1, 1, 1);
        assertEquals("A & B<C", BusinessCellTextScorer.project(prediction).slots().get(List.of(0, 0)));
        final var cells =
                PythonJson.array(PythonJson.object(prediction.get("pdf_table_cells"), "grid").get("cells"), "cells");
        cells.add(cells.getFirst());
        prediction.put("text_as_html", "<table><tr><td>A</td><td>B</td></tr></table>");
        final var failure =
                assertThrows(IllegalArgumentException.class, () -> BusinessCellTextScorer.project(prediction));
        assertTrue(failure.getMessage().contains("Overlapping"));
    }
}
