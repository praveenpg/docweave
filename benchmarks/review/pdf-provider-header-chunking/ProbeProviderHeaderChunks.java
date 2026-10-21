package dev.docweave.chunking;

import dev.docweave.core.Element;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Numeric-only before/after probe over the same independently authored integration fixture. */
public final class ProbeProviderHeaderChunks {
    /** Prevents standalone utility construction. */
    private ProbeProviderHeaderChunks() {
    }

    /**
     * Extracts every native/word/cell route with declared and unknown headers using the same fixture implementation.
     * No runtime-selected winner or score/text repair is used. Reflection reuses the owned source authoring exactly.
     * @param args NEW numeric report path
     * @throws Exception if fixture invocation, complete populations or fresh-output requirements fail
     */
    public static void main(final String[] args) throws Exception {
        final var fixture = Class.forName("dev.docweave.chunking.PdfProviderHeaderChunkTest");
        final var route = Class.forName("dev.docweave.chunking.PdfProviderHeaderChunkTest$Route");
        final var extract = fixture.getDeclaredMethod("extract", route, int.class);
        extract.setAccessible(true);
        final var rows = new ArrayList<Map<String, Object>>();
        for (final var selected : route.getEnumConstants()) {
            for (final int headers : List.of(0, 1)) {
                final var source = (Element) extract.invoke(null, selected, headers);
                final var chunks =
                        Chunker.create().basic(List.of(source), ChunkOptions.builder().maxCharacters(180).build());
                final long repeated =
                        chunks.stream()
                                .filter(chunk
                                        -> ((Number) chunk.metadata().get("num_carried_over_header_rows")).intValue()
                                                   == 1)
                                .count();
                int uniqueBodyRows = 0;
                for (int index = 1; index < 7; index++) {
                    final String body = "Row" + index + "BodyValue";
                    if (chunks.stream().filter(chunk -> chunk.text().contains(body)).count() == 1) {
                        uniqueBodyRows++;
                    }
                }
                rows.add(Map.of("route", selected.toString(), "declared_header_rows", headers,
                        "source_neutral_header_rows", source.metadata().getOrDefault("table_leading_header_rows", 0),
                        "chunks", chunks.size(), "continuation_chunks_with_one_repeated_header", repeated,
                        "body_rows_present_exactly_once", uniqueBodyRows, "body_row_denominator", 6));
            }
        }
        if (rows.size() != 6) {
            throw new AssertionError("Incomplete fixture population");
        }
        Files.writeString(Path.of(args[0]),
                JsonMapper.builder().build().writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("scope",
                        "Owned seven-row source, all three provider text routes and both declarations; no original-source accuracy claim",
                        "runtime", Runtime.version().toString(), "results", rows))
                        + "\n",
                StandardOpenOption.CREATE_NEW);
    }
}
