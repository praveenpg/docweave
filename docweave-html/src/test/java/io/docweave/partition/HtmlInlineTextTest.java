package io.docweave.partition;

import org.jsoup.nodes.TextNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class HtmlInlineTextTest {
    @Test
    void seededUtf16TokensSurviveNormalizationIncludingSupplementaryAndUnpairedSurrogates() throws Exception {
        final var random = new Random(0x25C0DE);
        final var tokens = List.of("ASCII", "Café", "漢", "😀", "𐐀", "e\u0301", "\ud800", "\udc00", "\u0000", "<&>");
        final var spaces = List.of(" ", "\n", "\t", "\u00a0", "\u2003");
        for (int trial = 0; trial < 1000; trial++) {
            final var expected = new ArrayList<String>();
            final var input = new StringBuilder(spaces.get(random.nextInt(spaces.size())));
            for (int i = 0; i < 20; i++) {
                final String token = tokens.get(random.nextInt(tokens.size()));
                expected.add(token);
                input.append(token).append(spaces.get(random.nextInt(spaces.size())));
            }
            final var renderer = new HtmlInlineText();
            renderer.append(new TextNode(input.toString()));
            assertEquals(String.join(" ", expected), renderer.text(), "trial=" + trial);
        }
    }

    @Test
    void supplementaryBoundaryChecksCountCodePointsAndPreserveInterruption() throws Exception {
        final var renderer = new HtmlInlineText(Set.of(), 3);
        renderer.append(new TextNode("😀 𐐀"));
        assertEquals("😀 𐐀", renderer.text());
        assertEquals(PartitionException.Code.TABLE_LIMIT,
                assertThrows(PartitionException.class, () -> renderer.append(new TextNode("x"))).code());
        assertEquals("😀 𐐀", renderer.text());
        Thread.currentThread().interrupt();
        try {
            assertThrows(java.io.InterruptedIOException.class, () -> new HtmlInlineText().append(new TextNode("😀")));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }
}
