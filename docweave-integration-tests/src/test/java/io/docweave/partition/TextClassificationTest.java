package io.docweave.partition;

import io.docweave.core.ElementType;
import org.junit.jupiter.api.Test;

import java.io.InterruptedIOException;
import java.util.List;
import java.util.Random;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** Historical regex/string rules are an independent oracle for the allocation-free implementation. */
class TextClassificationTest {
    private static final Pattern BULLET = Pattern.compile("^[•●▪◦‣⁃*+\\-]\\s+(.+)$", Pattern.DOTALL);
    private static final Pattern NUMBERED = Pattern.compile("^(?:\\d+[.)]|[a-zA-Z][.)])\\s+.+$", Pattern.DOTALL);

    private ElementType historical(final String text) {
        if (BULLET.matcher(text).matches() || NUMBERED.matcher(text).matches()) {
            return ElementType.ListItem;
        }
        if (text.codePoints().noneMatch(Character::isLetter)) {
            return ElementType.UncategorizedText;
        }
        final boolean sentence = text.endsWith(".") || text.endsWith("!") || text.endsWith("?") || text.endsWith("。")
                                 || text.endsWith("！") || text.endsWith("？");
        return text.split(" ").length <= 12 && text.codePointCount(0, text.length()) <= 100 && !sentence
                ? ElementType.Title
                : ElementType.NarrativeText;
    }

    private void matchesHistorical(final String text) throws Exception {
        assertEquals(historical(text), TextSupport.classify(text), () -> "Classification mismatch: " + text);
        final var bullet = BULLET.matcher(text);
        final String cleaned = bullet.matches() ? bullet.group(1) : text;
        assertEquals(cleaned, TextSupport.cleanBullet(text));
    }

    @Test
    void listMarkersWhitespaceAndGreedyCleaningRetainHistoricalRules() throws Exception {
        for (final String marker : List.of("•", "●", "▪", "◦", "‣", "⁃", "*", "+", "-", "1.", "001)", "a.", "Z)", "١.",
                     "é.", "ab.", "12x", "")) {
            for (final String space : List.of("", " ", "  ", "\t", "\r\n", "\u000b\f", "\u0085", "\u00a0", "\u2003")) {
                for (final String body : List.of("", " ", "\t", "🧵", "Café 漢.", "line\nnext", "\n")) {
                    matchesHistorical(marker + space + body);
                }
            }
        }
        assertEquals(" ", TextSupport.cleanBullet("•  "));
        assertEquals("• ", TextSupport.cleanBullet("• "));
        assertEquals("1. text", TextSupport.cleanBullet("1. text"));
    }

    @Test
    void seededMixedUnicodeCasesMatchHistoricalClassificationAndCleaning() throws Exception {
        final var random = new Random(911);
        final String[] units = {"a", "B", "0", "9", ".", ")", "•", "-", "+", " ", "\t", "\n", "\r", "\f", "漢", "é",
                "🧵", "。", "!", "\u0085", "\u00a0"};
        for (int trial = 0; trial < 10000; trial++) {
            final var text = new StringBuilder();
            final int size = random.nextInt(150);
            for (int index = 0; index < size; index++) {
                text.append(units[random.nextInt(units.length)]);
            }
            matchesHistorical(text.toString());
        }
    }

    @Test
    void titlePointWordAndTrailingSpaceBoundariesAreUnchanged() throws Exception {
        for (final int words : List.of(11, 12, 13)) {
            for (final int points : List.of(99, 100, 101)) {
                final String text = "a ".repeat(words - 1) + "🧵".repeat(points - 2 * (words - 1) - 1) + "a";
                matchesHistorical(text);
                matchesHistorical(" " + text);
                matchesHistorical(text + "   ");
                matchesHistorical(text + "!");
            }
        }
        for (final String text : List.of("", " ", "   ", "a  b", "a".repeat(50000), "7".repeat(50000) + ". item",
                     "•"
                             + " ".repeat(50000))) {
            matchesHistorical(text);
        }
    }

    @Test
    void preInterruptedMarkerRecognitionRetainsFlagsAndReuse() throws Exception {
        Thread.currentThread().interrupt();
        try {
            for (final String text : List.of("", "a", "• text", "12. text")) {
                assertThrows(InterruptedIOException.class, () -> TextSupport.classify(text));
                assertThrows(InterruptedIOException.class, () -> TextSupport.cleanBullet(text));
                assertTrue(Thread.currentThread().isInterrupted());
            }
        } finally {
            Thread.interrupted();
        }
        assertEquals(ElementType.ListItem, TextSupport.classify("12. text"));
    }
}
