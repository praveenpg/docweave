# Bounded Type3 glyph-program nesting

A Type3 character program can invoke another Type3 character through its font resources.
The page-operator budget does not necessarily reject a cycle before the Java call stack
is exhausted. An owned self-referencing font reproduced `StackOverflowError` in the native
graphics visitor under default options.

The visitor now accepts at most 64 simultaneous Type3 character programs. Entry into
the 65th throws `PartitionException` with `INPUT_TOO_LARGE`. Cancellation is checked at
entry, and the depth counter and glyph paint state unwind in `finally`. Operators still
consume the existing shared page budget; this guard neither resets that budget nor
silently skips descendant content.

The self-referencing source is serialized as a complete PDF and checked through the
public `DocWeave` interface as well as the visitor. Both now return the typed error.
Finite chains establish the explicit boundary: 64 programs succeed, 65 are rejected.
A separate leaf glyph succeeds after rejection, proving that the call-confined depth
state recovers. The existing invisible-paint, normal page, source-image and resource
controls remain active.

## Java21 verification

The focused graphics suite passes 24 methods, including seven Type3 methods. Full
Java21 verification passes **1,276 tests, zero failures/errors/skips**, strict public
Javadocs and standalone-source compilation. A final seven-method run passes after
normalizing diagnostic wording. Strict private Javadocs and scoped formatting pass.
`java21.json` pins the source files and full/final-focused log identities.

The guard does not promise a total JVM stack/heap bound for parser, font-loading or
codec internals. It addresses the reproduced native graphics character-program cycle;
it is not a general malformed-PDF certification or extraction accuracy measurement.
No external font/model/download, Java25 test, release or public synchronization is
required to reproduce these controls.
