# Upstream reference tracking

The historical execution baseline and the latest reviewed release have separate identities.
A source review does not silently regenerate references or establish full parity.

| Track | Commit | Status |
| --- | --- | --- |
| Saved reference release0.27.27 | 347fbbfd3e84e228017f125e5c5dbdc326ef7de2 | Fixture manifest and seven golden datasets remain unchanged |
| Latest reviewed release0.27.32 | 07d978b8c182404b800fba27c01f4a2c633cfffc | Previously reviewed main changes now released; measured PDF runtime remains explicitly0.27.28 |
| Reviewed main | 9a376447d236784a044a4929464179a0164f3329 | Eighteen commits after historical reference; four beyond the reviewed release |

The checkpoint is [benchmarks/upstream-status.json](../benchmarks/upstream-status.json).
The [0.27.32 and subsequent main review](../benchmarks/review/upstream-main-20261021/README.md)
records the now-released prior main checkpoint and four new main commits. The large split-source
provenance safeguard is adopted with explicit JVM omission metadata. The JVM decoder now also
uses strict bounded base64 input; Python certifi and figure/decode behavior retain separate
JVM contracts and no full-memory parity claim. Main changelog version headings
do not replace official release metadata. Historical goldens and executed comparisons stay pinned.

The [0.27.30 release review](../benchmarks/review/upstream-release-0.27.30/README.md)
adds the email metadata fix for readable non-ASCII sender/recipient names. Email remains
outside implemented formats. No PDF or dependency manifest changed; measured runtime and
historical reference identities remain unchanged.
The [0.27.29 release review](../benchmarks/review/upstream-release-0.27.29/README.md)
confirms the already reviewed XLS fix is now released. The PDF source hash is unchanged
from the measured0.27.28 runtime and no dependency manifest changes occur in the release diff;
this does not relabel the execution as0.27.29. Historical reference outputs stay unchanged.
The [release-track review](../benchmarks/review/upstream-release-20261019/README.md) records
exact official source/response identities. The sixth commit adds underlying Python OCR-agent
load-error details and explicit exception chaining. It does not change PDF parser output.
The JVM retains its own explicit typed provider and diagnostic contracts; no new broad
reference execution or Python loader/error-string parity is claimed.

The [subsequent main review](../benchmarks/review/upstream-main-20261019/README.md) records
the seventh commit: padded legacy XLS encryption probing, its owned regression fixture and
an unreleased version bump. It changes no PDF code. XLS/XLSX remain outside implemented formats;
the released0.27.28 identity and historical0.27.27 reference outputs remain unchanged.

Official sources: [reviewed release](https://github.com/Unstructured-IO/unstructured/releases/tag/0.27.32)
and [historical-reference-to-main comparison](https://github.com/Unstructured-IO/unstructured/compare/347fbbfd3e84e228017f125e5c5dbdc326ef7de2...9a376447d236784a044a4929464179a0164f3329).

The previously reviewed five commits cover optional langdetect-rs, unknown HTML element text, table captions,
DOCX per-document style lookup/numbering cache reuse, and XLSX sparse empty-row handling.
The DOCX change is a reviewed upstream performance optimization; its claimed unchanged output
has not been independently rerun here. XLSX is outside implemented formats and language-model
parity remains outside current policy.

The [HTML review](../benchmarks/review/upstream-html-20261016/README.md) adopts a scoped subset:
unknown/legacy font wrappers are transparent, exposing block children without losing surrounding
inline text; the reviewed non-content subtrees are excluded, including literal ix:header.
Java still emits root captions as separate FigureCaption elements rather than placing them in
Table text/text_as_html. MathML/KaTeX, page-number propagation, general inline-wrapper/block
segmentation, other tag policies and jsoup/lxml repair differences remain outside a parity claim.
Shared HTML DOM extraction means the new policy also applies to Markdown EXTRACT_DOM; default
Markdown AST/literal handling and DOCX parsing are unchanged. Upstream shared HTML sanitization
also changed, so no blanket statement about every downstream operation is made.

Official commit links and response hashes are retained in the review evidence. The historical reference pin
and seven historical reference datasets remain unchanged. The new regression tests are owned
Java policy tests informed by source inspection, not fabricated upstream execution results.

## Check freshness

Run from repository root with Java 21 or later and Maven:

```sh
mvn -pl docweave-validation -am test -Ddocweave.upstream.check=true
```

The Java/JUnit checker queries official release/main/tag/compare endpoints, records
response hashes, URLs, ETags, observed commits and changed paths, then fails if the
latest release/tag/main no longer matches its reviewed track. The observed release commit
is compared with latest_release.commit; saved fixture identities remain tied to
reference_release.commit and reference_commit. New release review never equates these tracks
or rewrites historical goldens. API failures and
truncated comparisons also fail. Reports use fresh paths under
docweave-validation/target/upstream-status. Default Maven tests check offline
consistency only; they do not claim a current network observation.

Private CI offers an explicit workflow_dispatch check_upstream boolean. Normal push
and pull-request validation makes no upstream request. No scheduled automation or
publishing is enabled. Before changing compatibility semantics, run the freshness
check when network access is available, inspect drift, and record its impact.

When a new release is reviewed, fetch that exact commit, verify selected implementation
hashes and record each changed behavior. Updating the reviewed release alone is source
triage, not a new execution claim. Before changing reference pins or broader compatibility
claims, regenerate affected references using the real upstream Python implementation and
review output differences.
Keep historical corpus/rendering evidence intact. A current reviewed release establishes source-review currency; the separate historical
pin establishes which version actually generated saved outputs. Neither proves full
functionality or rendering parity. Known supported-scope differences
remain documented in [compatibility](compatibility.md).
