package dev.docweave.partition;

import dev.docweave.DocWeave;
import dev.docweave.core.Element;
import dev.docweave.core.ElementType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Owned policy cases informed by upstream source review; these are not newly executed upstream references. */
class HtmlUpstreamReviewTest {
    /** Partitions local text only; no URLs, embedded resources or browser are used. */
    private static PartitionResult parse(final String html) throws Exception {
        return DocWeave.create().partition(DocumentSource.text(html, "review.html"));
    }

    /** Keeps source-order text assertions separate from classification differences. */
    private static List<String> texts(final PartitionResult result) {
        return result.elements().stream().map(Element::text).toList();
    }

    @Test
    void unknownInlineAndLegacyFontRetainAdjacencyUnicodeAndLinks() throws Exception {
        for (final String tag : List.of("my-card", "font", "ix:nonFraction")) {
            final var result = parse("<p>😀 Start<" + tag + ">MID<a href='/detail'>link</a></" + tag + ">End.</p>");
            assertEquals(List.of("😀 StartMIDlinkEnd."), texts(result));
            final var links = (List<?>) result.elements().getFirst().metadata().get("links");
            final var link = (Map<?, ?>) links.getFirst();
            assertEquals("link", link.get("text"));
            assertEquals(10, link.get("start_index"));
        }
    }

    @Test
    void unknownAndFontWrappersExposeParagraphsListsAndTablesWithoutDuplicatingText() throws Exception {
        for (final String tag : List.of("my-card", "font", "ix:nonNumeric")) {
            final var result = parse("<div>Before<" + tag + ">Lead<p>First paragraph.</p><p>Second paragraph.</p>"
                                     + "<ul><li>Listed item.</li></ul><table><tr><td>Cell</td></tr></table>Tail</" + tag
                                     + ">After</div>");
            assertEquals(
                    List.of("BeforeLead", "First paragraph.", "Second paragraph.", "Listed item.", "Cell", "TailAfter"),
                    texts(result));
            assertEquals(ElementType.ListItem, result.elements().get(3).type());
            assertEquals(ElementType.Table, result.elements().get(4).type());
        }
    }

    @Test
    void nestedUnknownWrappersPreserveHeadingParentsAndListAnchors() throws Exception {
        final var result =
                parse("<outer-card><h1>Section</h1><inner-card><p>Paragraph.</p><ul><li>Parent"
                        + "<custom-box><ul><li>Child</li></ul></custom-box></li></ul></inner-card></outer-card>");
        assertEquals(List.of("Section", "Paragraph.", "Parent", "Child"), texts(result));
        assertEquals(result.elements().get(0).elementId(), result.elements().get(1).metadata().get("parent_id"));
        assertEquals(result.elements().get(2).elementId(), result.elements().get(3).metadata().get("parent_id"));
    }

    @Test
    void reviewedNonContentTagsDropWholeSubtreesButRetainTheirTails() throws Exception {
        for (final String tag : List.of("audio", "canvas", "datalist", "dialog", "iframe", "ix:header", "noembed",
                     "noframes", "object", "select", "svg", "textarea", "title", "video", "xml")) {
            final String body = List.of("iframe", "noembed", "noframes", "textarea").contains(tag)
                                        ? "Unavailable fallback"
                                        : "<my-card>Unavailable fallback</my-card>";
            final var result = parse("<div>Before <" + tag + ">" + body + "</" + tag + "> after.</div>");
            assertEquals(List.of("Before after."), texts(result), tag);
        }
        assertTrue(parse("<html><head><title>Metadata</title></head><body></body></html>").elements().isEmpty());
        // jsoup repairs an invalid custom head child into body content; lxml repair can differ.
        assertEquals(List.of("Recovered content"),
                texts(parse("<html><head><custom-meta>Recovered content</custom-meta></head></html>")));
    }

    @Test
    void hiddenUnknownTreesStayExcludedAndDepthChecksPrecedeUnwrapping() throws Exception {
        assertEquals(
                List.of("Visible."), texts(parse("<my-card hidden><p>Hidden.</p></my-card>"
                                                 + "<font aria-hidden='true'>Also hidden.</font><p>Visible.</p>")));
        final String deep = "<my-card>".repeat(12) + "Content"
                            + "</my-card>".repeat(12);
        final var error = assertThrows(PartitionException.class,
                ()
                        -> DocWeave.create().partition(DocumentSource.text(deep, "deep.html"),
                                PartitionOptions.builder().maxHtmlDepth(8).build()));
        assertEquals(PartitionException.Code.HTML_DEPTH_LIMIT, error.code());
    }

    @Test
    void captionPolicyRetainsSeparateFigureCaptionAndCanonicalCellOnlyTable() throws Exception {
        final var result = parse("<table><caption onclick='bad()'>Quarterly <b>result</b> &amp; growth</caption>"
                                 + "<tr><th>Area</th><th>Value</th></tr><tr><td>West</td><td>4</td></tr></table>");
        assertEquals(List.of(ElementType.FigureCaption, ElementType.Table),
                result.elements().stream().map(Element::type).toList());
        assertEquals(List.of("Quarterly result & growth", "Area\tValue\nWest\t4"), texts(result));
        final String html = (String) result.elements().get(1).metadata().get("text_as_html");
        assertFalse(html.contains("caption"));
        assertFalse(html.contains("onclick"));
        assertFalse(html.contains("Quarterly"));
        assertEquals(List.of("Only caption"),
                texts(parse("<table><caption>Only caption</caption><tr><td> </td></tr></table>")));
    }

    @Test
    void sharedMarkdownDomModeUsesTheSameUnknownWrapperAndExclusionPolicy() throws Exception {
        final var source = DocumentSource.text(
                "<my-card><p>First.</p><p>Second.</p><object>Fallback</object></my-card>\n", "review.md");
        final var result = DocWeave.create().partition(
                source, PartitionOptions.builder()
                                .markdownOptions(new MarkdownOptions(
                                        true, 128, MarkdownOptions.RawHtml.EXTRACT_DOM, TableLimits.defaults()))
                                .build());
        assertEquals(List.of("First.", "Second."), texts(result));
    }

    @Test
    void nestedTableMetadataIntervalsStillMatchAfterUnknownWrapperNormalization() throws Exception {
        final var source = DocumentSource.text("<my-card><table><tr><td>😀 Before<child-box><table><tr><td>Child</td>"
                                                       + "</tr></table></child-box>After</td></tr></table></my-card>",
                "nested.html");
        final var baseline = DocWeave.create().partition(source);
        final var preserved = DocWeave.create().partition(
                source, PartitionOptions.builder().nestedTableOptions(NestedTableOptions.preserveMetadata()).build());
        assertEquals(texts(baseline), texts(preserved));
        final var table = preserved.elements().getFirst();
        final var descriptor = (Map<?, ?>) ((List<?>) table.metadata().get("nested_tables")).getFirst();
        final int start = (Integer) descriptor.get("start_index");
        final int end = (Integer) descriptor.get("end_index");
        assertEquals("Child", table.text().substring(table.text().offsetByCodePoints(0, start),
                                      table.text().offsetByCodePoints(0, end)));
    }
}
