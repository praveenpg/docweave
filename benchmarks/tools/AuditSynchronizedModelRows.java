package io.docweave.validation;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Numeric-only gate for synchronized native baselines; never changes source text or model topology. */
public final class AuditSynchronizedModelRows {
    /** Prevents construction of the standalone diagnostic. */
    private AuditSynchronizedModelRows() {
    }

    /**
     * Retains every row and empty page from the complete frozen two-profile inventory.
     * A candidate requires multiple occupied single-column cells, no spanning cells,
     * equal baseline counts and matching baseline positions within the existing two-point
     * tolerance. This is necessary geometric evidence, not proof of distinct logical rows.
     * @param args frozen inventory, its expected SHA256 and fresh report; or --self-test
     * @throws Exception if source identities, completeness, controls or output access fail
     */
    public static void main(final String[] args) throws Exception {
        controls();
        if (args.length == 1 && args[0].equals("--self-test")) {
            System.out.println("Synchronized model-row controls passed");
            return;
        }
        require(args.length == 3, "Expected frozen inventory, expected SHA256 and fresh output");
        final var bytes = Files.readAllBytes(Path.of(args[0]));
        final String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        require(digest.equals(args[1]), "Frozen inventory identity changed");
        final var mapper = JsonMapper.builder().build();
        final var inventory = mapper.readTree(bytes);
        final var results = new ArrayList<Map<String, Object>>();
        final var profiles = new java.util.HashSet<String>();
        for (final var page : inventory.get("results")) {
            final int number = page.get("page").asInt();
            final String family = page.get("family").asString();
            require(number >= 1 && number <= 11 && List.of("tatr", "yolox").contains(family)
                            && profiles.add(number + ":" + family),
                    "Unexpected or duplicate page/profile");
            final var rows = new LinkedHashMap<Key, List<Cell>>();
            for (final var cell : page.get("cells")) {
                final var key = new Key(cell.get("crop").asInt(), cell.get("table").asInt(), cell.get("row").asInt());
                rows.computeIfAbsent(key, ignored -> new ArrayList<>()).add(read(cell));
            }
            final var observations = new ArrayList<Map<String, Object>>();
            for (final var row : rows.entrySet()) {
                final var ordered = row.getValue().stream().sorted(Comparator.comparingInt(Cell::column)).toList();
                observations.add(Map.of("crop", row.getKey().crop(), "table", row.getKey().table(), "row",
                        row.getKey().row(), "cells", ordered.size(), "occupied_cells",
                        ordered.stream().filter(cell -> !cell.baselines().isEmpty()).count(), "multiline_cells",
                        ordered.stream().filter(cell -> cell.baselines().size() > 1).count(), "gate",
                        classify(ordered)));
            }
            results.add(Map.of(
                    "page", number, "family", family, "outcome", page.get("outcome").asString(), "rows", observations));
        }
        require(profiles.size() == 22, "Incomplete original page/profile population");
        Files.writeString(Path.of(args[2]),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("scope",
                        "All frozen model rows; synchronized baseline gate only, no topology repair or correctness score",
                        "runtime", Runtime.version().toString(), "inventory_sha256", digest, "source_sha256",
                        inventory.get("source_sha256").asString(), "tolerance_points", 2, "results", results))
                        + "\n",
                StandardOpenOption.CREATE_NEW);
    }

    /**
     * Converts a frozen numeric cell without reading publisher strings.
     * @param node validated inventory cell
     * @return immutable cell baseline evidence
     */
    private static Cell read(final JsonNode node) {
        final var baselines = new ArrayList<Double>();
        for (final var baseline : node.get("native_baselines")) {
            baselines.add(baseline.asDouble());
        }
        return new Cell(node.get("column").asInt(), node.get("row_span").asInt(), node.get("column_span").asInt(),
                List.copyOf(baselines));
    }

    /**
     * Applies one conservative uniform gate without per-source thresholds or logical-row assertions.
     * @param cells every anchor beginning in this captured row, in column order
     * @return retained rejection reason or synchronized candidate status
     */
    private static String classify(final List<Cell> cells) {
        if (cells.stream().anyMatch(cell -> cell.rowSpan() != 1 || cell.columnSpan() != 1)) {
            return "SPANNING_CELL";
        }
        final var occupied = cells.stream().filter(cell -> !cell.baselines().isEmpty()).toList();
        if (occupied.size() < 2) {
            return "INSUFFICIENT_OCCUPIED_COLUMNS";
        }
        final var baseline = occupied.getFirst().baselines();
        if (baseline.size() < 2) {
            return "NO_SHARED_MULTILINE_BASELINES";
        }
        for (final var cell : occupied) {
            if (cell.baselines().size() != baseline.size()) {
                return "UNEQUAL_BASELINE_COUNTS";
            }
            for (int index = 0; index < baseline.size(); index++) {
                if (Math.abs(cell.baselines().get(index) - baseline.get(index)) > 2) {
                    return "MISALIGNED_BASELINES";
                }
            }
        }
        return "SYNCHRONIZED_CANDIDATE";
    }

    /** Exercises synchronization, tolerance, wrapped labels, spanning notes and empty/single-column rejection. */
    private static void controls() {
        final var a = new Cell(0, 1, 1, List.of(10.0, 24.0));
        final var b = new Cell(1, 1, 1, List.of(12.0, 26.0));
        require(classify(List.of(a, b)).equals("SYNCHRONIZED_CANDIDATE"), "Inclusive tolerance rejected");
        require(classify(List.of(a, new Cell(1, 1, 1, List.of(12.01, 26.0)))).equals("MISALIGNED_BASELINES"),
                "Beyond-tolerance accepted");
        require(classify(List.of(a, new Cell(1, 1, 1, List.of(17.0)))).equals("UNEQUAL_BASELINE_COUNTS"),
                "Wrapped label accepted");
        require(classify(List.of(new Cell(0, 1, 3, List.of(10.0, 24.0)), b)).equals("SPANNING_CELL"),
                "Full-width note accepted");
        require(classify(List.of(new Cell(0, 2, 1, List.of(10.0, 24.0)), b)).equals("SPANNING_CELL"),
                "Row span accepted");
        require(classify(List.of(a)).equals("INSUFFICIENT_OCCUPIED_COLUMNS"), "One column accepted");
        require(classify(List.of()).equals("INSUFFICIENT_OCCUPIED_COLUMNS"), "Empty row accepted");
        require(classify(List.of(new Cell(0, 1, 1, List.of(10.0)), b)).equals("NO_SHARED_MULTILINE_BASELINES"),
                "Single baseline accepted");
    }

    /**
     * Identifies an unchanged captured row within a table crop.
     * @param crop original capture crop ordinal
     * @param table model table ordinal
     * @param row model row ordinal
     */
    private record Key(int crop, int table, int row) {}

    /**
     * Retains only numeric captured-cell evidence.
     * @param column model column ordinal
     * @param rowSpan original row span
     * @param columnSpan original column span
     * @param baselines immutable original native baseline cluster starts
     */
    private record Cell(int column, int rowSpan, int columnSpan, List<Double> baselines) {}

    /**
     * Rejects incomplete or changed protocols without recording source text.
     * @param condition required invariant
     * @param message numeric/content-free diagnostic
     */
    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
