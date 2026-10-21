# Fixed longer-anchor diagnostic: negative result

This experiment is diagnostic only and changes no extraction or strict scoring. The protocol
was frozen against the52-row inline-script baseline before execution. All four original sources
and28 reference paragraphs remain under two uniform profiles: native lines and explicit
inline-script/wrap paragraph assembly. Fixed128-code-point anchors treat ASCII space/NBSP
as equivalent only for lookup; original characters, offsets and edit traces remain untouched.
No adaptive anchor length, occurrence selection or repair is permitted.

Both actual Java21/25 runs agree on all8 strict baseline rows and all56 observations. There
are26 exact observations,6 uniquely anchored nonexact diagnostics,23 missing/ambiguous anchors
and1 short reference. This longer policy loses coverage where physical wraps occur inside
anchors. It does not solve eLife localization or improve extraction, and will not replace the
historical32-code-point diagnostic or strict scorer.

`validation.json` uses the unchanged `VerifyTextDifferences.java` from the earlier diagnostic
to validate runtime equality, strict baselines, every denominator and original edit accounting.
Owned controls additionally cover anchor-only NBSP equivalence, astral-code-point/UTF16 offsets,
retained original substitutions, canonical-anchor ambiguity and missing output. Standalone
compilation/self-tests pass on both JDKs. No production change, beta or publication.

Further eLife work should map actual native glyphs to paragraph spans; do not keep tuning
anchor lengths against these documents. The next acceptance work uses a new business issuer.
