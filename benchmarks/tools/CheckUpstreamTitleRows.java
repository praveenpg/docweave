package io.docweave.validation;

import org.jsoup.Jsoup;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Locates each NVIDIA reference statement's four leading title rows in retained pinned Unstructured hi_res output.
 *
 * <p>Reads the retained upstream element JSON only; nothing is executed. A title row is counted inside an upstream
 * table when its whitespace-collapsed, lower-cased text occurs in a Table element's text or HTML text on the case's
 * pages, otherwise in non-table elements, otherwise absent. Reports contain counts and hashes only.
 */
public final class CheckUpstreamTitleRows {
    /** Prevents construction. */
    private CheckUpstreamTitleRows() {
    }

    /**
     * Writes the placement counts.
     *
     * @param args reference manifest, issuer HTML, retained upstream JSON and fresh report
     * @throws Exception if reading or writing fails
     */
    public static void main(final String[] args) throws Exception {
        if (args.length != 4) {
            throw new IllegalArgumentException("manifest html upstream report");
        }
        final var json = JsonMapper.builder().build();
        final JsonNode manifest = json.readTree(Files.readAllBytes(Path.of(args[0])));
        final var tables = Jsoup.parse(Files.readString(Path.of(args[1]), StandardCharsets.UTF_8)).select("table");
        final byte[] raw = Files.readAllBytes(Path.of(args[2]));
        final JsonNode upstream = json.readTree(raw);
        final JsonNode elements = upstream.isArray() ? upstream : upstream.get("elements");
        final var cases = new ArrayList<Map<String, Object>>();
        for (final var selected : manifest.get("cases")) {
            final var table = tables.get(selected.get("html_table_index").asInt());
            final var titles = new ArrayList<String>();
            for (final var row : table.select("tr")) {
                final String text = normalize(String.join(" ", row.select("td, th").eachText()));
                if (!text.isEmpty() && titles.size() < 4) {
                    titles.add(text);
                }
            }
            final var pages = new HashSet<Integer>();
            selected.get("regions").forEach(region -> pages.add(region.get("page").asInt()));
            int inside = 0;
            int other = 0;
            int absent = 0;
            for (final String title : titles) {
                boolean inTable = false;
                boolean inOther = false;
                for (final var element : elements) {
                    final var metadata = element.get("metadata");
                    if (!pages.contains(metadata.get("page_number").asInt())) {
                        continue;
                    }
                    final String text = normalize(element.get("text").asString());
                    final String html = metadata.has("text_as_html")
                                                ? normalize(Jsoup.parse(metadata.get("text_as_html").asString()).text())
                                                : "";
                    if (element.get("type").asString().equals("Table")) {
                        inTable |= text.contains(title) || html.contains(title);
                    } else {
                        inOther |= text.contains(title);
                    }
                }
                inside += inTable ? 1 : 0;
                other += !inTable && inOther ? 1 : 0;
                absent += !inTable && !inOther ? 1 : 0;
            }
            final var row = new LinkedHashMap<String, Object>();
            row.put("case", selected.get("id").asString());
            row.put("title_rows", titles.size());
            row.put("inside_upstream_table", inside);
            row.put("only_in_non_table_elements", other);
            row.put("absent", absent);
            cases.add(row);
            System.out.println(row);
        }
        final var report = new LinkedHashMap<String, Object>();
        report.put("scope", "Retained pinned upstream hi_res output, no execution: placement of the four reference "
                                    + "title rows per statement; counts only");
        report.put(
                "upstream_output_sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw)));
        report.put("java_version", System.getProperty("java.version"));
        report.put("cases", cases);
        Files.writeString(Path.of(args[3]), json.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n",
                StandardOpenOption.CREATE_NEW);
    }

    /**
     * Collapses whitespace and lower-cases.
     *
     * @param text value
     * @return normalized value
     */
    private static String normalize(final String text) {
        return text.replaceAll("\\s+", " ").strip().toLowerCase(Locale.ROOT);
    }
}
