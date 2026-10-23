package dev.docweave.validation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Model-control replay against local capture IDs hiding real geometry changes, migrated from
 * {@code test_ruled_scans.py}.
 */
class RuledScanScorerTest {
    @TempDir Path temporary;

    /** Creates the guards. */
    RuledScanScorerTest() {
    }

    /**
     * Writes one independent numeric layout fixture with caller-selected capture identities.
     *
     * @param name directory name
     * @param crop crop identifier
     * @param table table identifier
     * @param edge right edge of the only cell
     * @return fixture directory
     * @throws IOException if writing fails
     */
    private Path capture(final String name, final int crop, final int table, final int edge) throws IOException {
        final Path directory = Files.createDirectory(temporary.resolve(name));
        Files.writeString(directory.resolve("outcome.txt"), "OK\n");
        Files.writeString(directory.resolve("tables.tsv"), "crop\ttable\trows\tcolumns\tleft\ttop\tright\tbottom\n"
                                                                   + crop + "\t" + table + "\t1\t1\t0\t0\t10\t10\n");
        Files.writeString(directory.resolve("anchors.tsv"),
                "crop\ttable\trow\tcolumn\trow_span\tcolumn_span\tleft\ttop\tright\tbottom\n" + crop + "\t" + table
                        + "\t0\t0\t1\t1\t0\t0\t" + edge + "\t10\n");
        return directory;
    }

    /**
     * Returns a modified copy of a frame.
     *
     * @param frame original
     * @param key changed field
     * @param value new value
     * @return copy
     */
    private static Map<String, String> with(final Map<String, String> frame, final String key, final String value) {
        final var result = new HashMap<>(frame);
        result.put(key, value);
        return result;
    }

    @Test
    void resolutionChangeDoesNotAllowSourceReplacementOrRepositioning() {
        final var first = Map.of("sha256", "original", "original_width", "100", "original_height", "100", "pdf_width",
                "48.0", "pdf_height", "48.0", "render_dpi", "150", "width", "99", "height", "99");
        final var higher = with(with(with(first, "render_dpi", "300"), "width", "199"), "height", "199");
        assertTrue(RuledScanScorer.samePhysicalFrame(first, higher));
        for (final var changed : List.of(with(higher, "sha256", "changed"), with(higher, "pdf_width", "49.0"),
                     with(higher, "original_width", "101"))) {
            assertFalse(RuledScanScorer.samePhysicalFrame(first, changed));
        }
    }

    @Test
    void captureIdsDoNotChangeGeometryIdentity() throws IOException {
        final Path first = capture("first", 3, 7, 10);
        final Path second = capture("second", 0, 0, 10);
        assertTrue(RuledScanScorer.canonicalTables(first).same(RuledScanScorer.canonicalTables(second)));
    }

    @Test
    void sameTopologyCannotHideChangedCellGeometryOrFailure() throws IOException {
        final Path first = capture("first", 0, 0, 10);
        final Path second = capture("second", 0, 0, 9);
        assertFalse(RuledScanScorer.canonicalTables(first).same(RuledScanScorer.canonicalTables(second)));
        Files.writeString(second.resolve("outcome.txt"), "TABLE_LIMIT\n");
        assertFalse(RuledScanScorer.canonicalTables(first).same(RuledScanScorer.canonicalTables(second)));
        assertEquals(new RuledScanScorer.Canonical("TABLE_LIMIT", List.of()), RuledScanScorer.canonicalTables(second));
    }
}
