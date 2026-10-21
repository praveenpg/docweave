# Unstructured 0.27.30 source review

The official freshness guard detected release/main `08d407ea5ab77dcc7f27775c22cc70bbedae7d58`
after the previous 0.27.29 checkpoint. `observed-drift.json` preserves that failed guard;
`review.json` records the reviewed response and source hashes.

The one incremental commit fixes non-ASCII sender/recipient names in email metadata.
The complete incremental diff changes only CHANGELOG.md, the email tests, the version
file and partition/email.py. Email is outside currently implemented formats. The released
partition/pdf.py hash remains identical to the measured 0.27.28 source, and no dependency
manifest changes occur. This review does not relabel measured outputs or regenerate goldens:
the executed comparison remains 0.27.28 and historical references remain 0.27.27.

Official sources: [release](https://github.com/Unstructured-IO/unstructured/releases/tag/0.27.30),
[incremental diff](https://github.com/Unstructured-IO/unstructured/compare/f98602237cb152e9c33fd824fb8ec96037806ad4...08d407ea5ab77dcc7f27775c22cc70bbedae7d58).
