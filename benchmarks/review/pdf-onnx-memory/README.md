# ONNX provider process memory

The [2026-10-22 validation](../pdf-validation-2026-10-22/README.md) packaged provider study
failed its frozen memory gate. Its real YOLOX/financial-TATR profile peaked at child VmHWM
1,855,972, 1,843,752 and 1,930,616 KiB, against a ceiling of 1,572,864 KiB (1.5 GiB). That
original report is unchanged.

This folder records the allocation study that explains the failure, the runtime fix, and the
rerun of that same study, unchanged except for the ONNX adapter JAR identity.

## Reproduction and phase attribution

`diagnostic-frozen.txt` (SHA-256 `384d01b8…09b7`) was frozen before execution. It specifies:

- **Workload:** the original real-profile worker: same Apple original (`dc0cf747…5aa5`), detector
  (`134301ca…95f3`), structure graph (`0d422609…d813`), options, two-call admission and the
  cold, sequential and two-concurrent sequence.
- **Runtime:** the same eight JARs, byte-identical to `provider-runtime-jars.sha256`.
- **JVM options:** the same: `-Xms32m -Xmx512m`, headless.
- **Instrumentation:** at each phase boundary it reads VmHWM, VmRSS and heap use and commitment.
  A 5 ms daemon sampler records the per-phase peak RSS. No forced GC.

`baseline-phases.txt` reproduces the failure: final VmHWM 1,834,444 / 1,957,424 /
1,899,616 KiB, with every attempt emitting five tables.

| Phase | VmHWM after the phase (KiB) | Java heap committed |
| --- | --- | --- |
| Provider construction (two graphs) | 1.29–1.31M, then RSS settles at 1.07–1.10M | 35 → 244 MB |
| Cold attempt | 1.47–1.49M | unchanged |
| Sequential attempt | 1.54–1.57M | +0–42 MB |
| Two concurrent attempts | **1.84–1.96M** (sets the peak) | +77–120 MB |
| Close | RSS falls by about 600 MB | — |

Closing the provider releases its memory, so this is not a confirmed leak. Two measured causes
explain the peak:

- **Heap-resident graph copies.** The 216.6 MB and 115.8 MB graphs were read into heap `byte[]`
  arrays and passed to `createSession(byte[])`, whose JNI path copies them again. G1 then keeps
  roughly 210 MB of heap committed for the rest of the process.
- **Retained ONNX Runtime arena regions.** The default per-session CPU arena keeps activation
  regions resident after each run and holds one region per simultaneous run, so the concurrent
  round adds 300–400 MB.

## One change per frozen hypothesis

Each candidate replaced only the ONNX adapter JAR and was measured with the same frozen
diagnostic (three child JVMs). JAR hashes are in `candidate-jar-identities.sha256`.

| Candidate | Frozen in | Change | Final VmHWM (KiB) | Result |
| --- | --- | --- | --- | --- |
| C1 | `hypothesis-frozen.txt` | `setCPUArenaAllocator(false)`, `setMemoryPatternOptimization(false)` | 1,686,356 / 1,684,260 / 1,750,640 | Partial; reverted |
| C2 | `hypothesis2-frozen.txt` | Exact-size off-heap direct snapshot, hashed and passed to `createSession(ByteBuffer)` | 1,600,116 / 1,607,340 / 1,591,436 | Partial; construction peak 0.91–0.94M, heap stays at 35 MB |
| C3 | `candidate3-frozen.txt` | C2 plus C1's two session settings, nothing else | **1,244,504 / 1,380,252 / 1,417,076** | Below the ceiling |

C1 alone also left about 1.5 GiB resident after close: freed operator buffers stayed with the C
allocator. C3 combines the two separately measured causes and was declared the last ONNX
candidate of this pass before it ran.

## Original gate rerun (`candidate-study/`)

The complete study ran with protocol `dc2398b4…1687` unchanged: three profiles, nine child
JVMs, 36 attempts, `-Xmx512m`, a 180-second deadline, two concurrent calls and the same sources
and models. Only the layout-onnx JAR differs (`runtime-jars.sha256`). The supervisor's checks
report `all_declared_gates_pass=true`.

| Profile | Original VmHWM (KiB) | Candidate VmHWM (KiB) | Outputs |
| --- | --- | --- | --- |
| real-yolox-fin | 1,855,972 / 1,843,752 / 1,930,616 (fail) | **1,462,472 / 1,341,636 / 1,377,108** (pass) | 5 tables per attempt; hash `743b0caf…` identical to the original |
| fixed-structure | 175,476 / 170,904 / 167,896 | 177,860 / 171,596 / 173,176 | hash `0a25a06d…` identical |
| fixed-anchor-limit | 167,512 / 168,028 / 171,520 | 174,708 / 173,788 / 172,228 | 4 × `TABLE_LIMIT` per child, unchanged |

- Every child exits 0 before its deadline, and every provider rejects calls after close.
- Real-profile wall time is 10.6–10.9 s, against 10.6–10.7 s originally.
- The small fixed-graph profiles rise by up to about 4% in VmHWM; they remain far below the ceiling.
- The largest real child keeps 7% headroom. Child-to-child variation is about 120 MB.

### Rerun on the final source (`final-study/`)

The Javadoc, formatting and test-visibility edits made after that run changed the adapter JAR to
`dc2dcbb4…37b1`. The same unchanged study was therefore rerun on the final packaged JARs. All
declared gates pass again:

- real-profile VmHWM **1,358,500 / 1,352,236 / 1,335,788 KiB**;
- identical output hashes;
- 12 `TABLE_LIMIT` outcomes in the limit profile;
- closure rejection in every child;
- real-profile wall times of 10.6–10.9 s.

## Transfer: another original source

`transfer-amazon.txt` (`TransferDiagnostic.java.txt`) runs the same four-attempt, two-admission
workload on the 14-page Amazon Q4 2025 original (`ad8d84cb…42915`, Workiva).

| JARs | Tables per attempt | Logical-output hash | VmHWM (KiB) |
| --- | --- | --- | --- |
| Original, run 1 | 7, 7, 7, 7 | `079e17f5…` | 1,726,224 |
| Original, run 2 | 7, 7, 7, 7 | `079e17f5…` | 1,736,740 |
| C3 | 7, 7, 7, 7 | `079e17f5…` | 1,520,180 |

Output is identical, and the peak falls by about 210 MB. Amazon is not part of the frozen gate.

A first version of this diagnostic sorted only top-level metadata keys. Nested immutable maps
iterate in a per-JVM order, so its hashes differed between runs regardless of the JAR. The
recursive canonical fingerprint above replaced it, and two separate original-JAR JVMs now
agree with C3.

## Owned tests

`ManagedTableSessionLoadingTest` (3 tests) checks:

- the hashed and loaded graph is an exact, direct, read-only snapshot of the file;
- `maxModelBytes` admits a graph of exactly its size and rejects one byte more with a typed
  failure before any session exists;
- identity is verified over the loaded bytes;
- a missing graph fails.

The existing ONNX suites pass unchanged, covering concurrency, admission, interruption,
deadlines, closure, detection crops, YOLOX, topology and image transforms: 41 tests in the
module. Process memory is measured only in the operational study, not asserted in unit tests.

## Limits

- These are single-host observations, not a total process-memory bound. ONNX Runtime's
  initializer and prepacked-weight memory, the C allocator's retention and PDFBox rendering all
  remain unbounded by the Java limits.
- The graph snapshot uses direct memory, which defaults to the heap maximum.
- Disabling the arena returns per-operator buffers to the system allocator. No latency change
  was measurable in this study, but other graphs or hosts may differ.

## Independent review before integration

The final adapter was independently checked against all seven unchanged companion runtime
JARs and the exact original protocol. All nine JVMs and 36 attempts pass the original gates.
Fresh real-profile peaks are **1,359,212 / 1,460,244 / 1,306,028 KiB**, each below
1,572,864 KiB. Original output hashes, expected limit failures and closure behavior match.
The independent full Java 21 build passes 1,380 tests with zero failures/errors/skips;
strict private Javadocs pass. The supplied full Java 25 log also records 1,380 passing tests.

A fresh transfer run on the final adapter preserves Amazon's seven tables on all four
attempts and its original output hash, with VmHWM **1,588,476 KiB**. This is lower than
its original 1,726,224 / 1,736,740 KiB observations, but slightly above 1,572,864 KiB.
Amazon is not part of the original frozen gate. The original gate is therefore qualified,
while a universal 1.5 GiB ceiling is not established. The higher fresh transfer result is
retained rather than rerun to obtain a lower reading. Numeric child records are retained
under `independent-study/`.
