package io.docweave.json;

import io.docweave.core.Element;
import io.docweave.core.ElementType;
import io.docweave.partition.PartitionResult;
import io.docweave.partition.PdfBudget;
import io.docweave.partition.PdfPageRange;
import io.docweave.partition.PdfRangeResult;
import io.docweave.partition.PdfTableBoundary;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Streaming writer and tree-based reader for the versioned range-result format. */
final class DefaultPdfRangeResultJson implements PdfRangeResultJson {
    /** Format marker. */
    private static final String FORMAT = "docweave-pdf-range";
    /** Tag of a typed number. */
    private static final String NUMBER = "$num";
    /** Tag of a map whose keys could be mistaken for tags. */
    private static final String MAP = "$map";
    /** Tag of a non-finite double, which plain JSON cannot represent. */
    private static final String DOUBLE = "d:";
    /** Writer; reading uses a per-instance bounded mapper. */
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    /** Nesting allowance: metadata depth 100, doubled by tag wrappers, plus the result structure. */
    private static final int MAX_DEPTH = 256;

    /** Maximum accepted encoded size in bytes. */
    private final long maxEncodedBytes;
    /** Reader bounded by document length, string length and nesting depth. */
    private final JsonMapper reader;

    /** Creates the codec with the default read limit. */
    DefaultPdfRangeResultJson() {
        this(PdfRangeResultJson.DEFAULT_MAX_ENCODED_BYTES);
    }

    /**
     * Creates the codec with a read limit.
     * @param maxEncodedBytes positive maximum encoded size
     */
    DefaultPdfRangeResultJson(final long maxEncodedBytes) {
        if (maxEncodedBytes <= 0) {
            throw new IllegalArgumentException("maxEncodedBytes must be positive");
        }
        this.maxEncodedBytes = maxEncodedBytes;
        reader = JsonMapper
                         .builder(JsonFactory.builder()
                                         .streamReadConstraints(StreamReadConstraints.builder()
                                                         .maxDocumentLength(maxEncodedBytes)
                                                         .maxStringLength(
                                                                 (int) Math.min(Integer.MAX_VALUE, maxEncodedBytes))
                                                         .maxNestingDepth(MAX_DEPTH)
                                                         .build())
                                         .build())
                         .build();
    }

    @Override
    public void write(final PdfRangeResult result, final OutputStream output) throws IOException {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(output, "output");
        final var borrowed = new FilterOutputStream(output) {
            @Override
            public void write(final byte[] bytes, final int offset, final int length) throws IOException {
                out.write(bytes, offset, length);
            }

            @Override
            public void close() throws IOException {
                flush();
            }
        };
        try (final var generator = MAPPER.createGenerator(borrowed)) {
            generator.writeStartObject();
            generator.writeStringProperty("format", FORMAT);
            generator.writeNumberProperty("formatVersion", result.formatVersion());
            generator.writeStringProperty("libraryVersion", result.libraryVersion());
            generator.writeStringProperty("sourceSha256", result.sourceSha256());
            generator.writeNumberProperty("sourceBytes", result.sourceBytes());
            generator.writeName("filename");
            if (result.filename() == null) {
                generator.writeNull();
            } else {
                generator.writeString(result.filename());
            }
            generator.writeStringProperty("optionsDigest", result.optionsDigest());
            generator.writeNumberProperty("documentPages", result.documentPages());
            generator.writeNumberProperty("first", result.coverage().first());
            generator.writeNumberProperty("last", result.coverage().last());
            generator.writeName("documentWarnings");
            warnings(generator, result.documentWarnings());
            generator.writeArrayPropertyStart("idReferenceFields");
            for (final String field : result.idReferenceFields()) {
                generator.writeString(field);
            }
            generator.writeEndArray();
            generator.writeArrayPropertyStart("pages");
            for (final var page : result.pages()) {
                generator.writeStartObject();
                generator.writeNumberProperty("pageNumber", page.pageNumber());
                generator.writeArrayPropertyStart("elements");
                for (final var element : page.elements()) {
                    generator.writeStartObject();
                    generator.writeStringProperty("type", element.type().name());
                    generator.writeStringProperty("element_id", element.elementId());
                    generator.writeStringProperty("text", element.text());
                    generator.writeName("metadata");
                    value(generator, element.metadata());
                    generator.writeEndObject();
                }
                generator.writeEndArray();
                generator.writeName("warnings");
                warnings(generator, page.warnings());
                generator.writeObjectPropertyStart("usage");
                for (final var usage : page.usage().entrySet()) {
                    generator.writeNumberProperty(usage.getKey().name(), usage.getValue());
                }
                generator.writeEndObject();
                generator.writeEndObject();
            }
            generator.writeEndArray();
            generator.writeName("firstPageTable");
            boundary(generator, result.firstPageTable());
            generator.writeName("lastPageTable");
            boundary(generator, result.lastPageTable());
            generator.writeEndObject();
        }
    }

    @Override
    public byte[] write(final PdfRangeResult result) {
        final var bytes = new ByteArrayOutputStream();
        try {
            write(result, bytes);
        } catch (final IOException impossible) {
            throw new UncheckedIOException(impossible);
        }
        return bytes.toByteArray();
    }

    @Override
    public PdfRangeResult read(final InputStream input) throws IOException {
        Objects.requireNonNull(input, "input");
        final var bounded = new java.io.FilterInputStream(input) {
            /** Bytes read so far. */
            private long count;

            @Override
            public int read() throws IOException {
                final int value = super.read();
                if (value >= 0) {
                    counted(1);
                }
                return value;
            }

            @Override
            public int read(final byte[] bytes, final int offset, final int length) throws IOException {
                final int read = super.read(bytes, offset, length);
                if (read > 0) {
                    counted(read);
                }
                return read;
            }

            @Override
            public void close() {
            }

            /**
             * Rejects input past the limit before it is parsed further.
             * @param read bytes just read
             */
            private void counted(final int read) {
                count += read;
                if (count > maxEncodedBytes) {
                    throw tooLarge();
                }
            }
        };
        final Object tree;
        try {
            tree = reader.readValue(bounded, Object.class);
        } catch (final tools.jackson.core.exc.StreamConstraintsException failure) {
            throw new IllegalArgumentException("PDF range result exceeds the read limits", failure);
        }
        return decode(tree);
    }

    /**
     * Describes an oversize input.
     * @return failure
     */
    private IllegalArgumentException tooLarge() {
        return new IllegalArgumentException("PDF range result exceeds maxEncodedBytes=" + maxEncodedBytes);
    }

    @Override
    public PdfRangeResult read(final byte[] json) {
        Objects.requireNonNull(json, "json");
        if (json.length > maxEncodedBytes) {
            throw tooLarge();
        }
        try {
            return read(new ByteArrayInputStream(json));
        } catch (final IOException impossible) {
            throw new UncheckedIOException(impossible);
        }
    }

    /**
     * Writes warnings.
     * @param generator generator
     * @param warnings warnings
     */
    private static void warnings(final JsonGenerator generator, final List<PartitionResult.Warning> warnings) {
        generator.writeStartArray();
        for (final var warning : warnings) {
            generator.writeStartObject();
            generator.writeStringProperty("code", warning.code().name());
            generator.writeStringProperty("message", warning.message());
            generator.writeEndObject();
        }
        generator.writeEndArray();
    }

    /**
     * Writes boundary evidence or null.
     * @param generator generator
     * @param boundary optional boundary
     */
    private static void boundary(final JsonGenerator generator, final PdfTableBoundary boundary) {
        if (boundary == null) {
            generator.writeNull();
            return;
        }
        final var evidence = boundary.evidence();
        generator.writeStartObject();
        generator.writeNumberProperty("pageNumber", boundary.pageNumber());
        generator.writeStringProperty("elementId", boundary.elementId());
        generator.writeBooleanProperty("entryClear", boundary.entryClear());
        generator.writeBooleanProperty("exitClear", boundary.exitClear());
        generator.writeName("width");
        writeDouble(generator, evidence.width());
        generator.writeName("height");
        writeDouble(generator, evidence.height());
        generator.writeName("transforms");
        value(generator, evidence.transforms());
        generator.writeStringProperty("source", evidence.source());
        generator.writeArrayPropertyStart("columns");
        for (final double column : evidence.columns()) {
            writeDouble(generator, column);
        }
        generator.writeEndArray();
        generator.writeArrayPropertyStart("header");
        for (final String label : evidence.header()) {
            generator.writeString(label);
        }
        generator.writeEndArray();
        generator.writeName("top");
        writeDouble(generator, evidence.top());
        generator.writeName("bottom");
        writeDouble(generator, evidence.bottom());
        generator.writeEndObject();
    }

    /**
     * Writes one JSON-compatible value losslessly.
     * @param generator generator
     * @param value value
     */
    private static void value(final JsonGenerator generator, final Object value) {
        switch (value) {
        case null -> generator.writeNull();
        case final String text -> generator.writeString(text);
        case final Boolean bool -> generator.writeBoolean(bool);
        case final Integer number -> generator.writeNumber(number);
        case final Double number -> writeDouble(generator, number);
        case final Long number -> tagged(generator, "L", number.toString());
        case final Short number -> tagged(generator, "S", number.toString());
        case final Byte number -> tagged(generator, "B", number.toString());
        case final Float number -> tagged(generator, "F", number.toString());
        case final BigInteger number -> tagged(generator, "I", number.toString());
        case final BigDecimal number -> tagged(generator, "D", number.toString());
        case final List<?> list -> {
            generator.writeStartArray();
            for (final var item : list) {
                value(generator, item);
            }
            generator.writeEndArray();
        }
        case final Map<?, ?> map -> {
            final boolean wrap = map.keySet().stream().anyMatch(key -> ((String) key).startsWith("$"));
            generator.writeStartObject();
            if (wrap) {
                generator.writeName(MAP);
                generator.writeStartObject();
            }
            map.forEach((key, nested) -> {
                generator.writeName((String) key);
                value(generator, nested);
            });
            if (wrap) {
                generator.writeEndObject();
            }
            generator.writeEndObject();
        }
        default -> throw new IllegalArgumentException("Unsupported metadata value " + value.getClass().getName());
        }
    }

    /**
     * Writes a double: finite values as JSON numbers, NaN and infinities as tagged values.
     * @param generator generator
     * @param value value
     */
    private static void writeDouble(final JsonGenerator generator, final double value) {
        if (Double.isFinite(value)) {
            generator.writeNumber(value);
        } else {
            tagged(generator, "d", Double.toString(value));
        }
    }

    /**
     * Writes a typed number.
     * @param generator generator
     * @param tag type tag
     * @param text decimal text
     */
    private static void tagged(final JsonGenerator generator, final String tag, final String text) {
        generator.writeStartObject();
        generator.writeStringProperty(NUMBER, tag + ":" + text);
        generator.writeEndObject();
    }

    /**
     * Decodes a value written by {@link #value(JsonGenerator, Object)}.
     * @param value decoded JSON
     * @return original value
     */
    private static Object decodeValue(final Object value) {
        if (value instanceof final Map<?, ?> map) {
            if (map.size() == 1 && map.get(NUMBER) instanceof final String number) {
                final String text = number.substring(2);
                return switch (number.substring(0, 2)) {
                    case "L:" -> Long.valueOf(text);
                    case "S:" -> Short.valueOf(text);
                    case "B:" -> Byte.valueOf(text);
                    case "F:" -> Float.valueOf(text);
                    case "I:" -> new BigInteger(text);
                    case "D:" -> new BigDecimal(text);
                    case DOUBLE -> Double.valueOf(text);
                    default -> throw new IllegalArgumentException("Unknown number tag in PDF range result");
                };
            }
            final Map<?, ?> entries = map.size() == 1 && map.get(MAP) instanceof final Map < ?, ? > inner ? inner : map;
            final var copy = new LinkedHashMap<String, Object>();
            entries.forEach((key, nested) -> copy.put((String) key, decodeValue(nested)));
            return copy;
        }
        if (value instanceof final List<?> list) {
            final var copy = new ArrayList<>(list.size());
            for (final var item : list) {
                copy.add(decodeValue(item));
            }
            return copy;
        }
        return value;
    }

    /**
     * Rebuilds a result from its decoded tree.
     * @param tree decoded JSON
     * @return result
     */
    private static PdfRangeResult decode(final Object tree) {
        final var root = map(tree, "result");
        if (!FORMAT.equals(root.get("format"))) {
            throw new IllegalArgumentException("Not a DocWeave PDF range result");
        }
        final var pages = new ArrayList<PdfRangeResult.Page>();
        for (final var item : list(root.get("pages"), "pages")) {
            final var page = map(item, "page");
            final var elements = new ArrayList<Element>();
            for (final var entry : list(page.get("elements"), "elements")) {
                final var element = map(entry, "element");
                @SuppressWarnings("unchecked")
                final var metadata = (Map<String, Object>) decodeValue(map(element.get("metadata"), "metadata"));
                elements.add(new Element(string(element.get("element_id")),
                        ElementType.valueOf(string(element.get("type"))), string(element.get("text")), metadata));
            }
            final var usage = new EnumMap<PdfBudget.Kind, Long>(PdfBudget.Kind.class);
            map(page.get("usage"), "usage")
                    .forEach((kind, amount)
                                     -> usage.put(PdfBudget.Kind.valueOf((String) kind), wholeLong(amount, "usage " + kind)));
            pages.add(new PdfRangeResult.Page(
                    wholeInt(page.get("pageNumber"), "pageNumber"), elements, warnings(page.get("warnings")), usage));
        }
        final var fields = list(root.get("idReferenceFields"), "idReferenceFields")
                                   .stream()
                                   .map(DefaultPdfRangeResultJson::string)
                                   .toList();
        return new PdfRangeResult(wholeInt(root.get("formatVersion"), "formatVersion"), string(root.get("libraryVersion")),
                string(root.get("sourceSha256")), wholeInt(root.get("sourceBytes"), "sourceBytes"),
                (String) root.get("filename"), string(root.get("optionsDigest")),
                wholeInt(root.get("documentPages"), "documentPages"),
                new PdfPageRange(wholeInt(root.get("first"), "first"), wholeInt(root.get("last"), "last")),
                warnings(root.get("documentWarnings")), fields, pages, boundary(root.get("firstPageTable")),
                boundary(root.get("lastPageTable")));
    }

    /**
     * Decodes warnings.
     * @param value decoded array
     * @return warnings
     */
    private static List<PartitionResult.Warning> warnings(final Object value) {
        return list(value, "warnings")
                .stream()
                .map(item -> map(item, "warning"))
                .map(warning
                        -> new PartitionResult.Warning(PartitionResult.WarningCode.valueOf(string(warning.get("code"))),
                                string(warning.get("message"))))
                .toList();
    }

    /**
     * Decodes optional boundary evidence.
     * @param value decoded object or null
     * @return boundary or null
     */
    @SuppressWarnings("unchecked")
    private static PdfTableBoundary boundary(final Object value) {
        if (value == null) {
            return null;
        }
        final var boundary = map(value, "boundary");
        final List<Double> columns = list(boundary.get("columns"), "columns")
                                             .stream()
                                             .map(item -> number(decodeValue(item)))
                                             .map(Number::doubleValue)
                                             .toList();
        final List<String> header =
                list(boundary.get("header"), "header").stream().map(DefaultPdfRangeResultJson::string).toList();
        return new PdfTableBoundary(wholeInt(boundary.get("pageNumber"), "boundary pageNumber"), string(boundary.get("elementId")),
                new PdfTableBoundary.Evidence(number(decodeValue(boundary.get("width"))).doubleValue(),
                        number(decodeValue(boundary.get("height"))).doubleValue(),
                        (Map<String, Object>) decodeValue(map(boundary.get("transforms"), "transforms")),
                        string(boundary.get("source")), columns, header,
                        number(decodeValue(boundary.get("top"))).doubleValue(),
                        number(decodeValue(boundary.get("bottom"))).doubleValue()),
                Boolean.TRUE.equals(boundary.get("entryClear")), Boolean.TRUE.equals(boundary.get("exitClear")));
    }

    /**
     * Requires an object.
     * @param value decoded value
     * @param what field description
     * @return map
     */
    private static Map<?, ?> map(final Object value, final String what) {
        if (value instanceof final Map<?, ?> map) {
            return map;
        }
        throw new IllegalArgumentException("PDF range result " + what + " must be an object");
    }

    /**
     * Requires an array.
     * @param value decoded value
     * @param what field description
     * @return list
     */
    private static List<?> list(final Object value, final String what) {
        if (value instanceof final List<?> list) {
            return list;
        }
        throw new IllegalArgumentException("PDF range result " + what + " must be an array");
    }

    /**
     * Requires a string.
     * @param value decoded value
     * @return string
     */
    private static String string(final Object value) {
        if (value instanceof final String text) {
            return text;
        }
        throw new IllegalArgumentException("PDF range result field must be a string");
    }

    /**
     * Requires a number.
     * @param value decoded value
     * @return number
     */
    private static Number number(final Object value) {
        if (value instanceof final Number number) {
            return number;
        }
        throw new IllegalArgumentException("PDF range result field must be a number");
    }

    /**
     * Requires an integral JSON number that fits in an {@code int}; fractions and overflow are rejected rather than
     * truncated.
     * @param value decoded value
     * @param what field description
     * @return value
     */
    private static int wholeInt(final Object value, final String what) {
        final long whole = wholeLong(value, what);
        if (whole < Integer.MIN_VALUE || whole > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("PDF range result " + what + " is out of range");
        }
        return (int) whole;
    }

    /**
     * Requires an integral JSON number that fits in a {@code long}; fractions and overflow are rejected rather than
     * truncated.
     * @param value decoded value
     * @param what field description
     * @return value
     */
    private static long wholeLong(final Object value, final String what) {
        switch (value) {
            case final Integer number -> {
                return number;
            }
            case final Long number -> {
                return number;
            }
            case final BigInteger number -> {
                if (number.bitLength() >= Long.SIZE) {
                    throw new IllegalArgumentException("PDF range result " + what + " is out of range");
                }
                return number.longValue();
            }
            case null, default -> throw new IllegalArgumentException("PDF range result " + what + " must be an integer");
        }
    }
}
