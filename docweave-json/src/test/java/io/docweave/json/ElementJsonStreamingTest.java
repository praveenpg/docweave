package io.docweave.json;

import io.docweave.core.Element;
import io.docweave.core.ElementType;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Per-element streaming encoding is byte-identical to the element's object inside the array encoding. */
class ElementJsonStreamingTest {
    /** Codec under test. */
    private final ElementJson codec = ElementJson.create();

    /** Creates the test. */
    ElementJsonStreamingTest() {
    }

    /**
     * A generator stream of single elements equals the array encoding byte for byte.
     * @throws Exception if encoding fails
     */
    @Test
    void generatorStreamEqualsArrayEncoding() throws Exception {
        final var elements = elements();
        final byte[] expected = codec.write(elements).getBytes(StandardCharsets.UTF_8);
        final var bytes = new ByteArrayOutputStream();
        try (final var generator = JsonMapper.builder().build().createGenerator(bytes)) {
            generator.writeStartArray();
            for (final var element : elements) {
                codec.write(element, generator);
            }
            generator.writeEndArray();
        }
        assertArrayEquals(expected, bytes.toByteArray());
    }

    /**
     * The generator form never flushes or closes the caller's generator or stream.
     * @throws Exception if encoding fails
     */
    @Test
    void generatorFormDoesNotFlush() throws Exception {
        final var flushes = new int[1];
        final var bytes = new ByteArrayOutputStream();
        final var target = new OutputStream() {
            @Override
            public void write(final int value) {
                bytes.write(value);
            }

            @Override
            public void write(final byte[] data, final int offset, final int length) {
                bytes.write(data, offset, length);
            }

            @Override
            public void flush() {
                flushes[0]++;
            }
        };
        final var generator = JsonMapper.builder().build().createGenerator(target);
        generator.writeStartArray();
        for (final var element : elements()) {
            codec.write(element, generator);
        }
        assertEquals(0, flushes[0]);
        assertEquals(0, bytes.size());
        generator.writeEndArray();
        generator.close();
        assertArrayEquals(codec.write(elements()).getBytes(StandardCharsets.UTF_8), bytes.toByteArray());
    }

    /**
     * Each element written to a stream equals its own slice of the array encoding; the stream stays open.
     * @throws Exception if encoding fails
     */
    @Test
    void eachElementEqualsItsArraySlice() throws Exception {
        final var elements = elements();
        final var joined = new ByteArrayOutputStream();
        joined.write('[');
        for (int index = 0; index < elements.size(); index++) {
            if (index > 0) {
                joined.write(',');
            }
            final var single = new ByteArrayOutputStream();
            final var tracked = new Tracked(single);
            codec.write(elements.get(index), tracked);
            assertFalse(tracked.closed);
            assertArrayEquals(codec.write(List.of(elements.get(index))).getBytes(StandardCharsets.UTF_8),
                    wrap(single.toByteArray()), "element " + index);
            joined.write(single.toByteArray());
        }
        joined.write(']');
        assertArrayEquals(codec.write(elements).getBytes(StandardCharsets.UTF_8), joined.toByteArray());
    }

    /**
     * An empty sequence and argument validation.
     * @throws Exception if encoding fails
     */
    @Test
    void emptyArrayAndNullArguments() throws Exception {
        final var bytes = new ByteArrayOutputStream();
        try (final var generator = JsonMapper.builder().build().createGenerator(bytes)) {
            generator.writeStartArray();
            generator.writeEndArray();
        }
        assertEquals(codec.write(List.of()), bytes.toString(StandardCharsets.UTF_8));
        final var element = elements().getFirst();
        assertThrows(NullPointerException.class, () -> codec.write(null, new ByteArrayOutputStream()));
        assertThrows(NullPointerException.class, () -> codec.write(element, (OutputStream) null));
        assertThrows(NullPointerException.class, () -> codec.write(element, (tools.jackson.core.JsonGenerator) null));
    }

    /**
     * Wraps one encoded object as a one-element array.
     * @param object encoded object
     * @return array bytes
     */
    private static byte[] wrap(final byte[] object) {
        final byte[] result = new byte[object.length + 2];
        result[0] = '[';
        System.arraycopy(object, 0, result, 1, object.length);
        result[result.length - 1] = ']';
        return result;
    }

    /**
     * Elements covering every wire feature: tables with HTML, images, compressed originals, Unicode, numbers of
     * every supported type, and empty or omitted fields.
     * @return elements
     */
    private List<Element> elements() {
        final var table = new LinkedHashMap<String, Object>();
        table.put("filename", "report.pdf");
        table.put("filetype", "application/pdf");
        table.put("text_as_html", "<table><tbody><tr><td>Item &amp; \"x\"</td><td>Qty</td></tr></tbody></table>");
        table.put("page_number", 3);
        table.put("coordinates", Map.of("system", "PDFUserSpace", "layout_width", 612.0, "layout_height", 792.0,
                                         "points", List.of(List.of(1.5, 2.25), List.of(1e-7, 1e21))));
        table.put("pdf_table_continuation",
                Map.of("version", 1, "root_element_id", "a".repeat(32), "part_index", 2, "root_page_number", 1));
        final var image = new LinkedHashMap<String, Object>();
        image.put("filetype", "application/pdf");
        image.put("image_base64",
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");
        image.put("image_mime_type", "image/png");
        image.put("image_stencil", true);
        image.put("detection_origin", "debug only");
        final var numbers = new LinkedHashMap<String, Object>();
        numbers.put("big_decimal", new BigDecimal("12345678901234567890.123456789012345678901"));
        numbers.put("big_exponent", new BigDecimal("1E+3"));
        numbers.put("scaled", new BigDecimal("0.10000000000000000555"));
        numbers.put("big_integer", new BigInteger("-98765432109876543210987654321"));
        numbers.put("long", Long.MIN_VALUE);
        numbers.put("short", (short) 7);
        numbers.put("byte", (byte) -3);
        numbers.put("float", 0.1f);
        numbers.put("double", -0.0);
        numbers.put("tiny", Double.MIN_VALUE);
        numbers.put("empty_list", List.of());
        numbers.put("empty_map", Map.of());
        numbers.put("nested_empty", List.of(List.of(), Map.of()));
        numbers.put("nested_null", Arrays.asList(1, null, "x"));
        numbers.put("nested_null_map", nullValueMap());
        final var originals = codec.encodeOriginals(List.of(new Element("b".repeat(32), ElementType.Title, "Original é",
                Map.of("coordinates", Map.of("system", "PixelSpace", "points", List.of(List.of(1.25, 2.35)))))));
        final var composite = new LinkedHashMap<String, Object>();
        composite.put("orig_elements", originals);
        composite.put("languages", List.of("eng"));
        final var unicode = new LinkedHashMap<String, Object>();
        unicode.put("text_note", "\u0000\u001f\u007f   😀 中文 é \"quote\" \\ /");
        unicode.put("ключ", "значение");
        return List.of(new Element("t".repeat(32), ElementType.Table, "Item & \"x\" Qty", table),
                new Element("i".repeat(32), ElementType.Image, "", image),
                new Element("n".repeat(32), ElementType.NarrativeText, "numbers", numbers),
                new Element("c".repeat(32), ElementType.CompositeElement, "Original é", composite),
                new Element("u".repeat(32), ElementType.Title, "😀 中文  \t\n\r\b\f", unicode),
                new Element("e".repeat(32), ElementType.UncategorizedText, "", Map.of()));
    }

    /**
     * Builds a nested map with a null value, which the snapshot keeps below the top level.
     * @return map
     */
    private static Map<String, Object> nullValueMap() {
        final var map = new LinkedHashMap<String, Object>();
        map.put("present", "v");
        map.put("absent", null);
        return map;
    }

    /** Records whether the codec closed a borrowed stream. */
    private static final class Tracked extends OutputStream {
        /** Destination. */
        private final OutputStream target;
        /** Whether close was called. */
        private boolean closed;

        /**
         * Wraps a destination.
         * @param target destination
         */
        Tracked(final OutputStream target) {
            this.target = target;
        }

        @Override
        public void write(final int value) throws IOException {
            target.write(value);
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
