# Partition progress (unreleased)

A partition call can report optional, content-free progress to a listener supplied for that call. The API is
provider-neutral: it has no UI, job or Studio concepts, and calls without a listener behave exactly as before.

```java
DocWeave processor = DocWeave.create();
PartitionResult result = processor.partition(source, PartitionOptions.defaults(), progress -> {
    if (progress.determinate()) {
        // e.g. EXTRACTING 35/100 pages
        log.info("{} {}/{} {}", progress.phase(), progress.completed(), progress.total(), progress.unit());
    } else {
        log.info("{}", progress.phase());
    }
});
```

## Public types

All types are in `io.docweave.partition`; implementations stay hidden.

| Type | Role |
| --- | --- |
| `PartitionProgressListener` | Functional interface supplied per call: `onProgress(PartitionProgress)`. |
| `PartitionProgress` | Immutable record: `phase`, `unit` (null without units), `completed`, `total` (`UNKNOWN_TOTAL` = -1 when indeterminate) and `determinate()`. A determinate total can be `0` (`0/0`), so check for zero before dividing. |
| `PartitionProgress.Phase` | `PREPARING`, `EXTRACTING`, `ASSEMBLING`, `DONE`. |
| `PartitionProgressReporter` | Strategy-side sink from `PartitionContext.progress()`: `beginUnits(unit, total)` and `unitCompleted(index)`. |

Counters in `PartitionProgress` are `long` so the value type can describe large counts; the reporter takes `int`
totals and indices because it tracks completed units in a bit set and the built-in unit, the PDF page, is
`int`-indexed.

The listener is passed to `DocWeave.partition(DocumentSource, PartitionOptions, PartitionProgressListener)`.
`PartitionContext.progress()` and the `DocWeave` overload are default methods, so existing custom contexts and
facades compile unchanged. A custom `DocWeave` implementation that does not override the overload reports only
`PREPARING` before and `DONE` after its ordinary call.

## Semantics

Events never contain document text, filenames, metadata or element values. Counters measure work units, not time:
OCR, layout and table work vary widely between pages, so a unit percentage is not a time estimate.

| Phase | Meaning |
| --- | --- |
| `PREPARING` | Remote acquisition, loading, format detection and strategy selection. Indeterminate. |
| `EXTRACTING` | Format strategy running. First reported indeterminate; a strategy with a reliable unit count then reports `0/total` and one event per completed unit. |
| `ASSEMBLING` | Every unit finished and the strategy returned; the immutable result is being assembled and any remote source handle released. Carries the final counters. |
| `DONE` | Assembly and remote release succeeded. Reported once, immediately before the call returns. Carries the final counters. |

- Phases are reported in order, each at most once, and `DONE` only after assembly succeeded. `ASSEMBLING` can show
  `completed == total`; treat overall completion as reached only at `DONE`.
- The total is declared once and never changes. `completed` increases by one per event, never exceeds the total,
  and each unit index is counted exactly once even when units complete concurrently or are reported twice.
- The built-in engine delivers events synchronously and serially (never concurrently) while holding a per-call
  `ReentrantLock` (not a monitor, so a blocking listener does not pin a virtual-thread carrier). A strategy
  completing units on worker threads can make the delivering thread vary. Keep listeners short: extraction waits
  for them. Never wait inside a listener for work that itself reports progress for the same call; that deadlocks.
- Failure or cancellation stops reporting: no `DONE` follows. Once any reporting thread of the call is observed
  interrupted, later extraction events from every thread are discarded. Nothing is reported after the call returns
  or throws, even through a retained `PartitionProgressReporter`.
- `ASSEMBLING` and `DONE` are reported only after the engine's last extraction cancellation checkpoint passed. If
  releasing a remote source fails, the call throws after `ASSEMBLING` without `DONE`. If an interrupt arrives after
  the last checkpoint, the call still returns its result (with the interrupt flag set, as before) and `DONE` is
  still reported: `DONE` always accompanies a returned result.
- Anything the listener throws, including undeclared ("sneaky") checked exceptions and errors other than
  `VirtualMachineError`, is caught and ignored for that event; later events are still offered and the result is
  unchanged. A swallowed `InterruptedException` restores the interrupt flag.

## Units per format

| Format / path | Unit | Total | A unit completes when |
| --- | --- | --- | --- |
| PDF native (`FAST`, `AUTO`, low-level native factories) | `pages` | page count | native text, graphics and tables, any OCR of textless or mixed pages, and output of all of the page's elements finished |
| PDF `OCR_ONLY` | `pages` | page count | the page was rendered and recognized and its elements were emitted |
| PDF `HI_RES` | `pages` | page count | word recognition, provider table layout, cell OCR and emission for the page all finished |
| TXT, HTML, Markdown, CSV, TSV, XML, DOCX, custom strategies without reporting | none | indeterminate | — |

PDF pages are processed sequentially today, so PDF events arrive on the calling thread. The page total is declared
right after the document opens and the `maxPages` check passes; source-tag reading (when enabled) happens after
`0/total` is reported. A page with no output still counts once its work finished.

## Custom strategies

A `Partitioner` with a reliable unit count calls `context.progress().beginUnits("units", total)` once and
`context.progress().unitCompleted(index)` only after all work and emissions for that unit finished. Reports before
`beginUnits`, repeated indices, indices outside the total and reports after the call ended are ignored. Negative
indices, blank units and negative totals throw, with or without a listener, so behaviour does not depend on
whether progress is observed. Without a listener the context returns a shared no-op reporter.

A `PartitionContext` that decorates another context (for example to observe emissions) must forward
`progress()` to the wrapped context; otherwise the inherited default returns the no-op reporter and the call loses
its unit progress. `progress()` stays available after the context completes, unlike `emit`, because the reporter
itself discards late reports.

## Limitations

- Batch services (`DocumentBatch`, `BatchProcessor`) do not yet accept a per-document listener.
- Retries are the caller's concern: each call has its own independent counters, so a retried call starts again at
  `PREPARING`.
- Only PDF reports units. DOCX has no reliable page count before layout, and text formats have no meaningful
  pre-parse unit count.
