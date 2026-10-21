# Explicit PDF credential configuration

The new public `PdfPasswordProvider` interface supplies fresh character arrays for one opening
attempt. Built-in `PdfPartitioner` and `PdfFontInspector` services expose `.withPasswords(...)`
without altering existing factories, options records or discovery. The shared hidden loader
owns opening/clearing policy; existing extraction/font services retain document closing,
permission enforcement, traversal, emission and resource policies.

No password is requested for ordinary unencrypted or permitted empty-password documents.
One explicit lookup follows a required-password failure or an encrypted empty-password open
whose permissions prohibit extraction. The rejected preliminary document is closed before
lookup. Actual PDFBox access permissions still apply after reopening; owner credentials are
handled by its standard security handler, without changing permission flags or removing security.
Returned arrays clear in a finally block on success, invalid credentials, lookup cancellation
or parsing failure. PDFBox requires a temporary String; this is not a JVM memory-erasure guarantee.
Provider messages/causes are deliberately excluded from lookup failures. No external credential
store, prompt, guessing loop, credential cache, Python dependency or native process is introduced.

## Verification

`PdfPasswordTest` authors owned plaintext native/tagged and scanned sources, then applies real
standard AES128/AES256 encryption. Independent source text, complete table text, cell geometry
and image/font expectations are checked through normal public source routing. The controls cover:

- User passwords, owner access, restricted extraction and permitted/restricted empty-user passwords.
- Unicode AES256 credentials, missing/wrong credentials, exactly one lookup and cleared arrays.
- Native/tagged/provider-only/native-first/tagged-first output and required provider calls.
- Actual OCR-provider orchestration, distinct PAGE/CELL providers and explicit orientation.
- Standalone font inventory, its null lookup hint, unchanged input and page limits.
- Sanitized checked/unchecked lookup failures, interruption flags and clearing before propagation.
- Twelve concurrent named documents with fresh per-call credentials and bounded task waits.
- Lazy/private configuration, input rejection before lookup, output/page limits, malformed input
  without lookup and replacement of an existing configured provider.

Full offline `clean verify` passes on actual Java21.0.12.1+12-LTS and25.0.4+7-LTS:
1008 tests each, zero failures/errors/skips and strict public Javadocs. Strict private Javadocs
pass for all changed production classes/interfaces and the new test on both runtimes. The pinned
formatter and `git diff --check` pass. Initial fixture compilation errors were corrected before
these final runs; no source case or assertion was excluded to obtain passing results.

Reproduce with `mvn -B -ntp clean verify`, or run the focused seven methods with:

```sh
mvn -B -ntp -pl docweave-pdf -am test \
  -Dtest=PdfPasswordTest -Dsurefire.failIfNoSpecifiedTests=false
```

This validates opening/configuration and preserved extraction contracts, not broader PDF text,
table or scan accuracy. Public-key/certificate encryption and arbitrary external password-store
deadlines are outside this credential interface. Existing PDF quality acceptance gates remain open.
Draft beta.6 notes carry the feature; no release/tag/publication is performed here.

The contemporaneous strict Java upstream check detected/reviewed one new XLS-only main commit;
see [source review](../upstream-main-20261019/README.md). Released0.27.28 and historical0.27.27
reference identities stay separate. No upstream reference output was regenerated.
