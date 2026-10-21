# Newly selected scientific paragraph check

The source was selected before fetching or inspecting tags: the first research-article
result for the recorded official-publisher/date query. Publisher XML confirms DOI
10.1371/journal.pone.0360958 and publication on2026-10-16. Original source sizes/hashes and
the unchanged paragraph reference rule were frozen before any DocWeave extraction.
No source replacement, reference repair or production tuning followed the result.

All five prior profiles process the complete original PDF on Java21 and25. The unchanged
reference helper selects three full paragraphs: the first abstract's paragraphs and up to
eight direct paragraphs of the first top-level body section, retaining its actual count.
Exact paragraph matches are **0/3 under every profile**, including explicit source-tagged
paragraphs with discretionary joining. Preserved order pairs are **0/3**, with all three
pairs unresolved. Every whole-document extraction succeeds; zero exact full-paragraph
matches is not zero extracted text. The source-soft-hyphen profile changes the emitted
text but does not recover an exact selected paragraph.

Both JVM reports agree for all five complete result rows, including full candidate hashes
and denominators. The earlier two-paper improvement remains unchanged; it must not be
generalized to this source or used as population accuracy. This case was newly selected
after implementation, but one same-publisher document is not representative held-out
coverage. Its observed outcomes now form development evidence.

`selection.json` records the source-only choice. `FreezeScientificParagraphHoldout.java`
checks DOI/date and input bounds and freezes reference hashes without invoking extraction.
Its Java21/25 outputs agree except the freeze timestamp. The source-only parser/reference
helpers and scoring code are unchanged. Compiling the freezer against the validation test
classpath is required because its shared helpers are package-private:

```sh
javac --release 21 -cp "$classpath" -d "$classes" benchmarks/tools/FreezeScientificParagraphHoldout.java
java -cp "$classes:$classpath" dev.docweave.validation.FreezeScientificParagraphHoldout \
  benchmarks/review/pdf-scientific-paragraph-holdout/selection.json "$cache" "$new_manifest"
```

Run `PdfScientificReadingOrderTest` with explicit scientific-reading cache, this manifest,
and a fresh report path. Reuse `../pdf-scientific-reading/VerifyReports.java` to check
coverage/equality. Strict private freezer Javadocs pass on both JDKs. Raw sources and
diagnostic output remain outside Git. No production/default/dependency change or new
Central release accompanies this evidence.
