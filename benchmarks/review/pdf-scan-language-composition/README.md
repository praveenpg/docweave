# Explicit ordered language-model composition

This study evaluates the existing `TesseractOptions.languages` capability, using the same
cached German and Fraktur model binaries. Every original scan runs under each single
language and both fixed orders, `deu+deu_latf` and `deu_latf+deu`. AUTO, ENGINE_DEFAULT,
source300/600DPI, original RGB pixels, references and association/normalization rules remain
fixed. No new model, Python dependency, preprocessing, production API or default change.

All16 actual cases succeed and complete numeric outputs agree across Java21/25 except
random PDF container identifiers. All eight single-model baselines are unchanged, including
prediction hashes. Both component SHA256/Git-object identities are verified before inference;
ordered models/languages and a distinct ordered-set hash are recorded. A combined-model
result is never attributed to one traineddata binary. The verifier compares each combination
against both single models instead of selecting the better one or routing by reference text.

## Mixed outcomes

The1835 declared-order source has43 character edits/1059 under either combination, versus
104 for German and51 for Fraktur alone. But exact associated lines fall from Fraktur's4
to3, with one missing line retained. On1879, combined exact lines41 trail German's42
while the XML-sequence character diagnostic stays291. For1778 the two orders produce
374 and372 XML-sequence edits versus Fraktur371;1780 produces83 and79 versus Fraktur82.
These are development observations, not a generally better configuration or an order-selection
rule. Only1835 supplies explicit line order; other XML-sequence diagnostics are not logical
page CER. Every missing/extra line and all other metrics remain in the full reports.

## Reproduction and integrity

Run the historical corpus test with this frozen manifest, explicit original/model/executable
cache properties, source-resolution mode and a fresh output. Leave the median probe inactive.
Both local model files must already exist. The harness's optional languages/models arrays
are paired and order-checked; historical single-model profiles retain their original schema.
Use `CompareLanguageReports.java` with the prior source-resolution report, manifest, both
fresh composition reports and fresh verification output. All16 current profiles are mandatory;
only the explicitly declared AUTO/default subset of the older24-case baseline is compared.

Both full builds pass1,022 tests with strict public Javadocs. New owned model-binding controls
check order-dependent identity, immutable lists, swapped/duplicate/missing bindings, changed
bytes and legacy profile retention. Changed private harness/verifier Javadocs pass both JDKs.
An initial owned fixture omitted the mandatory Git-object hash: the full run correctly failed.
The fixture was corrected, the guard was preserved, and both full builds were repeated.
Its initial16 recognition outputs are identical to the finalJava21 report except random
container identity; original logs remain outside Git. No source labels or scoring rules changed.
