# Publisher figure-caption inventory and baseline

All main-body figure nodes from the same three pinned complete publisher PDF/JATS
pairs are retained. These sources were previously evaluated for other PDF work;
this is development/reference discovery, not an unseen or human-reviewed holdout.
Publisher text and original/derived media are not redistributed.

| Original publisher article | Main-body figures | Direct captions | Graphic references |
| --- | ---: | ---: | ---: |
| PLOS 10.1371/journal.pone.0324609 | 5 | 5 | 5 |
| PLOS 10.1371/journal.pone.0330904 | 1 | 1 | 1 |
| eLife 10.7554/eLife.90049 | 4 | 4 | 4 |
| Total | 10 | 10 | 10 |

The source-only inventory records exact DOM text lengths/hashes, direct labels
and captions and descendant graphic references. Those counts do not establish
physical PDF image/box correspondence. Original PDF and XML lengths/SHA256 are
verified against the existing frozen source manifest before analysis. External
entities and resources are disabled through the existing secure JVM XML helper.

The next independently frozen baseline processes every complete PDF under
`NATIVE_LINES`, `NATIVE_COLUMNS` and `SOURCE_TAGGED_LINES`. Every profile retains
all ten captions, including any failed/missing/duplicate observation. Extraction
receives PDF bytes alone: no JATS strings, regions, label-guided crops or repair.

| Uniform extraction profile | Complete captions uniquely present in all emitted text | Complete captions present in classified FigureCaption text |
| --- | ---: | ---: |
| Native lines | 10/10 | 0/10 |
| Native column paragraphs | 10/10 | 0/10 |
| Source-tagged tables with ordinary lines | 10/10 | 0/10 |

All nine complete source/profile runs succeed. **Text is retained; semantic
caption classification remains missing.** These separate metrics prevent treating
a classification gap as missing source content. They do not measure image-caption
association, caption-box accuracy, visual figure coverage or general PDF quality.

References use complete direct caption content with explicit line separators at
source title/paragraph block boundaries. Inline characters remain adjacent;
case, punctuation, Unicode, NBSP and hyphens are not repaired. The existing tested
JVM scorer collapses ASCII whitespace only and requires exact unique presence.
XML comments and processing instructions are excluded. Owned projection controls
verify block separation, inline adjacency, preserved Unicode/NBSP, ignored XML
commentary and noninheritance of nested source children. Adding these controls
and commentary filtering after the initial run leaves every final reference
identity and score result exact; both tool identities are retained in the protocol.
Raw DOM identities and scored structural-text identities are deliberately distinct.

Both JVM utilities compile on Java 21, pass strict private Javadocs and the pinned
formatter. No product changes or new dependency are introduced; the preceding
complete verification remains 1,155 passing tests. Java 25 stays deferred.

To reproduce, use the unchanged `pdf-scientific-reading/manifest.json` and explicit
original cache names from that manifest. Compile `InventoryPublisherFigures.java`
and `ScorePublisherCaptions.java` against the validation module Java 21 test
classpath. Each takes the original manifest, cache and a fresh numeric report;
run the scorer with headless AWT. No downloads occur. The selection, full source
inventory, baseline protocol and complete baseline scores accompany this README.

Next implementation work should evaluate an explicit conservative caption
strategy against this entire positive population and the independently selected
logo/Alt/ActualText negative controls. Missing semantic output must remain visible;
labels must not enter extraction or become an output-dependent winner selector.
