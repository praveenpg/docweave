# Disjoint-projection column experiment — not promoted

One bounded attempt after the intervening graphics, margin and list-boundary fixes.
The proposed explicit strategy searches horizontal source-box endpoint intervals
without paired baselines, retaining existing four-unit side guards, three-line band
minimum, vertical overlap/barrier checks and 80percent source-line support. Inference
has a512-line cap and uses the existing page work/cancellation budget. Existing modes
and defaults are unchanged by the candidate.

Three owned tests pass, including unequal11/12-unit leading, a16-unit horizontal gap,
a spanning title, sequential/single-column fallback, cancellation and cap preservation.
Sixteen focused methods pass including historical column/list tests. Owned column
reconstruction alone does not establish source-document paragraph improvement.

The single transfer check keeps all143 publisher paragraphs and4942 reference order
pairs per profile across the same three whole original PDFs. Both native compound
columns and source-line-wrap paragraph assembly were run with baseline and candidate:

| Complete selected source | Native baseline/candidate unique | Assembled baseline/candidate unique |
| --- | ---: | ---: |
| recent PLOS,33 references | 4 /4 | 24 /24 |
| next PLOS,93 references | 54 /54 | 83 /83 |
| eLife,17 references | 1 /1 | 1 /1 |
| Total,143 references | 59 /59 | 108 /108 |

Every reference identity remains unchanged; the nine duplicate paragraphs on next
PLOS remain unresolved. No matched pair is reversed. All six baseline/candidate
comparisons preserve the complete non-ASCII-whitespace character populations; this
check does not prove reading order or replace exact paragraph scoring. No source
hyphens, NBSP, punctuation or references were repaired or removed. The report retains
all individual paragraph outcomes, offsets, hashes and failed/missing/duplicate counts.

**Decision: not promoted.** There is no complete-paragraph gain on the originals.
The source-preserving standalone owned result does not justify another public option.
The candidate implementation and tests are retained only as an unapplied gzip-compressed patch at
f5d7ada. Decompress `candidate.patch.gz` and apply it to that baseline to reproduce with Java21 and the existing scientific
references/scorer. It is not compiled or shipped as production functionality. No
full-reactor/Java25/model grids were repeated for this rejected candidate; the last
production full verification remains1245 passing Java21 tests. Raw captures and the
JVM transfer launcher remain in the private run cache, not runtime dependencies.

Backlog: sidebar/paragraph fidelity remains unresolved. A future attempt needs a
counterexample that isolates a source-supported ordering change and demonstrates
original-document improvement; do not repeat this projection or threshold search.
