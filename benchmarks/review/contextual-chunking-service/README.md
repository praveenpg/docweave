# Explicit contextual chunk enrichment

The new snapshot service enriches existing chunks through a caller-owned interface.
Original Elements are retained unchanged; generated context/provenance and an explicit
embedding representation remain separate. Existing basic/title chunk behavior and
published chunk goldens are unchanged. No model dependency, network call or download
is added to default discovery.

Eight new unit methods cover original identity/Unicode, complete-input preflight,
provider/invalid-context fallback, bounded virtual-thread execution and order,
uncooperative-provider timeout, pre/during-call interruption, provider cancellation
and cancellation of a peer after document failure without scheduling later chunks.
Two new integration methods run eight actual native formats through both existing
chunk strategies and verify source JSON/provenance, table HTML and code whitespace.
The two existing public API exposure methods also pass.

Final full Java21 verification:1,255 tests, zero failures/errors/skips, strict public
Javadocs and standalone-source compilation. Eight-file private Javadocs and scoped
formatting pass. A packaged consumer using only built core/chunking JARs also passes,
without parser runtimes, Jackson or jsoup on that execution classpath. This tests the
enrichment-only path; it does not remove declared chunking dependencies or establish
a signed/installed release candidate. Numeric identities are in `java21.json`.

These are behavioral controls with explicitly owned context providers. No live model,
retrieval dataset, embedding model or retrieval improvement is claimed. A reproducible
live-provider/retrieval evaluation remains open. See the [public contract and usage](../../../docs/contextual-chunking.md).
