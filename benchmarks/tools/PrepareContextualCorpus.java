import io.docweave.DocWeave;
import io.docweave.chunking.ChunkOptions;
import io.docweave.chunking.Chunker;
import io.docweave.core.Element;
import io.docweave.json.ElementJson;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Authors a small cross-format retrieval corpus and freezes source-based labels before any model execution. */
public final class PrepareContextualCorpus {
    /** Prevents utility construction. */
    private PrepareContextualCorpus() {
    }

    /**
     * Authors three distinct fictional documents and twelve independently authored source questions.
     * This is an owned integration corpus, not independently reviewed real-world relevance ground truth.
     * @param args fresh output directory, or --collect and the previously frozen corpus directory
     * @throws Exception if source creation, actual partitioning or relevance preflight fails
     */
    public static void main(final String[] args) throws Exception {
        if (args.length == 2 && args[0].equals("--collect")) {
            collect(Path.of(args[1]));
            return;
        }
        if (args.length != 1) {
            throw new IllegalArgumentException("Expected fresh owned corpus directory");
        }
        final var root = Path.of(args[0]);
        Files.createDirectory(root);
        final var documents = List.of(
                new Source("cedar.pdf", "Cedar Instruments fiscal 2026 report",
                        List.of(new Section("Revenue",
                                        "Cedar Instruments earned 48 million dollars in fiscal 2026. "
                                                + "Its laboratory sensor division earned 31 million dollars and calibration services earned 17 million dollars. "
                                                + "This is a fictional owned test report. Values describe fiscal 2026 only."),
                                new Section("Operations",
                                        "Cedar Instruments operates a factory in Tucson. "
                                                + "A second factory will open in Reno in September 2027. No revenue forecast for 2027 is provided. "
                                                + "The planned factory is not operating during the reporting year."),
                                new Section("Risk",
                                        "Cedar Instruments identifies ceramic substrate shortages as its main supply risk. "
                                                + "The mitigation is a six-month inventory reserve. The reserve covers substrates rather than finished sensors.")),
                        List.of(new Question("What was Cedar Instruments revenue in fiscal 2026?", "48 million"),
                                new Question("How much did Cedar Instruments calibration services earn?", "17 million"),
                                new Question("Where and when will Cedar Instruments open its second factory?", "Reno"),
                                new Question(
                                        "How does Cedar Instruments mitigate its main supply risk?", "six-month"))),
                new Source("harbor.docx", "Harbor Materials fiscal 2026 report",
                        List.of(new Section("Revenue",
                                        "Harbor Materials earned 84 million dollars in fiscal 2026. "
                                                + "Its recycled glass division earned 52 million dollars and logistics services earned 32 million dollars. "
                                                + "This is a fictional owned test report. Values describe fiscal 2026 only."),
                                new Section("Operations",
                                        "Harbor Materials operates a plant in Portland. "
                                                + "A second plant will open in Boise in March 2028. No revenue forecast for 2028 is provided. "
                                                + "The planned plant is not operating during the reporting year."),
                                new Section("Risk",
                                        "Harbor Materials identifies river transport interruptions as its main supply risk. "
                                                + "The mitigation is a rail freight agreement. The agreement covers incoming glass rather than customer deliveries.")),
                        List.of(new Question("What was Harbor Materials revenue in fiscal 2026?", "84 million"),
                                new Question("How much did Harbor Materials logistics services earn?", "32 million"),
                                new Question("Where and when will Harbor Materials open its second plant?", "Boise"),
                                new Question(
                                        "How does Harbor Materials mitigate its main supply risk?", "rail freight"))),
                new Source("lumen.html", "Lumen Research battery study 2026",
                        List.of(new Section("Method",
                                        "Lumen Research tested sodium-ion cells at 25 degrees Celsius. "
                                                + "The experiment used 60 cells, split equally between a coated group and an untreated control group. "
                                                + "This is a fictional owned scientific study, not investment or engineering advice."),
                                new Section("Results",
                                        "Lumen Research observed 92 percent capacity retention in the coated group "
                                                + "after 800 cycles. The untreated control retained 76 percent capacity under the same test conditions. "
                                                + "Capacity was measured relative to each cell's initial capacity."),
                                new Section("Limitations",
                                        "Lumen Research did not test cells below zero degrees Celsius. "
                                                + "The team plans a cold-weather trial in January 2027. The current experiment cannot establish winter performance.")),
                        List.of(new Question("How many cells were used in the Lumen Research experiment?", "60 cells"),
                                new Question("What capacity did the coated Lumen cells retain after 800 cycles?",
                                        "92 percent"),
                                new Question("What capacity did the untreated Lumen control retain?", "76 percent"),
                                new Question(
                                        "When will Lumen Research test cold-weather performance?", "January 2027"))));
        final var originals = new ArrayList<Element>();
        final var queries = new ArrayList<Map<String, Object>>();
        final var inventory = new ArrayList<Map<String, Object>>();
        for (final var source : documents) {
            final var path = root.resolve(source.filename());
            author(path, source);
            final var elements = DocWeave.create().partition(path).elements();
            final var chunks = Chunker.create().byTitle(elements, ChunkOptions.builder()
                                                                          .maxCharacters(1500)
                                                                          .newAfterCharacters(1000)
                                                                          .combineTextUnderCharacters(0)
                                                                          .build());
            if (chunks.isEmpty()) {
                throw new IllegalStateException("Owned source produced no chunks");
            }
            final var json = JsonMapper.builder().build();
            final var frozen = json.createObjectNode();
            frozen.put("document_text", elements.stream()
                                                .filter(element -> !element.text().isBlank())
                                                .map(Element::text)
                                                .collect(java.util.stream.Collectors.joining("\n\n")));
            frozen.set("original_chunks", json.readTree(ElementJson.create().write(chunks)));
            Files.write(root.resolve(source.filename() + "-frozen.json"), json.writeValueAsBytes(frozen),
                    StandardOpenOption.CREATE_NEW);
            originals.addAll(chunks);
            for (final var question : source.questions()) {
                final var relevant = chunks.stream()
                                             .filter(chunk -> chunk.text().contains(question.anchor()))
                                             .map(Element::elementId)
                                             .toList();
                if (relevant.size() != 1) {
                    throw new IllegalStateException("Expected one exact source-supported relevance chunk");
                }
                queries.add(Map.of("id", "corpus-" + (queries.size() + 1), "text", question.text(),
                        "relevant_chunk_ids", relevant));
            }
            inventory.add(Map.of("source", source.filename(), "chunks", chunks.size()));
        }
        final var json = JsonMapper.builder().build();
        Files.writeString(root.resolve("original-chunks.json"), ElementJson.create().write(originals),
                StandardOpenOption.CREATE_NEW);
        Files.write(root.resolve("queries.json"), json.writerWithDefaultPrettyPrinter().writeValueAsBytes(queries),
                StandardOpenOption.CREATE_NEW);
        Files.write(root.resolve("inventory.json"), json.writerWithDefaultPrettyPrinter().writeValueAsBytes(inventory),
                StandardOpenOption.CREATE_NEW);
        System.out.println("Frozen " + originals.size() + " chunks and " + queries.size()
                           + " source-supported questions before model generation: " + inventory);
    }

    /**
     * Collects unchanged frozen populations without regenerating source IDs or repairing reference labels.
     * @param root previously frozen owned corpus directory
     * @throws Exception if original identities, generation configuration or model digests differ
     */
    private static void collect(final Path root) throws Exception {
        final var json = JsonMapper.builder().build();
        final var originals = json.createArrayNode();
        final var contextual = json.createArrayNode();
        final var configuration = json.createArrayNode();
        for (final var item : json.readTree(read(root.resolve("inventory.json")))) {
            final String filename = item.get("source").asString();
            if (!List.of("cedar.pdf", "harbor.docx", "lumen.html").contains(filename)) {
                throw new IllegalArgumentException("Unexpected owned source identity");
            }
            final var report = json.readTree(read(root.resolve(filename + "-context.json")));
            final var identity = json.createObjectNode();
            for (final var field : List.of("model", "model_digest", "context_window", "temperature",
                         "maximum_generated_tokens", "thinking")) {
                if (!report.has(field)) {
                    throw new IllegalArgumentException("Missing generation identity/configuration");
                }
                identity.set(field, report.get(field));
            }
            if (!configuration.isEmpty() && !configuration.get(0).equals(identity)) {
                throw new IllegalArgumentException("Generation model/configuration drift");
            }
            configuration.add(identity);
            for (final var chunk : report.get("original_chunks")) {
                originals.add(chunk);
            }
            for (final var chunk : report.get("contextual_chunks")) {
                contextual.add(chunk);
            }
        }
        if (!originals.equals(json.readTree(read(root.resolve("original-chunks.json"))))
                || originals.size() != contextual.size() || originals.isEmpty()) {
            throw new IllegalArgumentException("Original frozen chunk population changed");
        }
        final var aggregate = json.createObjectNode();
        aggregate.set("original_chunks", originals);
        aggregate.set("contextual_chunks", contextual);
        aggregate.set("generation_configuration", configuration.get(0));
        Files.write(root.resolve("aggregate-context.json"), json.writeValueAsBytes(aggregate),
                StandardOpenOption.CREATE_NEW);
    }

    /**
     * Reads bounded local experiment artifacts without changing their identities.
     * @param path complete owned source artifact
     * @return up to16MiB original bytes
     * @throws Exception if the source is missing or exceeds its encoded ceiling
     */
    private static byte[] read(final Path path) throws Exception {
        try (final var input = Files.newInputStream(path)) {
            final byte[] raw = input.readNBytes(16 * 1024 * 1024 + 1);
            if (raw.length > 16 * 1024 * 1024) {
                throw new IllegalArgumentException("Owned artifact exceeds16MiB");
            }
            return raw;
        }
    }

    /**
     * Authors actual native PDF/DOCX/HTML sources with heading and body separation.
     * @param path fresh source output
     * @param source owned title and sections
     * @throws Exception if native document encoding fails
     */
    private static void author(final Path path, final Source source) throws Exception {
        if (source.filename().endsWith(".pdf")) {
            try (final var document = new PDDocument()) {
                final var page = new PDPage();
                document.addPage(page);
                try (final var stream = new PDPageContentStream(document, page)) {
                    stream.beginText();
                    stream.newLineAtOffset(40, 740);
                    line(stream, source.title(), 16);
                    for (final var section : source.sections()) {
                        line(stream, section.title(), 14);
                        // Keep source lines within the page without using DocWeave's extraction to lay them out.
                        final var words = section.body().split(" ");
                        final var text = new StringBuilder();
                        for (final var word : words) {
                            if (text.length() + word.length() > 82) {
                                line(stream, text.toString(), 11);
                                text.setLength(0);
                            }
                            if (!text.isEmpty()) {
                                text.append(' ');
                            }
                            text.append(word);
                        }
                        line(stream, text.toString(), 11);
                        stream.newLineAtOffset(0, -12);
                    }
                    stream.endText();
                }
                document.save(path.toFile());
            }
        } else if (source.filename().endsWith(".docx")) {
            try (final var document = new XWPFDocument()) {
                final var title = document.createParagraph();
                title.setStyle("Heading1");
                title.createRun().setText(source.title());
                for (final var section : source.sections()) {
                    final var heading = document.createParagraph();
                    heading.setStyle("Heading2");
                    heading.createRun().setText(section.title());
                    document.createParagraph().createRun().setText(section.body());
                }
                try (final var output = Files.newOutputStream(path, StandardOpenOption.CREATE_NEW)) {
                    document.write(output);
                }
            }
        } else {
            final var html =
                    new StringBuilder("<!doctype html><html><body><h1>").append(source.title()).append("</h1>");
            for (final var section : source.sections()) {
                // Fixture strings are authored ASCII with no HTML syntax or external inputs.
                html.append("<h2>").append(section.title()).append("</h2><p>").append(section.body()).append("</p>");
            }
            Files.writeString(path, html.append("</body></html>").toString(), StandardOpenOption.CREATE_NEW);
        }
    }

    /**
     * Paints a single independently positioned source line.
     * @param stream owned PDF source stream
     * @param text authored ASCII text
     * @param size explicit source font size
     * @throws Exception if painting fails
     */
    private static void line(final PDPageContentStream stream, final String text, final int size) throws Exception {
        stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), size);
        stream.showText(text);
        stream.newLineAtOffset(0, -20);
    }

    /**
     * One owned native source with pre-authored questions; no generated answers or ranks enter its definition.
     * @param filename unique native source filename
     * @param title document title
     * @param sections original heading/body sections
     * @param questions original query text and source-only relevance anchors
     */
    private record Source(String filename, String title, List<Section> sections, List<Question> questions) {}

    /**
     * Source text authored independently of model output.
     * @param title original heading
     * @param body original paragraph
     */
    private record Section(String title, String body) {}

    /**
     * Question fixed before generation with an exact original-source relevance anchor.
     * @param text authored query
     * @param anchor source phrase expected in exactly one original chunk
     */
    private record Question(String text, String anchor) {}
}
