# Explicit physical-hyphen wrap spacing

This development experiment adds two uniformly applied profiles to the frozen scientific
paragraph-assembly protocol. All four full original PDFs, publisher XML hashes, 28 reference
paragraphs and 111 order pairs per profile remain unchanged. No reference text enters extraction.
The manifest was frozen before the candidate run. No publisher documents or text are redistributed.

`SOURCE_PARAGRAPHS_LINE_WRAPS` uses the explicit new `WordWrap.SOURCE_LINE_WRAPS` policy;
`SOURCE_PARAGRAPHS_LINE_WRAPS_ASSEMBLED` also applies the existing logical paragraph assembler.
The policy retains physical ASCII hyphens and omits generated spacing only at unique native
letter-hyphen/letter endpoints inside already validated source paragraphs. Explicit source
spaces, ambiguous endpoints, numerical/punctuation boundaries, large vertical gaps and page
boundaries remain unchanged. Geometry does not establish word semantics; this interpretation
is opt-in. Existing policies and original pre-join budgets remain unchanged.

| Original | New wrap policy exact paragraphs | With existing assembly | Prior soft-hyphen + assembly |
| --- | ---: | ---: | ---: |
| recent | 7/9 | 8/9 | 7/9 |
| next | 10/12 | 12/12 | 12/12 |
| elife | 0/4 | 0/4 | 0/4 |
| holdout | 0/3 | 0/3 | 0/3 |

These are strict paragraph matches on development documents, not general extraction accuracy
or held-out acceptance. Both zero-match cases and every denominator remain in the reports.
The earlier difference audit localizes only some misses; this policy does not solve Unicode
space differences, all remaining physical-wrap cases or complex paragraph ownership/order.

The Java verifier requires all 44 case/profile rows to agree across Java 21 and 25, every
one of the original 36 rows to remain identical, and all reference identities/denominators
to remain intact. Run it with Jackson on the classpath and five arguments: manifest, Java21
report, Java25 report, the prior paragraph-assembly Java21 report, and a fresh receipt path.
Do not regenerate historical baseline reports.

Owned JVM controls cover real tagged PDFs, soft-hyphen precedence, explicit source spaces,
untagged fallback, cross-page assembly, pre-shortening budgets, zero-width whitespace,
ambiguous glyphs, numeric/punctuation boundaries, cancellation and the shared work limit.

Full clean verification on actual Java21 and Java25 passes 1,061 tests each with no failures,
errors or skipped tests, including this opt-in original-source experiment and public Javadocs.
Strict private Javadocs also pass for the changed production classes, new owned test and verifier.
`validation.json` records paired equality and unchanged baseline rows; the reports contain
numeric scores and hashes only. No beta candidate or release was prepared.
