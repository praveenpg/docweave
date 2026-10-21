# Independent native-row ambiguity control

Two newly authored PDFs use the same words, font and original source glyph positions.
One paints a single row with two multiline cells; the other adds a horizontal divider
creating two logical rows. Original native text and complete glyph geometry are exactly
equal between cases. Each model-like candidate has two columns and two native baselines.
These are owned independent controls created after freezing the NVIDIA experiment;
they are not held-out publisher accuracy labels or a new training corpus.

The unchanged synchronized-baseline gate accepts both. The unchanged open native-gap
check finds one separator in both. That would produce two rows in each case, falsely
splitting the single logical row. The two-row positive remains valid. The experimental
algorithms do not read the differing painted divider, demonstrating that native text
geometry alone cannot distinguish these cases. No threshold is adjusted to hide the
counterexample. Full per-case outcomes remain in java21.json.

This prevents promoting the NVIDIA native-gap experiment to a general production
row-refinement policy. Its date/year exact-presence improvements remain recorded,
alongside unchanged0/110 strict scores. This is a counterexample to the experimental
gates, not evidence of a current production extraction failure: the integrated native-
first pipeline may recognize ruled tables before consulting a model. Native source
paint/structure and independent transfer evidence would be needed for a future strategy.
The declared provider header-prefix continuation feature is separate and unchanged;
it only links existing tables using explicit provider roles and never splits rows.

Compile ProbeNativeRowAmbiguity.java together with AuditSynchronizedModelRows.java and
RefineSynchronizedModelRows.java on the existing Java21 validation/test runtime classpath.
Run dev.docweave.partition.ProbeNativeRowAmbiguity with one fresh numeric report path.
Both owned PDFs are generated in memory and are not distributed as binary fixtures.
The source hashes and authored logical row counts remain in the report. The program
requires the frozen gate to retain the counterexample; future algorithms need separate
evidence rather than silently changing this historical diagnostic.

Java21 compilation, pinned formatting and strict private Javadocs pass. Product code
has not changed since8163b35's full1,175-test verification. No redundant reactor or
Java25 run is claimed for this benchmark-only addition. No release or public change.
