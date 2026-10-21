# Detector-only resolution experiment

All nine pinned cTDaR inputs were evaluated with the detector's maximum image side set
to800 pixels (control) or1200 pixels (experiment). This is a development-corpus study,
not a held-out benchmark or an upstream preprocessing compatibility claim. Original
source/annotation/model pins remain those in the linked manifests. No source selection,
annotation crop, relabeling, repair or case-dependent resolution choice was performed.

Only the three-label detection session uses the experimental resolution. The seven-label
structure session retains its800-pixel transform. Bilinear filtering, normalization,
threshold0.5, source-frame decoding, crop policy, KEEP_HIGHER_SCORE merges, explicit
rule preference, English PAGE-word OCR and orientation/minimum raw score15 stay fixed.
Whole JPEG images are embedded/rendered at150DPI. Resource ceilings/timeouts are not
raised, and the oversized source remains an OCR_LIMIT outcome in both profiles.

The experiment uses generated classes in a temporary classpath overlay. Repository
production sources and Maven class outputs are unchanged. The existing corpus assertion
method runs directly through a small launcher; these are four completed evaluations,
not four full Maven/JUnit verification runs. Logs identify the active session class origin.
The800 control reproduces every result entry from the earlier Maven evaluation.

Both profiles reproduce every per-input result entry across actual Java21 and25.
A separate actual-JVM check confirms800/1200 resized maximum dimensions, preserved
original source dimensions and unchanged borrowed pixels. Normal input/deadline policies
remain in the generated transform; the experiment is not production hardening evidence.

| Diagnostic | Control800 | Experiment1200 |
| --- | ---: | ---: |
| Inputs retained |9 |9 |
| Candidate tables |12 |9 |
| Exact dimensions/anchor sets at highest envelope overlap |3 |3 |
| Typed OCR_LIMIT inputs |1 |1 |

The same three topology matches remain: s0022x5, s10217x4 and5x4. On s003, the incorrect
4x2/six-anchor structure becomes15x2/24anchors; its best positional/span intersection
remains two keys. On s104, candidate tables fall from six to three. Other sources also
have changed envelopes and/or output order. Counts alone cannot establish whether every
changed proposal is useful, duplicated or missing, and ordinary OCR line counts do not
measure text fidelity. Both full paired result arrays and every source/reference comparison
are retained rather than selecting only favorable effects.

No additional exact scan topology match is demonstrated, so no production setting is
promoted. The20 scientific-table images were not replayed for this rejected experiment;
any future candidate setting needs independent validation before adoption. Published
sources themselves use different inference/preprocessor profiles; this1200 setting is
an explicit experiment, not a claimed reproduction of their defaults. See the original
[Microsoft inference source](https://github.com/microsoft/table-transformer/blob/main/src/inference.py)
and the [pinned detector configuration](https://huggingface.co/microsoft/table-transformer-detection/blob/2357cbe2b5a5d1c03e54f32764f06058933b65ab/preprocessor_config.json).

`manifest.json` records the exact source-generation script, one-site session patch,
compile/run script, transform-check source, active overlay class hashes, graph/resource
manifest links, evaluated production commit, runtime identities and report hashes.
`source-sha256.json` fingerprints the unchanged layout and corpus Java sources. Original
images/XML, transcriptions and weights remain local and are not redistributed.
