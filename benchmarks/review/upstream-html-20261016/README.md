# Scoped upstream HTML review — 2026-10-16

The live GitHub check detected two commits beyond the old reviewed main d4008b99:
DOCX style-lookup caching and XLSX sparse empty-row handling. Released 0.27.27 remains
at347fbbfd; main3253c2b0 is five commits ahead. `upstream-status.json` preserves the
final live metadata observation with response hashes/URLs/ETags. `review.json` records
official commit/compare response hashes, patch hashes and actual Java source identities.
The initial failed drift check was expected; the reviewed-checkpoint recheck passes.

Official sources:

- [Unknown HTML text fix](https://github.com/Unstructured-IO/unstructured/commit/804e8a10d488a9567b539c3424a891fcd07e252c).
- [Table caption fix](https://github.com/Unstructured-IO/unstructured/commit/d4008b99a12f2f417f3270711bd37d89d0d7f1be).
- [DOCX style cache](https://github.com/Unstructured-IO/unstructured/commit/e80c88c0a0f9c02883bdcc18a169ab766ba2444a).
- [Release-to-reviewed-main changes](https://github.com/Unstructured-IO/unstructured/compare/347fbbfd3e84e228017f125e5c5dbdc326ef7de2...3253c2b0f6b0a5f0808910c085675672aff718f3).

## Adopted subset

Java already retained unknown inline text, but block descendants under an inline unknown/font
wrapper could be collapsed into prose rather than emitted as paragraphs/lists/tables. A small
internal HtmlDomPolicy now unwraps unknown/legacy font containers after original depth checks
and explicit hidden-content removal. The reviewed audio/canvas/datalist/dialog/head/iframe/
ix:header/noembed/noframes/object/select/svg/textarea/title/video/xml subtrees are removed first,
so their content cannot be promoted by unwrapping. The conventional ix prefix is literal.
No CSS rendering, namespace inference or external resource retrieval is performed.

Eight owned Java tests cover Unicode links/adjacency, block boundaries, headings/list parents,
non-content tails, depth-before-normalization/hidden exclusions, caption policy, shared Markdown
DOM extraction and preserved nested-table code-point intervals. Strict Javadocs and all603Java/
12Python guard tests pass. No production public API/dependency changes or new format was added.

## Boundaries retained

Captions remain separate FigureCaption elements. Unlike unreleased upstream main, they do not
appear in root Table text/text_as_html; caption-only input can yield a caption without a Table.
MathML/KaTeX, page-number propagation, general known-inline-container block segmentation, other
tag rules and jsoup/lxml DOM-repair differences remain outside compatibility claims. For example,
jsoup promotes a custom child illegally placed in head into body content; source origin cannot
be reconstructed by pretending it stayed in head. Existing canonical cell/span policy is retained.

These tests are owned Java policy cases informed by official source/tests. No fresh Unstructured
parser execution, NLP/model installation, browser/Word rendering or new upstream golden output
was produced. Historical released references and measured Word/corpus packets are unchanged.
The DOCX change is triaged as per-document cache reuse, not independently measured Java/Python
performance parity. XLSX is unsupported; optional language detection remains outside current policy.
No Windows VM was needed. Next broaden independent real-world DOCX nested/text-box/revision coverage.
