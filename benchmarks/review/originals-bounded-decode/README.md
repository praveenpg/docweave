# Bounded base64 input for original-element decoding

The public `ElementJson.decodeOriginals` service no longer materializes complete encoded
ASCII and decoded compressed byte arrays before inflation. A hidden, call-confined input
view reads the caller's immutable base64 String. Validation uses the same JDK basic decoder
in aligned 8,192-character blocks; nonfinal blocks cannot contain padding. The entire input
is validated before inflation, including encoded suffixes beyond the first zlib member.
An additional validation pass trades CPU scanning for bounded input temporaries.

The raw decompressed array and stream scopes end before building the element graph.
Decompressed JSON text, caller-owned encoded text, individual maps and final elements still
materialize. This is not a total decoder/parser heap bound or a throughput improvement claim.
No public API shape, dependency, source element or wire-format change is introduced.

## Compatibility boundaries

- The 32 MiB encoded-character preflight and 16 MiB decompressed-byte ceiling remain.
- Strict basic-base64 alphabet/padding behavior remains, including optional final padding,
  JDK-accepted noncanonical trailing bits and rejection of malformed unused encoded tails.
- Valid decoded bytes after the first zlib member remain ignored as before; truncated or
  checksum-corrupt members fail. This does not introduce a stricter compressed-tail policy.
- Existing UTF-8 replacement behavior and the subsequent normalized UTF-8 byte preflight remain.
  JSON/schema errors, trailing JSON rejection and originals-specific parser limits remain.
- Interruption cooperates during validation/decoding without clearing the flag. Calls use
  independent cursors, including concurrent virtual-thread reuse.

Five new owned Java methods compare the legacy algorithm/JDK decoder against valid and
invalid inputs around validation-block boundaries, encoded suffixes, padding, malformed
UTF-8, corrupted/truncated compression, JSON/schema errors, encoded limits, cancellation
and concurrent reuse. Existing exact 16 MiB ASCII/multibyte controls still pass.

## Scoped allocation observation

The same public decoder processes one complete owned image-bearing element with wide
Unicode text, using a deterministic valid 512x512 RGB PNG. The encoded payload has 1,054,704
characters. Source PNG, full encoded payload, decoded wire SHA-256 and the complete original
are identical across the baseline and candidate runs.

Java Flight Recorder observes allocations only during public decoding. The filter retains
byte-array events with both base64-decoder and public originals-decoder frames. The baseline
records two large objects; the maximum recorded allocation is 1,054,720 bytes including its
object header. The candidate records no matching events. Its implementation still allocates
small validation buffers: zero matching events is **not** proof of zero allocation.
New-TLAB events sample renewal allocations; these observations are not total allocation,
retained heap, peak RSS, a speed comparison or independent PDF-corpus accuracy.

See [before](before-java21.json), [after](after-java21.json), [verification](comparison.json)
and [receipt](receipt.json). Baseline source is development `0224a8f`. Compile its
`DefaultElementJson.java` into an isolated first classpath entry; run the same
[ProbeOriginalDecodeBuffers](../../tools/ProbeOriginalDecodeBuffers.java) with one fresh
report path under each runtime. Then pass baseline, candidate and a third fresh report path
to the probe for guarded verification. Use Java 21 and current binary dependencies; full
builds overwrite ordinary target JARs, so isolate baseline classes first. JFR recordings
are temporary local artifacts and are deleted after numeric aggregation.

Full Java 21 verification passes1,301 tests with zero failures/errors/skips. Strict documentation
and the standalone source compiler cover
the final implementation. Java 25 stays deferred to final PDF acceptance. No new model,
OCR run, Python evaluation or upstream golden regeneration is part of this change.
The upstream decode-buffer lifetime review motivates this separate JVM implementation;
it does not establish executed parity with the latest upstream runtime.
