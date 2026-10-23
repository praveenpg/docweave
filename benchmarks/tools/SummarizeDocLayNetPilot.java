package dev.docweave.layout;
import java.nio.file.*;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;

/** Replays detector reports against independently supplied rectangles. */
public final class SummarizeDocLayNetPilot {
    /** Prevents construction. */
    private SummarizeDocLayNetPilot() {
    }

    /**
     * Recomputes matches and summarizes all pages.
     * @param args selection and two reports
     * @throws Exception if reports differ from recomputed counts
     */
    public static void main(final String[] args) throws Exception {
        final var mapper = JsonMapper.builder().build();
        final var selection = mapper.readTree(Files.readAllBytes(Path.of(args[0])));
        final var result = new LinkedHashMap<String, Object>();
        for (int k = 1; k < args.length; k++) {
            final var report = mapper.readTree(Files.readAllBytes(Path.of(args[k])));
            final var sums = new TreeMap<String, int[]>();
            if (report.get("pages").size() != 24)
                throw new AssertionError("Incomplete run");
            for (int i = 0; i < 24; i++) {
                final var p = report.get("pages").get(i);
                final var ref = selection.get("pages").get(i);
                if (!p.get("id").asString().equals(ref.get("id").asString()))
                    throw new AssertionError("Page order");
                final var truth = new ArrayList<double[]>();
                for (final var b : ref.get("tables"))
                    truth.add(new double[] {
                            b.get(0).asDouble(), b.get(1).asDouble(), b.get(2).asDouble(), b.get(3).asDouble()});
                final boolean ok = p.get("status").asString().equals("ok");
                int tp = 0, fp = 0, fn = truth.size();
                if (ok) {
                    final var pred = new ArrayList<double[]>();
                    for (final var b : p.get("predictions"))
                        pred.add(new double[] {
                                b.get(0).asDouble(), b.get(1).asDouble(), b.get(2).asDouble(), b.get(3).asDouble()});
                    tp = EvaluateDocLayNetProposals.matches(pred, truth);
                    fp = pred.size() - tp;
                    fn = truth.size() - tp;
                    if (tp != p.get("tp").asInt() || fp != p.get("fp").asInt() || fn != p.get("fn").asInt())
                        throw new AssertionError("Scoring mismatch");
                }
                for (final var key : List.of("all", p.get("category").asString(), p.get("group").asString())) {
                    final var s = sums.computeIfAbsent(key, unused -> new int[7]);
                    s[0]++;
                    s[1] += tp;
                    s[2] += fp;
                    s[3] += fn;
                    s[4] += ok ? 0 : 1;
                    s[5] += truth.isEmpty() ? 1 : 0;
                    s[6] += truth.isEmpty() ? fp : 0;
                }
            }
            final var groups = new TreeMap<String, Object>();
            for (final var e : sums.entrySet()) {
                final var s = e.getValue();
                groups.put(e.getKey(), Map.of("pages", s[0], "tp", s[1], "known_fp", s[2], "fn", s[3], "errors", s[4],
                                               "negative_pages", s[5], "negative_fp", s[6]));
            }
            result.put(report.get("detector").asString(), groups);
        }
        System.out.println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result));
    }
}
