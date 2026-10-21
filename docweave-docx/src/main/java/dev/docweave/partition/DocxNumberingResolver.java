package dev.docweave.partition;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTLvl;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Stored-part-local counters keyed by definition-selected sequence identity.
 * Simple direct single-level decimal instances share their unmodified abstract sequence;
 * overrides, multilevel definitions and style-linked aliases retain instance-local counters.
 * Definition traversal and identity selection are delegated to DocxNumberingDefinitions.
 * Labels remain metadata so semantic list/heading text stays unprefixed.
 * Unresolved definitions preserve the source reference and expose a diagnostic marker.
 */
final class DocxNumberingResolver {
    /** Pattern matching 1-based level placeholders ({@code %1} through {@code %9}) in numbering level templates. */
    private static final Pattern TOKEN = Pattern.compile("%([1-9])");
    /** Document-local numbering definition lookup. */
    private final DocxNumberingDefinitions definitions;
    /** Active 9-level counter arrays keyed by sequence counter identity. */
    private final Map<DocxNumberingDefinitions.CounterKey, BigInteger[]> counters = new HashMap<>();

    /**
     * Creates a numbering counter resolver for the given Word document.
     *
     * @param document parsed Word document
     */
    DocxNumberingResolver(final XWPFDocument document) {
        this.definitions = new DocxNumberingDefinitions(document);
    }

    /**
     * Advances the counter for the specified numbering instance and depth and returns its numbering metadata.
     *
     * @param id numbering instance ID
     * @param depth 0-based list level depth
     * @return immutable map of numbering metadata attributes
     * @throws java.io.IOException if the thread is interrupted
     */
    Map<String, Object> consume(final BigInteger id, final int depth) throws java.io.IOException {
        final var current = definitions.level(id, depth);
        if (current == null) {
            return Map.of("docx_numbering_unresolved", true);
        }
        final BigInteger[] values = counters.computeIfAbsent(definitions.counterKey(id), ignored -> new BigInteger[9]);
        values[depth] = values[depth] == null ? current.start() : values[depth].add(BigInteger.ONE);
        // Reset deeper level counters unless lvlRestart explicitly suppresses restart or binds to a shallower level.
        for (int d = depth + 1; d < 9; d++) {
            final var child = definitions.level(id, d);
            if (child != null
                    && (!child.definition().isSetLvlRestart()
                            || child.definition().getLvlRestart().getVal().signum() > 0
                                       && child.definition().getLvlRestart().getVal().intValue() - 1 >= depth)) {
                values[d] = null;
            }
        }
        final String format = format(current.definition());
        final String template =
                current.definition().isSetLvlText() ? current.definition().getLvlText().getVal() : "%" + (depth + 1);
        final var result = new LinkedHashMap<String, Object>();
        result.put("docx_numbering_format", format);
        result.put("docx_numbering_level_text", template);
        result.put("docx_numbering_counter", values[depth].toString());
        final var suffixNode = DocxBodyView.child(current.definition().getDomNode(), "suff");
        final String suffix = suffixNode == null ? null : DocxBodyView.attribute(suffixNode, DocxBodyView.WORD, "val");
        result.put("docx_list_suffix", suffix == null ? "tab" : suffix);
        if (format.equals("bullet") || format.equals("none")) {
            result.put("docx_list_label", format.equals("none") ? "" : template);
            return result;
        }
        // Expand %1..%9 placeholders in lvlText, forcing decimal rendering when isLgl (legal numbering) is active.
        final var matcher = TOKEN.matcher(template);
        final var label = new StringBuilder();
        boolean supported = true;
        final boolean legal = current.definition().isSetIsLgl()
                              && !"false".equals(String.valueOf(current.definition().getIsLgl().getVal()))
                              && !"0".equals(String.valueOf(current.definition().getIsLgl().getVal()))
                              && !"off".equals(String.valueOf(current.definition().getIsLgl().getVal()));
        while (matcher.find()) {
            int d = Integer.parseInt(matcher.group(1)) - 1;
            final var definition = definitions.level(id, d);
            BigInteger value = values[d] == null ? definition == null ? BigInteger.ONE : definition.start() : values[d];
            final String formatted = render(value, legal                ? "decimal"
                                                   : definition == null ? "decimal"
                                                                        : format(definition.definition()));
            if (formatted == null) {
                supported = false;
                break;
            }
            matcher.appendReplacement(label, java.util.regex.Matcher.quoteReplacement(formatted));
        }
        if (supported) {
            matcher.appendTail(label);
            result.put("docx_list_label", label.toString());
        } else {
            result.put("docx_numbering_unsupported", true);
        }
        return Map.copyOf(result);
    }

    /**
     * Returns the numbering format name for a level definition, defaulting to {@code "decimal"}.
     *
     * @param level XMLBeans level definition
     * @return numbering format string
     */
    private static String format(final CTLvl level) {
        return level.isSetNumFmt() ? level.getNumFmt().getVal().toString() : "decimal";
    }

    /**
     * Formats a numeric counter value according to a Word numbering format.
     *
     * @param number counter value to format
     * @param format Word numbering format name
     * @return formatted counter token, or {@code null} if the format or value range is unsupported
     */
    private static String render(final BigInteger number, final String format) {
        return switch (format) {
            case "decimal" -> number.toString();
            case "decimalZero" ->
                number.signum() >= 0 && number.compareTo(BigInteger.TEN) < 0 ? "0" + number : number.toString();
            case "upperLetter", "lowerLetter" -> {
                if (number.signum() <= 0) {
                    yield null;
                }
                // Convert positive counter to 1-based bijective base-26 alphabetic digits (A..Z, AA..ZZ).
                var value = number;
                final var letters = new StringBuilder();
                while (value.signum() > 0) {
                    final var pair = value.subtract(BigInteger.ONE).divideAndRemainder(BigInteger.valueOf(26));
                    letters.append((char) ('A' + pair[1].intValue()));
                    value = pair[0];
                }
                final String label = letters.reverse().toString();
                yield format.equals("lowerLetter") ? label.toLowerCase(java.util.Locale.ROOT) : label;
            }
            case "upperRoman", "lowerRoman" -> {
                if (number.signum() <= 0 || number.compareTo(BigInteger.valueOf(3999)) > 0) {
                    yield null;
                }
                // Greedily decompose 1..3999 into standard subtractive Roman numeral symbols.
                int value = number.intValue();
                final var label = new StringBuilder();
                final int[] units = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
                final String[] symbols = {"M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"};
                for (int i = 0; i < units.length; i++) {
                    while (value >= units[i]) {
                        label.append(symbols[i]);
                        value -= units[i];
                    }
                }
                yield format.equals("lowerRoman") ? label.toString().toLowerCase(java.util.Locale.ROOT)
                                                  : label.toString();
            }
            case "ordinal" -> {
                final int tail = number.abs().mod(BigInteger.valueOf(100)).intValue();
                final int last = tail % 10;
                yield number
                        + (tail >= 11 && tail <= 13 ? "th"
                                : last == 1         ? "st"
                                : last == 2         ? "nd"
                                : last == 3         ? "rd"
                                                    : "th");
            }
            case "none" -> "";
            default -> null;
        };
    }
}
