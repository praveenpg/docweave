package io.docweave.partition;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.TreeMap;

/** Offline text-free comparison of captured native/model row geometry, not a physical-row annotation oracle. */
public final class AuditCapturedRowBands {
    /** Prevents utility construction. */
    private AuditCapturedRowBands() {
    }

    /**
     * One source row interval in the unchanged displayed PDF coordinate frame.
     * @param top finite upper edge
     * @param bottom finite lower edge, strictly greater
     */
    private record Band(double top, double bottom) {
        /** Rejects invalid numeric intervals. */
        private Band {
            if (!Double.isFinite(top) || !Double.isFinite(bottom) || top >= bottom) {
                throw new IllegalArgumentException("Invalid captured row band");
            }
        }
    }

    /**
     * Audits two successful single-table captures, never reading source text into the report.
     * @param args native capture JSON, model capture JSON and NEW output; or --self-test
     * @throws Exception if schema/frame/geometry/fresh-output or owned control checks fail
     */
    public static void main(final String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--self-test")) {
            final var halfOpen = associate(List.of(new Band(5, 15)), List.of(new Band(0, 10), new Band(10, 20)));
            if (!halfOpen.equals(List.of(0, 1, 0))) {
                throw new IllegalStateException("Half-open center ownership regressed");
            }
            if (!associate(List.of(new Band(0, 5), new Band(5, 10), new Band(20, 30)), List.of(new Band(0, 10)))
                            .equals(List.of(2, 1))) {
                throw new IllegalStateException("Combined/missing diagnostic rows were hidden");
            }
            return;
        }
        if (args.length != 3) {
            throw new IllegalArgumentException("Expected native capture, model capture, NEW numeric report");
        }
        if (Files.size(Path.of(args[0])) > 16 * 1024 * 1024 || Files.size(Path.of(args[1])) > 16 * 1024 * 1024) {
            throw new IllegalArgumentException("Captured input exceeds16MiB");
        }
        final var mapper = JsonMapper.builder().build();
        final var nativeTable = table(mapper.readTree(Files.readString(Path.of(args[0]))));
        final var modelTable = table(mapper.readTree(Files.readString(Path.of(args[1]))));
        for (final String field : List.of("system", "layout_width", "layout_height")) {
            if (!nativeTable.get("coordinates").get(field).equals(modelTable.get("coordinates").get(field))) {
                throw new IllegalArgumentException("Captured source frames differ");
            }
        }
        final var nativeBands = bands(nativeTable);
        final var modelBands = bands(modelTable);
        final var counts = associate(nativeBands, modelBands);
        final var result = new LinkedHashMap<String, Object>();
        result.put("native_row_count", nativeTable.get("pdf_table_cells").get("row_count").asInt());
        result.put("model_row_count", modelTable.get("pdf_table_cells").get("row_count").asInt());
        result.put("native_recovered_row_bands", nativeBands.size());
        result.put("model_recovered_row_bands", modelBands.size());
        result.put("native_centers_per_model_band", counts.subList(0, modelBands.size()));
        result.put("unassigned_native_centers", counts.getLast());
        result.put(
                "empty_model_bands", counts.subList(0, modelBands.size()).stream().filter(count -> count == 0).count());
        result.put("model_bands_with_multiple_native_centers",
                counts.subList(0, modelBands.size()).stream().filter(count -> count > 1).count());
        Files.write(Path.of(args[2]), mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(result),
                StandardOpenOption.CREATE_NEW);
    }

    /**
     * Requires a unique successful table rather than selecting by text or repairing failed outcomes.
     * @param capture explicit local capture
     * @return unchanged metadata of its single table
     */
    private static JsonNode table(final JsonNode capture) {
        if (!capture.get("outcome").asString().equals("SUCCESS") || capture.get("tables").size() != 1) {
            throw new IllegalArgumentException("Row audit requires one successful captured table");
        }
        return capture.get("tables").get(0);
    }

    /**
     * Recovers row bands only from single-row anchors, retaining unrecoverable row counts separately.
     * @param table complete captured geometry
     * @return bands in original row-index order, without splitting spans or consulting text
     */
    private static List<Band> bands(final JsonNode table) {
        final var result = new TreeMap<Integer, Band>();
        for (final var cell : table.get("pdf_table_cells").get("cells")) {
            if (cell.get("row_span").asInt() != 1) {
                continue;
            }
            final var points = cell.get("coordinates").get("points");
            final var band = new Band(points.get(0).get(1).asDouble(), points.get(2).get(1).asDouble());
            final var previous = result.putIfAbsent(cell.get("row_index").asInt(), band);
            if (previous != null
                    && (Math.abs(previous.top() - band.top()) > 0.01
                            || Math.abs(previous.bottom() - band.bottom()) > 0.01)) {
                throw new IllegalArgumentException("Captured columns disagree on row bounds");
            }
        }
        return List.copyOf(result.values());
    }

    /**
     * Counts native band centers in half-open model bands; the last slot keeps unassigned centers.
     * @param source native geometry intervals, not annotation labels
     * @param target model geometry intervals
     * @return model-band counts plus one unmatched count; no sequence shifting or source text scoring
     * @throws java.io.IOException if cancelled or the shared geometry work ceiling is exceeded
     */
    private static List<Integer> associate(final List<Band> source, final List<Band> target)
            throws java.io.IOException {
        final var work = new PdfTableWork();
        final var counts = new ArrayList<>(java.util.Collections.nCopies(target.size() + 1, 0));
        for (final var band : source) {
            final double center = (band.top() + band.bottom()) / 2;
            int owner = target.size();
            for (int index = 0; index < target.size(); index++) {
                work.step();
                if (center >= target.get(index).top() && center < target.get(index).bottom()) {
                    if (owner != target.size()) {
                        throw new IllegalArgumentException("Overlapping model row bands");
                    }
                    owner = index;
                }
            }
            counts.set(owner, counts.get(owner) + 1);
        }
        return List.copyOf(counts);
    }
}
