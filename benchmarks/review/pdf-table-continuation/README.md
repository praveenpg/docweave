# Conservative adjacent-page table continuation links

The explicit PdfOptions.TableContinuation.REPEATED_HEADER policy links isolated ruled tables while
preserving every page-local Element, text string, repeated header, HTML, image and coordinate frame.
NONE remains the default; prior constructors/copy policies are preserved. Public services remain
interfaces; PdfPageOutput/PdfTableSignature and the private evidence channel are hidden. No parser
or runtime dependency was added. Continuation evidence is independent of serialized cell geometry.

Matching requires exactly one Table on immediately adjacent physical pages, a simple unmerged first
row with2–32 labels, each containing a letter and at least two distinct labels, nonblank body content, identical source frames
and native/OCR families, matching column boundaries within2 unscaled source units, bottom20% to top20%
placement and no intervening extracted narrative. Headers compare Unicode whitespace/NFC, with exact
case and no spelling correction. Each raw/normalized label is capped at512 UTF-16 units and the header
at4096. Blank/multiple/ineligible pages break a chain. Aligned-text/borderless, merged/missing headers,
changed frames, cross native/OCR and semantic identity are unsupported. Images alone do not establish
narrative barriers. Repeated labels/layout can still belong to unrelated logical tables.

Only actual matching continuation parts get versioned pdf_table_continuation backward links to the
central sink's real root/previous source Table IDs, root/previous physical pages and increasing part
index. Root metadata is unchanged; no speculative groups, forward-ID predictions, HTML flattening,
header deletion or calibrated confidence claim. Geometry/text/HTML/IDs/order are unchanged aside from
the explicit link extension. A mismatched middle page can become a new root for a later pair.

The optional output adapter buffers one page under remaining maxElements and a fixed4096 pending
limit (ELEMENT_LIMIT), then emits through the central sink in original order. It retains only one
capped prior signature and checks interruption during admission/matching/flush. The disabled path
emits directly. Source evidence generation shares existing table-work controls. Metadata/HTML payload
limits remain extraction controls rather than a peak-heap or codec guarantee.

Seven owned PDF tests check three-page chains with differing body merges, matching real original IDs,
unchanged text/HTML/coordinates and no implicit cell arrays; small column drift; mismatched labels,
numeric/duplicate/oversized labels, Unicode NFC/spacing and exact case; changed columns/frames;
merged headers; blank/multiple/narrative barriers; chain restart; OCR source images/family isolation;
pending/global limits and cancellation; prior constructors/option copies/null rejection; and four
concurrent partitions through a shared processor. Final Unicode/tolerance additions were replayed
on Java21 after full Java25 verification. Integration checks exact JSON plus pass-through, whole,
structural/text-only and provenance-disabled chunk behavior. Source continuation IDs are deliberately
removed from every re-IDed chunk, including unsplit Tables; compressed originals retain them.

`java21-control.xml` and `java25-control.xml` are actual Tesseract5.5.3/English SINGLE_BLOCK controls of
an owned two-page raster with repeated Item/Qty rows. Both preserve exact page text/HTML, two source
Images and cell geometry, and link the second source Table to the first. Neither is an independent
multi-page corpus, human annotation or semantic-accuracy score. Executable/model identities are
recorded; earlier native/runtime provenance remains in pdf-ocr/runtime-and-packages.json. No external
PDF/model/runtime binaries are committed or downloaded by the library.

`*-corpus-recheck.json` replays the preceding pinned independent single-page Schools crop and its
cell geometry in default continuation-NONE mode:6×10/60 anchors,59/60 text matches/index42 spacing
mismatch, and AnimalSounds ordinary OCR/noTable. These are regression checks, not evidence of
independent continuation accuracy. Manifest/crop/source-rights limitations remain unchanged in
[the prior corpus review](../pdf-table-corpus/README.md); no historical golden/reference rewrite.

Replay with the existing explicit executable/tessdata/fresh base report properties; the new owned
control writes `<report>.continuations.xml`. The external corpus cache/report/DPI/segmentation
properties remain unchanged. Existing installed textless/mixed/unmerged/merged/blank-cell controls
were also replayed. Hosted defaults use deterministic owned controls without real OCR or external
corpus downloads. Temporary JDK/runtime/corpus/formatter caches can disappear after reboot; no Windows
VM needed. Official upstream still observes release/main0.27.28 and explicit known0.27.27 historical
freshness drift in upstream-status.json; no Unstructured PDF execution or parity claim.

Full verification passed 733 Java tests on actual Java21 and25 with zero failures/errors/skips, strict
Javadocs,12 reference-tool guards, pinned formatting and diff checks. Next recommended milestone: conservative multiline native borderless-table cells, with independent
validation and explicit rejection rules. Broader merged/noisy physical scans and semantic continuation
validation remain. Approximate full PDF completion: **76%**, a planning estimate rather than accuracy
or spend.
