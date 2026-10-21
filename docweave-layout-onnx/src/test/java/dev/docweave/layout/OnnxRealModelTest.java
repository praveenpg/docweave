package dev.docweave.layout;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import javax.imageio.ImageIO;

import static org.junit.jupiter.api.Assertions.*;

/** Explicit local model smoke probe; output counts are diagnostics, never an accuracy assertion. */
class OnnxRealModelTest {
    @Test
    void explicitPinnedModelProcessesWholeImages() throws Exception {
        final var model = System.getProperty("docweave.onnx.model");
        if (model == null) {
            return;
        }
        final var sha = System.getProperty("docweave.onnx.sha256");
        final var directory = Path.of(System.getProperty("docweave.onnx.images"));
        final var report = Path.of(System.getProperty("docweave.onnx.report"));
        assertFalse(Files.exists(report));
        final var rows = new ArrayList<String>();
        rows.add("input\ttables\trows\tcolumns\tanchors");
        try (final var provider = OnnxTableLayout.create(OnnxTableOptions.defaults(Path.of(model), sha));
                final var files = Files.walk(directory)) {
            final var images = files.filter(path -> path.toString().endsWith(".png")).sorted().toList();
            assertEquals(20, images.size());
            for (final var path : images) {
                final var image = ImageIO.read(path.toFile());
                assertNotNull(image);
                try {
                    final var tables = provider.locate(image);
                    rows.add(path.getFileName() + "\t" + tables.size() + "\t"
                             + tables.stream().map(table -> "" + table.rows()).toList() + "\t"
                             + tables.stream().map(table -> "" + table.columns()).toList() + "\t"
                             + tables.stream().map(table -> "" + table.anchors().size()).toList());
                } finally {
                    image.flush();
                }
            }
        }
        Files.write(report, rows, StandardOpenOption.CREATE_NEW);
    }
}
