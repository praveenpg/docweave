package dev.docweave.partition;

import org.jsoup.nodes.Entities;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class HtmlTextEscaperTest {
    @Test
    void adjacentSupplementaryPairsAndInvalidCodeUnitsRetainExactEscaping() {
        final String input = "😀𐐀\ud800X\udc00<&>\"'\u00a0\u0001";
        final String expected = "😀𐐀&#xd800;X&#xdc00;&lt;&amp;&gt;&quot;&apos;&nbsp;&#x1;";
        assertEquals(expected, HtmlTextEscaper.escape(input));
        assertEquals(Entities.escape(input), HtmlTextEscaper.escape(input));
    }

    @Test
    void sharedEscapingPreservesPreviousLibraryBehaviorAcrossAsciiAndUnicode() {
        for (int point = 0; point < 0x10000; point++) {
            final String text = Character.toString(point);
            assertEquals(Entities.escape(text), HtmlTextEscaper.escape(text), "point=" + point);
        }
        final var random = new Random(803);
        for (int trial = 0; trial < 1000; trial++) {
            final var text = new StringBuilder();
            for (int i = 0; i < 100; i++) {
                text.appendCodePoint(random.nextInt(0x110000));
            }
            assertEquals(Entities.escape(text.toString()), HtmlTextEscaper.escape(text.toString()));
        }
    }
}
