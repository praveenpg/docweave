# Explicit source-owned inline script grouping

The frozen manifest retains all four original scientific PDF/XML identities, all28 reference
paragraphs and111 order pairs per profile. It adds two uniformly applied profiles alongside
all11 earlier profiles: source-owned inline scripts with physical-hyphen wrap treatment,
with and without the existing logical paragraph assembler. Reference text never enters
extraction. These are reused development documents, including the case named `holdout`;
the name does not establish independent held-out acceptance.

The explicit `InlineScripts.NEARBY_SMALL_GLYPHS` policy identifies smaller native glyphs
relative to median body height. It requires horizontal adjacency, a bounded vertical shift
and one nearby body baseline. Ambiguity retains historical grouping. Sorting/grouping uses
comparison baselines only; original glyph identities, characters and enclosing bounds remain.
Source ownership and single-column guards remain in place. Original pre-grouping space-joined
character limits and shared page work/cancellation budgets apply before accepting output.

| Original | Earlier physical-wrap + assembly exact | New script profile exact | New script + assembly exact |
| --- | ---: | ---: | ---: |
| recent |8/9|7/9|8/9|
| next |12/12|10/12|12/12|
| elife |0/4|0/4|0/4|
| holdout |0/3|2/3|3/3|

The improvement follows source inspection of raised runs that made otherwise complete
paragraphs fail the single-column intersection guard, causing fallback text to interleave
sidebar material. The new policy retains every independent element; no publisher-specific
exceptions, normalization changes, removed sidebar text or best-of settings are used.
This does not solve eLife's Unicode/structural differences or the explicit source whitespace
behind the recent case's remaining miss. No general document accuracy or script-semantics
claim follows from these strict reference matches.

`VerifyInlineScriptReports.java` requires52 complete paired case/profile rows, all44 earlier
rows identical, all reference hashes and denominators unchanged, and equality across Java21
and Java25. Its five arguments are manifest, reports21/25, the original physical-hyphen-wrap
Java21 report, and a new receipt path. Reports contain numeric scores and hashes only;
no third-party PDF, XML or source text is redistributed.

Owned JVM controls exercise raised/lowered positions and original bounds, competing baselines,
distant columns, ordinary/zero-size glyphs, actual tagged PDF grouping with an independent
sidebar, untagged fallback, original character budgets, legacy constructors, immutable copies,
shared work limits/cancellation and mixed/unknown assembly policies. Old grouping is the default;
the options record gains a fifth component while preserving its three/four-argument constructors.
The interpretation is geometric and caller-selected, not mathematical markup recognition.

No OCR engine/model or Python test/dependency was introduced. No beta candidate or publication.

Full clean verification passes1,069 tests on each actual Java21/Java25 runtime with zero
failures, errors or skipped tests, including the original-source experiment and public Javadocs.
Strict private Javadocs also pass for all changed production classes, the owned script/assembly
tests and the verifier. `validation.json` confirms complete paired equality and all44 prior
rows unchanged. `upstream-status.json` records a successful official release/main freshness
check: reviewed0.27.30 remains current; measured upstream comparison remains0.27.28.

After those full runs, a defensive immutable copy of the hidden grouped-line list and an owned
immutability assertion were added. All30 focused script/tagged-paragraph/hyphen/assembly controls
pass on both JDKs against that final source; final strict private Javadocs pass as well.
