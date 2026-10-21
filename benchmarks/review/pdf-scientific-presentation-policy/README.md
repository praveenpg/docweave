# Existing Unicode presentation policy on original scientific PDFs

This check runs the existing PRESENTATION_FORMS option on all four unchanged original
scientific sources. It preserves the six prior RAW profiles and adds presentation-form
plain lines and presentation-form source paragraphs with page fragments. References stay
at28 paragraphs/111 pairs per profile, with strict unchanged ASCII whitespace scoring.
No new normalization, source replacement, reference repair, model or production API change.

All32 complete case/profile rows agree across actual Java21/25. All24 previous rows,
including candidate hashes, remain unchanged. The option changes the newest PLOS source's
candidate text but no full-paragraph or order-pair scores. Plain-line matches remain
1/9,2/12,0/4,0/3; source-paragraph matches remain6/9,10/12,0/4,0/3. The other three
documents' plain-line candidate hashes are unchanged. No score improvement or reading-order
closure is claimed. This bounded negative check does not justify another normalization rule.

Both focused runs include the standalone Java21 compilation/owned scorer controls and
existing presentation-form correctness, Unicode preservation, geometry, limits and cancellation
controls. The unchanged full production baseline previously passes1,022 tests on both JDKs.
Use the scientific-reading test with this manifest, verified original cache and fresh output;
reuse `../pdf-scientific-reading/VerifyReports.java` for full cross-JVM coverage/equality.
