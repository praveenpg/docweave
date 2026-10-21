import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;

/** Checks complete original-source default output without excluding IDs, content, metadata or diagnostics. */
public final class VerifyDefaultReplay {
    /** Prevents utility construction. */
    private VerifyDefaultReplay() {
    }

    /**
     * Requires exact parsed public captures for all nine fixed sources, plus unchanged independent raster inventories.
     * @param args frozen manifest, original source cache, baseline captures, current captures and fresh numeric report
     * @throws Exception if source identity, complete output equality or new-output invariants fail
     */
    public static void main(final String[] args) throws Exception {
        require(args.length == 5);
        final var mapper = JsonMapper.builder().build();
        final var manifestPath = Path.of(args[0]);
        final var manifest = mapper.readTree(Files.readAllBytes(manifestPath));
        require(manifest.get("cases").size() == 9);
        final var results = new ArrayList<Map<String, Object>>();
        final var seen = new HashSet<String>();
        int ordinary = 0;
        int stencils = 0;
        for (final var selected : manifest.get("cases")) {
            final String id = selected.get("id").asString();
            require(seen.add(id));
            final var source = Path.of(args[1], selected.get("pdf").asString());
            require(Files.size(source) == selected.get("bytes").asLong()
                    && hash(source).equals(selected.get("sha256").asString()));
            final var baseline = Path.of(args[2], id + ".json");
            final var current = Path.of(args[3], id + ".json");
            final var actual = mapper.readTree(Files.readAllBytes(current));
            require(actual.equals(mapper.readTree(Files.readAllBytes(baseline))));
            require(actual.get("source_sha256").equals(selected.get("sha256"))
                    && actual.get("outcome").asString().equals("SUCCESS"));
            final var inventory = mapper.readTree(Files.readAllBytes(Path.of(args[3], id + "-images.json")));
            require(inventory.equals(mapper.readTree(Files.readAllBytes(Path.of(args[2], id + "-images.json")))));
            int images = 0;
            for (final var image : inventory.get("images")) {
                if (image.get("kind").asString().equals("image")) {
                    images++;
                    ordinary++;
                } else if (image.get("kind").asString().equals("stencil")) {
                    stencils++;
                }
            }
            results.add(Map.of("case", id, "source_sha256", selected.get("sha256").asString(),
                    "complete_public_capture_equal", true, "independent_raster_inventory_equal", true,
                    "ordinary_raster_placements", images, "baseline_capture_sha256", hash(baseline),
                    "current_capture_sha256", hash(current)));
        }
        require(ordinary == 33);
        Files.writeString(Path.of(args[4]),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("runtime",
                        Runtime.version().toString(), "manifest_sha256", hash(manifestPath), "complete_original_cases",
                        results.size(), "ordinary_raster_placements", ordinary, "source_stencil_entries", stencils,
                        "scope",
                        "Complete parsed default-output equality; payloads disabled; not external stencil accuracy evidence",
                        "results", results))
                        + "\n",
                StandardOpenOption.CREATE_NEW);
    }

    /**
     * Hashes complete locally pinned source/capture bytes, without exporting any source content.
     * @param path local source or numeric identity input
     * @return lowercase SHA256
     * @throws Exception if bytes or digest cannot be read
     */
    private static String hash(final Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }

    /**
     * Rejects a missing source, changed output or incomplete observation population.
     * @param valid required invariant
     */
    private static void require(final boolean valid) {
        if (!valid) {
            throw new IllegalArgumentException("Default PDF image regression verification failed");
        }
    }
}
