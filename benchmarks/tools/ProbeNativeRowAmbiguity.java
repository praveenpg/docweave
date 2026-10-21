package dev.docweave.partition;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** Owned independent counterexample: identical text geometry may describe wrapped cells or separate table rows. */
public final class ProbeNativeRowAmbiguity {
    /** Prevents standalone probe construction. */
    private ProbeNativeRowAmbiguity() {
    }

    /**
     * Tests frozen experimental gates on two owned PDFs with identical glyphs and different explicit painted rows.
     * Neither the independent truth nor paint changes are passed into the frozen baseline/gap algorithms.
     * @param args one fresh numeric report path
     * @throws Exception if authoring, source extraction, protocol controls or fresh output fail
     */
    public static void main(final String[] args) throws Exception {
        require(args.length == 1, "Expected fresh report");
        final var classifier = Class.forName("dev.docweave.validation.AuditSynchronizedModelRows");
        final var cellClass = Class.forName(classifier.getName() + "$Cell");
        final var constructor = cellClass.getDeclaredConstructor(int.class, int.class, int.class, List.class);
        constructor.setAccessible(true);
        final var classify = classifier.getDeclaredMethod("classify", List.class);
        classify.setAccessible(true);
        final var refine = RefineSynchronizedModelRows.class.getDeclaredMethod(
                "separators", List.class, List.class, double.class, double.class, PdfTableWork.class);
        refine.setAccessible(true);
        final var anchors = List.of(new String[] {"0", "0", "0", "0", "1", "1", "30", "100", "200", "160"},
                new String[] {"0", "0", "0", "1", "1", "1", "200", "100", "370", "160"});
        final var results = new ArrayList<Map<String, Object>>();
        List<PdfPageText.Glyph> previous = null;
        for (final boolean divider : List.of(false, true)) {
            final var bytes = source(divider);
            final List<PdfPageText.Glyph> glyphs;
            try (final var document = Loader.loadPDF(bytes)) {
                final var text = new PdfPageText(PdfOptions.defaults());
                text.getText(document);
                glyphs = List.copyOf(text.glyphs());
            }
            if (previous != null) {
                require(previous.equals(glyphs), "Owned text geometry differs between cases");
            }
            previous = glyphs;
            final var evidence = new ArrayList<Object>();
            for (int column = 0; column < 2; column++) {
                final double left = column == 0 ? 30 : 200;
                final double right = column == 0 ? 200 : 370;
                final var baselines =
                        glyphs.stream()
                                .filter(glyph
                                        -> !glyph.text().isBlank() && glyph.x() + glyph.width() / 2 >= left
                                                   && glyph.x() + glyph.width() / 2 < right)
                                .map(glyph -> (double) glyph.y())
                                .distinct()
                                .sorted()
                                .toList();
                require(baselines.size() == 2, "Owned cell lacks two source baselines");
                evidence.add(constructor.newInstance(column, 1, 1, baselines));
            }
            final String gate = (String) classify.invoke(null, evidence);
            @SuppressWarnings("unchecked")
            final var separators = (List<Double>) refine.invoke(null, anchors, glyphs, 1.0, 1.0, new PdfTableWork());
            require(gate.equals("SYNCHRONIZED_CANDIDATE") && separators.size() == 1,
                    "Frozen candidate behavior changed; preserve counterexample before evaluating a new policy");
            results.add(Map.of("case", divider ? "two_painted_rows" : "single_row_wrapped_cells", "owned_pdf_sha256",
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),
                    "authored_logical_rows", divider ? 2 : 1, "gate", gate, "experimental_source_gap_separators",
                    separators.size(), "would_emit_rows", separators.size() + 1, "false_split_under_authored_truth",
                    !divider));
        }
        Files.writeString(Path.of(args[0]),
                JsonMapper.builder().build().writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("scope",
                        "Owned independent semantic ambiguity control; no production change or original-source accuracy score",
                        "runtime", Runtime.version().toString(), "same_native_text_and_geometry", true, "results",
                        results))
                        + "\n",
                StandardOpenOption.CREATE_NEW);
    }

    /**
     * Authors a one-row/two-cell wrapped table or two painted rows with the exact same source glyph positions.
     * @param divider whether an explicit horizontal divider creates two logical rows
     * @return complete owned PDF bytes
     * @throws Exception if source authoring fails
     */
    private static byte[] source(final boolean divider) throws Exception {
        try (final var document = new PDDocument(); final var bytes = new ByteArrayOutputStream()) {
            final var page = new PDPage(new PDRectangle(400, 400));
            document.addPage(page);
            try (final var content = new PDPageContentStream(document, page)) {
                content.addRect(30, 240, 340, 60);
                content.moveTo(200, 240);
                content.lineTo(200, 300);
                if (divider) {
                    content.moveTo(30, 275);
                    content.lineTo(370, 275);
                }
                content.stroke();
                final var font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
                final var words = List.of("Billing", "Home", "address", "address");
                for (int index = 0; index < words.size(); index++) {
                    content.beginText();
                    content.setFont(font, 10);
                    content.newLineAtOffset(index % 2 == 0 ? 40 : 210, index < 2 ? 285 : 265);
                    content.showText(words.get(index));
                    content.endText();
                }
            }
            document.save(bytes);
            return bytes.toByteArray();
        }
    }

    /**
     * Rejects unexpected controls without adapting thresholds to make them pass.
     * @param condition required frozen behavior
     * @param message content-free diagnostic
     */
    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
