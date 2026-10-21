package dev.docweave.validation;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Source-only structure inventory, without DocWeave extraction or source caption/alternative text in reports. */
public final class InventorySourceFigureRoles {
    /** Prevents standalone utility construction. */
    private InventorySourceFigureRoles() {
    }

    /**
     * Verifies every selected original and inventories structural roles before any product evaluation.
     * The source author pass designation is not interpreted as image/caption ground truth.
     * @param args frozen source-selection manifest, local cache and NEW numeric report
     * @throws Exception if original lengths/SHA/Git identities, traversal limits or fresh output checks fail
     */
    public static void main(final String[] args) throws Exception {
        controls();
        require(args.length == 3 && !Files.exists(Path.of(args[2])), "Expected frozen manifest, cache and NEW report");
        final var mapper = JsonMapper.builder().build();
        final var protocol = Files.readAllBytes(Path.of(args[0]));
        final var manifest = mapper.readTree(protocol);
        final var results = new ArrayList<Map<String, Object>>();
        for (final var input : manifest.get("inputs")) {
            final var filename = Path.of(input.get("path").asString()).getFileName();
            require(filename.toString().matches("[0-9.a-z-]+\\.pdf"), "Unsafe source filename");
            final var path = Path.of(args[1]).resolve(filename);
            require(Files.size(path) <= 1024 * 1024 && Files.size(path) == input.get("size").asLong(),
                    "Source length changed");
            final var bytes = Files.readAllBytes(path);
            final var blob = MessageDigest.getInstance("SHA-1");
            blob.update(("blob " + bytes.length + "\0").getBytes(StandardCharsets.US_ASCII));
            require(HexFormat.of().formatHex(blob.digest(bytes)).equals(input.get("sha").asString()),
                    "Original Git blob changed");
            final var counts = new TreeMap<String, Integer>();
            try (final var document = Loader.loadPDF(bytes)) {
                require(document.getNumberOfPages() <= 4, "Source exceeds discovery page limit");
                final var root = document.getDocumentCatalog().getStructureTreeRoot();
                if (root != null) {
                    walk(root.getCOSObject().getDictionaryObject(COSName.K), "ROOT", false,
                            root.getCOSObject().getCOSDictionary(COSName.ROLE_MAP), counts,
                            Collections.newSetFromMap(new IdentityHashMap<>()), 0);
                }
                results.add(Map.of("filename", filename.toString(), "source_sha256", hash(bytes), "git_blob_sha1",
                        input.get("sha").asString(), "pages", document.getNumberOfPages(), "role_counts", counts));
            }
        }
        Files.writeString(Path.of(args[2]),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("scope",
                        "Complete source-only role inventory; no DocWeave extraction, reference caption strings, raster inference or output-dependent selection",
                        "runtime", Runtime.version().toString(), "selection_sha256", hash(protocol), "results",
                        results))
                        + "\n",
                StandardOpenOption.CREATE_NEW);
    }

    /** Checks alias resolution, repeated child ownership and Figure/Caption ancestry without source text. */
    private static void controls() {
        final var figure = new COSDictionary();
        figure.setName(COSName.S, "CustomFigure");
        figure.setString(COSName.ALT, "Owned alternative, not a caption");
        final var caption = new COSDictionary();
        caption.setName(COSName.S, "Caption");
        final var children = new COSArray();
        children.add(caption);
        children.add(caption);
        figure.setItem(COSName.K, children);
        final var roleMap = new COSDictionary();
        roleMap.setName(COSName.getPDFName("CustomFigure"), "Figure");
        final var counts = new TreeMap<String, Integer>();
        walk(figure, "ROOT", false, roleMap, counts, Collections.newSetFromMap(new IdentityHashMap<>()), 0);
        require(counts.equals(Map.of("role:Figure", 1, "figure_with_Alt_key", 1, "role:Caption", 1,
                        "caption_parent:Figure:figure_ancestor:true", 1)),
                "Alias/ancestry/repeated-source control failed");
        roleMap.setName(COSName.getPDFName("CustomFigure"), "CustomFigure");
        boolean rejected = false;
        try {
            walk(figure, "ROOT", false, roleMap, new TreeMap<>(), Collections.newSetFromMap(new IdentityHashMap<>()),
                    0);
        } catch (final IllegalArgumentException expected) {
            rejected = true;
        }
        require(rejected, "Source role cycle accepted");
    }

    /**
     * Traverses only structure children with bounded role aliases, depth and unique source objects.
     * Repeated objects are counted once; no source strings are read as captions or alternative text.
     * @param value original structure child
     * @param parent nearest structural parent role
     * @param inFigure whether a source Figure ancestor is present
     * @param roleMap original root role aliases, possibly absent
     * @param counts numeric role/property counts
     * @param seen unique source object identities
     * @param depth current source traversal depth
     */
    private static void walk(final COSBase value, final String parent, final boolean inFigure,
            final COSDictionary roleMap, final Map<String, Integer> counts, final Set<COSBase> seen, final int depth) {
        if (!(value instanceof COSArray || value instanceof COSDictionary)) {
            return;
        }
        require(depth <= 128 && seen.size() <= 200_000, "Source structure exceeds discovery limits");
        if (!seen.add(value)) {
            return;
        }
        if (value instanceof COSArray array) {
            for (int index = 0; index < array.size(); index++) {
                walk(array.getObject(index), parent, inFigure, roleMap, counts, seen, depth + 1);
            }
        } else if (value instanceof COSDictionary node) {
            String role = node.getNameAsString(COSName.S);
            final var aliases = new HashSet<String>();
            while (role != null && roleMap != null
                    && roleMap.getDictionaryObject(COSName.getPDFName(role)) instanceof COSName mapped) {
                require(aliases.add(role) && aliases.size() <= 16, "Source role alias cycle/limit");
                role = mapped.getName();
            }
            if (role != null) {
                counts.merge("role:" + role, 1, Integer::sum);
                if (role.equals("Caption")) {
                    counts.merge("caption_parent:" + parent + ":figure_ancestor:" + inFigure, 1, Integer::sum);
                }
                if (role.equals("Figure")) {
                    if (node.containsKey(COSName.ALT)) {
                        counts.merge("figure_with_Alt_key", 1, Integer::sum);
                    }
                    if (node.containsKey(COSName.ACTUAL_TEXT)) {
                        counts.merge("figure_with_ActualText_key", 1, Integer::sum);
                    }
                }
            }
            walk(node.getDictionaryObject(COSName.K), role == null ? parent : role, inFigure || "Figure".equals(role),
                    roleMap, counts, seen, depth + 1);
        }
    }

    /**
     * Hashes complete source/protocol bytes without reporting their content.
     * @param bytes exact local evidence
     * @return lowercase SHA256
     * @throws Exception if the digest is unavailable
     */
    private static String hash(final byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /**
     * Rejects altered source inputs or exceeded discovery bounds.
     * @param condition required invariant
     * @param message content-free diagnostic
     */
    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
