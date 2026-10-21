# Explicit native-first table composition

A caller-selected production factory now retains accepted native tables and adds only disjoint
provider/model tables. On the fixed development corpus it combines native strengths with existing
model-only coverage, without changing source labels, model weights, thresholds or defaults.

## Paired Java21/25 outcomes

| Case | Provider-only native-text route | Explicit native-first route |
| --- | --- | --- |
| financial disclosure |59x5;102/440 CSV positions |88x5;440/440 |
| transport procurement |14x11;152/154 CSV positions |14x11;152/154 |
| education statistics |51x7;72/350 CSV positions |50x7;339/350 |
| recent issuer statements |59/59 issuer HTML ordinal rows |59/59 |
| scientific cycle |43/43 exact span/text anchors |43/43 |

All cases complete SUCCESS on both runtimes. Financial/procurement/scientific selected tables use
native closed-grid topology; statistics uses native partial-rule geometry. Both recent statement
tables use the provider/native-glyph method because the native policy finds neither table. The
same native policy, RULED_AND_PARTIAL_TEXT, is applied uniformly to every source—not chosen by
case or text. The eleven statistics header differences and two procurement differences remain.
No source text is repaired, labels shifted, prediction fragments joined or missed references dropped.

Financial/procurement/statistics parent IoUs are0.993415/0.944868/0.991469; recent operations/balance
remain0.974409/0.979576; scientific is the native closed-grid0.926999. These are geometry association
measures, not confidence values. CSV positions, issuer rows and scientific span/text anchors stay
separate denominators; they are not pooled into a PDF accuracy score. Model row prediction remains
59/51 on the older financial/statistics sources; composition does not repair or retrain the model.

The complete original source documents run once per profile/JDK, with no reference/page/ROI/text
supplied to extraction. Runtime metadata records actual document-wide layout calls and returned raw
model-table counts. Per-selected-case counters repeat the same document totals and must not be
summed across selected pages. No OCR call occurs. Unlabelled tables elsewhere in these documents
are not declared correct. These development sources are not held-out acceptance or broad scan proof.

## Production contract

`PdfPartitioner.createWithNativeTablesFirst` is a public interface factory returning a hidden
implementation. It uses `PdfOptions` for explicit native policy and `PdfNativeLayoutOptions` for
rendering and combined anchor limits. Existing factories/defaults remain unchanged. No dependency,
model download, implicit discovery, executor or provider lifecycle transfer is introduced.

Native tables and exactly detector-unclaimed glyphs are staged before output. The original closed/
double/horizontal/partial-rule paths expose that ownership directly; it is never inferred by deleting
every glyph inside a table envelope. Run-based aligned/multiline policies are rejected at construction
by this new factory because they do not expose that glyph-stage contract; their older factories still
work. All quarter-turn/crop/UserUnit handling is preserved. Arbitrary native angles remain unsupported
by this composition, as with the provider-only native-text strategy.

Every provider candidate is completely validated before selection. Any positive interior overlap
with an accepted native table rejects the entire provider candidate; exact edge contact is allowed.
Rejected overlaps leave unclaimed text ordinary. Admitted provider tables use only the staged
unclaimed glyphs. Provider I/O/invalid topology/deadline failures remain visible even if native tables
exist. The provider still runs on native-text pages; textless pages retain images/warnings without
provider calls or OCR. Native-only budget failure can abort before rendering/provider execution.

Accepted native anchors plus all raw provider anchors—including candidates later discarded—must
fit maxTableAnchors. Native recognition, provider validation, overlap and assignment share one page
work counter. Existing per-table slot/text, source/render/output and cancellation limits remain.
No document-wide deadline or peak-memory claim follows. Native methods/geometry/continuation signatures
are retained; model tables do not invent native continuation evidence. Both table families record
`pdf_table_selection_policy=native_first_disjoint_v1` so the chosen precedence is visible.

## Frozen evaluation and reproduction

`manifest.json` was frozen before native-first capture. It pins unchanged source/reference protocols
and original provider-only comparisons. The same pinned detector/structure graphs run with four
native threads, KEEP_HIGHER_SCORE structure merges,150DPI/16M pixels and explicit1024 combined anchors.
The configuration is uniform before observing results; there is no text-driven automatic selector.

Use [the existing live capture command](../pdf-native-provider-layout/README.md#reproduction), adding
`native-first` as the final argument. It writes `native-first.json` instead of `native-live.json`.
The previous seven-argument provider-only mode remains available. Use the unchanged CSV comparator
with `--profiles native-first`. For original recent/scientific Java evaluators, use the same caches/
new report paths and add these flags respectively:

```sh
-Ddocweave.pdf.current.business.model.native.first=true
-Ddocweave.pdf.scientific.model.native.first=true
```

The full `.cache`, `.report`, `.model.captures` and `.model.report` properties remain as documented
in the earlier current-business/scientific evidence. Neither evaluator downloads inputs or invokes
models. Source identities and original labels remain independently checked; score misses are retained.

Nine owned tests cover native topology plus disjoint model tables, edge contact, partial overlap
rejection, unclaimed text/images once, explicit unclaimed glyphs inside preferred envelopes, provider
failure causes/invalid overlap output, combined anchor counts including rejected candidates, budgets
without cell metadata, a shared exhausted work counter, model-only/textless pages, unsupported run
policies and inherited quarter-turn/crop/UserUnit frames. Actual Java21/25 clean verification passes
949 tests and strict Javadocs,36 offline tooling guards and pinned formatting. The capture helper's
manual public/private Javadocs pass both runtimes. No model is loaded by ordinary verification.

Committed reports contain only numeric geometry/counts/hash identities; downloaded sources/CSV/XML/
HTML text, model graphs and full captures remain local/fetch-only. Earlier provider-only misses and
native controls are preserved rather than rewritten. Fresh official upstream metadata remains
0.27.28/main1b02b4d versus historical0.27.27; the strict freshness assertion reports the same reviewed
release drift, not parity/freshness PASS. No old reference pin is changed.

See [the contract](../../../docs/pdf-support.md#explicit-native-first-table-composition),
[deferred draft notes](../../../docs/releases/0.1.0-beta.6.md) and
[progress ledger](../../../docs/pdf-progress.md). This closes explicit composition, not the model-only
row-quality, held-out modern-source, historical/degraded scan/text or calibration gates. Release
preparation remains deferred; no candidate/tag/prerelease/Central action occurs.
