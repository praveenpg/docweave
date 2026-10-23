# Python scorer migration to the JVM (2026-10-22)

All 47 Python test methods in 14 files were dispositioned:
- **29 methods (8 files) migrated** to owned JUnit tests in `docweave-validation`. Their implementations are
  now JVM scorers launched by `ScoreBenchmarkCaptures`.
- **18 methods (6 files) retained in Python**, because they guard or run inside the actual Unstructured
  execution.

The retired Python sources remain in repository history. Historical reports and identity files are unchanged
and still pin those sources.

## Parity evidence

| Evidence | Result |
|---|---|
| Retained cTDaR reports regenerated from retained numeric captures and pinned labels (`CtdarScorerReplayTest`, opt-in with `-Ddocweave.scoring.ctdar.cache`) | Byte-identical: `pdf-tiled-regions`, `pdf-detector-structures`, `pdf-compact-ruled`, `pdf-raster-rule-audit`, `pdf-whole-page-ruled-composition` at 150 and 300 DPI |
| `pdf-alternate-detectors/summary.json` (Python YOLOX harness output), recomputed by `scan-detectors-verify` | Every `tatr`/`yolox` metric and the aggregate are identical |
| All-20 upstream summary, regenerated from the 20 per-input reports that are themselves rebuilt from `comparison.json` (each report SHA-256 matches its recorded pin) | Byte-identical except `harness_sha256`, which now pins the JVM source. Default test, no private data |
| Retained detector proposal TSVs regenerated from retained region evidence | All 16 file hashes match `proposals/manifest.json` |
| Raster-rule prototype sources regenerated from the reviewed baseline source | All four profile hashes match `profiles.json` |
| Fresh native business captures (6 profiles, 3 cases) scored by the retired script and the JVM scorer | Report and console output byte-identical: 18 case/profile records, including tables with no association and integer-zero IoU |
| Frozen vectors from the retired functions (`python-scorer-parity-vectors.json`, SHA-256 `79c9842d…`) | See the breakdown below; all replay identically |
| Negative path | Both implementations reject an invalid profile list with the same message |

Frozen vector breakdown, all replayed with identical typed JSON (int versus float):

| Group | Cases |
|---|---:|
| Float `repr` | 2,016 |
| `round(x, 6)` | 2,021 |
| JSON layouts | 7 |
| CSV / TSV | 12 / 3 |
| IoU, both variants | 802 each |
| Maximum matching and proposal metrics | 300 |
| Structure metric | 250 |
| Compact interiors (including 248 rejections) | 305 |
| ASCII normalization | 8 |
| HTML cell parsing (including 3 failures: nested, unmatched and unterminated cells) | 9 |
| Geometry selection | 200 |
| Business comparison (including 63 typed failures) | 250 |

The generator, `generate-parity-vectors.py.txt`, is kept beside this README. It reads only owned synthetic
inputs. Byte parity covers documented-input behaviour only. Where Python would silently accept an input the
pinned tools never produce, the JVM scorers fail closed instead:
- underscores beyond Python's numeric syntax;
- unterminated CSV quotes;
- named HTML references outside DocWeave's escape set;
- raw-text HTML elements.

## Method mapping (47)

### Migrated: 29 methods, JUnit test in parentheses

**`benchmarks/tools/test_business_cell_text.py`** → `BusinessCellTextScorerTest` (`compare_business_cell_text.py` → `BusinessCellTextScorer`)

| Python method | JUnit test |
|---|---|
| test_ascii_whitespace_only | `asciiWhitespaceOnly` |
| test_escaped_html_text_and_overlapping_anchors | `escapedHtmlTextAndOverlappingAnchors` |
| test_explicit_native_profile_preserves_reference_denominator | `explicitNativeProfilePreservesReferenceDenominator` |
| test_geometry_selection_does_not_choose_better_text | `geometrySelectionDoesNotChooseBetterText` |
| test_merged_anchor_projection_is_evaluation_only | `mergedAnchorProjectionIsEvaluationOnly` |
| test_missing_table_does_not_match_blank_expectations | `missingTableDoesNotMatchBlankExpectations` |
| test_profile_names_cannot_escape_capture_directory_or_duplicate_cases | `profileNamesCannotEscapeCaptureDirectoryOrDuplicateCases` |
| test_uncovered_slots_do_not_match_blanks | `uncoveredSlotsDoNotMatchBlanks` |

**`benchmarks/tools/test_compact_layout.py`** → `CompactLayoutScorerTest` (`compare_compact_layout.py` → `CompactLayoutScorer`)

| Python method | JUnit test |
|---|---|
| test_fractional_centerlines_round_inward_and_keep_compact_interiors_positive | `fractionalCenterlinesRoundInwardAndKeepCompactInteriorsPositive` |
| test_too_small_prediction_cannot_be_repaired_into_a_positive_interior | `tooSmallPredictionCannotBeRepairedIntoAPositiveInterior` |

**`benchmarks/tools/test_detector_structures.py`** → `DetectorStructureScorerTest` (`compare_detector_structures.py` → `DetectorStructureScorer`; the class also has `retainedProposalFilesAreReproducedFromRetainedRegionEvidence`)

| Python method | JUnit test |
|---|---|
| test_extra_duplicate_cannot_multiply_topology_credit | `extraDuplicateCannotMultiplyTopologyCredit` |
| test_preparation_preserves_thresholded_scores_and_unclipped_boxes | `preparationPreservesThresholdedScoresAndUnclippedBoxes` |
| test_topology_cannot_choose_a_geometrically_unmatched_prediction | `topologyCannotChooseAGeometricallyUnmatchedPrediction` |

**`benchmarks/tools/test_raster_rule_audit.py`** → `RasterRulePrototypeScorerTest` (`audit_raster_rules.py` → `RasterRulePrototypeScorer`; `compare_raster_rule_audit.py` → `RasterRuleAuditScorer`; the class also has `everyRetainedPrototypeIdentityIsReproduced`)

| Python method | JUnit test |
|---|---|
| test_baseline_changes_access_only_and_unknown_source_is_rejected | `baselineChangesAccessOnlyAndUnknownSourceIsRejected` |
| test_compact_preserves_dark_predicate_and_resource_caps | `compactPreservesDarkPredicateAndResourceCaps` |

**`benchmarks/tools/test_ruled_scans.py`** → `RuledScanScorerTest` (`compare_ruled_scans.py` → `RuledScanScorer`)

| Python method | JUnit test |
|---|---|
| test_capture_ids_do_not_change_geometry_identity | `captureIdsDoNotChangeGeometryIdentity` |
| test_resolution_change_does_not_allow_source_replacement_or_repositioning | `resolutionChangeDoesNotAllowSourceReplacementOrRepositioning` |
| test_same_topology_cannot_hide_changed_cell_geometry_or_failure | `sameTopologyCannotHideChangedCellGeometryOrFailure` |

**`benchmarks/tools/test_scan_detectors.py`** → `ScanDetectorVerificationTest` (scoring of `compare_scan_detectors.py` → `TableGeometry`, `StructureMetrics` and `ScanDetectorVerification`)

| Python method | JUnit test |
|---|---|
| test_augmenting_path_avoids_order_dependent_under_count | `augmentingPathAvoidsOrderDependentUnderCount` |
| test_duplicate_predictions_cannot_count_a_reference_twice | `duplicatePredictionsCannotCountAReferenceTwice` |
| test_empty_and_canvas_floor_do_not_invent_matches_or_rescaling | `emptyAndCanvasFloorDoNotInventMatchesOrRescaling` |

**`benchmarks/tools/test_tiled_regions.py`** → `TiledRegionScorerTest` (`compare_tiled_regions.py` → `TiledRegionScorer`)

| Python method | JUnit test |
|---|---|
| test_identical_span_keys_without_spatial_overlap_are_not_a_match | `identicalSpanKeysWithoutSpatialOverlapAreNotAMatch` |
| test_raster_canvas_floor_does_not_rescale_source_geometry | `rasterCanvasFloorDoesNotRescaleSourceGeometry` |
| test_spatial_association_does_not_select_an_oracle_topology | `spatialAssociationDoesNotSelectAnOracleTopology` |

**`benchmarks/reference-tests/test_pdf_upstream_summary.py`** → `UpstreamTableSummaryTest` (`summarize_pdf_upstream.py` → `UpstreamTableSummary`; the class also has `retainedAllTwentySummaryIsReproducedFromItsOwnSourceReports`)

| Python method | JUnit test |
|---|---|
| test_duplicate_tables_do_not_double_matches_or_receive_complete_input_credit | `duplicateTablesDoNotDoubleMatchesOrReceiveCompleteInputCredit` |
| test_empty_recognition_is_retained_without_table_credit | `emptyRecognitionIsRetainedWithoutTableCredit` |
| test_extra_public_element_without_html_prevents_complete_input_credit | `extraPublicElementWithoutHtmlPreventsCompleteInputCredit` |
| test_java_extra_tables_prevent_complete_input_credit | `javaExtraTablesPreventCompleteInputCredit` |
| test_misses_stay_in_denominator_and_text_credit_remains_separate | `missesStayInDenominatorAndTextCreditRemainsSeparate` |

### Retained in Python: 18 methods, with rationale

**`benchmarks/reference-tests/test_pdf_upstream.py`** (6 methods). The functions under test (`html_grid`,
`cell_grid`, `metrics`) run inside `compare_pdf_upstream.py`, which executes Unstructured. They parse
Unstructured's own `text_as_html` and cell objects, and PubTabNet tokens, with BeautifulSoup. The text-free
report keeps only the resulting counts. Moving the scoring to the JVM would mean exporting text-bearing cell
grids from the Unstructured process, which contradicts the text-free evidence policy, so these methods stay
with that process. They run in the pinned reference environment: 6 passed this session.

| Method | Reason it stays |
|---|---|
| test_duplicate_keys_cannot_inflate_matches | `metrics` is applied to Unstructured outputs inside the reference process |
| test_invalid_overlapping_and_overlong_spans_are_not_repaired | `html_grid` parses Unstructured HTML with the reference environment's parser |
| test_labels_supply_literal_unicode_text_and_preserve_punctuation | PubTabNet label tokens are parsed by the reference environment |
| test_same_dimensions_do_not_hide_wrong_spans_or_text | `metrics`, as above |
| test_upstream_positions_are_preserved_and_nonrectangular_cells_rejected | `cell_grid` adapts Unstructured cell objects |
| test_vertical_span_skips_occupied_position | `html_grid`, as above |

**Reference-generator guards** (12 methods in 5 files). These guard the actual Python Unstructured reference
generation: source pins are checked before upstream imports, and the model wheel and version must match.
They run with the Python standard library only; 12 passed this session.

| File | Methods | Count |
|---|---|---:|
| `test_generate_chunking_goldens.py` | `test_intentional_differences_have_named_reviewed_expectations`, `test_missing_source_fails_instead_of_claiming_pin`, `test_source_verification_accepts_selected_pin_and_rejects_drift`, `test_supported_runtime_range_and_rejection` | 4 |
| `test_generate_docx_references.py` | `test_external_manifest_inputs_are_pinned_before_upstream_imports`, `test_installed_model_must_match_verified_wheel_and_version`, `test_manifest_cannot_escape_the_reference_cache`, `test_selected_docx_sources_are_checked_before_import`, `test_wrong_model_wheel_is_rejected_before_package_lookup` | 5 |
| `test_generate_markup_references.py` | `test_selected_markup_sources_must_match_pin` | 1 |
| `test_generate_text_references.py` | `test_selected_text_sources_must_match_pin` | 1 |
| `test_generate_xml_references.py` | `test_selected_xml_sources_must_match_pin` | 1 |

## Retained Python programs

- **`compare_scan_detectors.py`:** executes pinned upstream YOLOX prediction code.
  - Its three geometry helpers were copied verbatim from the retired `compare_tiled_regions.py`, so the file
    no longer imports a retired module. Before and after versions agree on 1,111 equivalence checks:
    matching, metrics, IoU, all nine label files and source boxes.
  - Its reported metrics are recomputed on the JVM by `scan-detectors-verify`.
- **`compare_pdf_upstream.py`:** executes Unstructured.
- **The `generate_*.py` reference generators.**

## Removed files

Removed after parity was shown and every usage was audited. SHA-256 at removal:

| File | SHA-256 |
|---|---|
| `benchmarks/tools/compare_business_cell_text.py` | `6946b5ce…3db6` |
| `benchmarks/tools/test_business_cell_text.py` | `60b43e2c…89ce` |
| `benchmarks/tools/compare_compact_layout.py` | `a895fbca…084f` |
| `benchmarks/tools/test_compact_layout.py` | `008cbd45…e514` |
| `benchmarks/tools/compare_detector_structures.py` | `fa67a02b…7d99` |
| `benchmarks/tools/test_detector_structures.py` | `74808bbc…e373` |
| `benchmarks/tools/audit_raster_rules.py` | `3cc68cbc…eb12` |
| `benchmarks/tools/test_raster_rule_audit.py` | `c4a4e2eb…eeae` |
| `benchmarks/tools/compare_raster_rule_audit.py` | `7742c523…438c` |
| `benchmarks/tools/compare_ruled_scans.py` | `558ee551…934d` |
| `benchmarks/tools/test_ruled_scans.py` | `99d6f71f…35cd` |
| `benchmarks/tools/test_scan_detectors.py` | `f277f13c…a4db` |
| `benchmarks/tools/compare_tiled_regions.py` | `8b25679d…c83b` |
| `benchmarks/tools/test_tiled_regions.py` | `50a4d232…a5b1` |
| `benchmarks/tools/summarize_pdf_upstream.py` | `828776b9…17f0` |
| `benchmarks/reference-tests/test_pdf_upstream_summary.py` | `568ac46b…4cb2` |

Full hashes are in `retired-python-sha256.txt`.

## Changed callers

- The reproduction commands in the review READMEs now use `ScoreBenchmarkCaptures`, each with a migration note:
  - `pdf-tiled-regions`, `pdf-detector-structures`, `pdf-compact-ruled`, `pdf-raster-rule-audit`;
  - `pdf-whole-page-ruled-composition`, `pdf-alternate-detectors`;
  - `pdf-business-cell-text`, `pdf-business-native`, `pdf-partial-rule-text`, `pdf-native-model-breadth`;
  - `pdf-upstream-all20`.
- `README.md`, `benchmarks/README.md` and the validation README describe the split.
- The CI step `python3 -m unittest discover -s benchmarks/tools` still runs the 12 retained generator guards.
- No new dependency: the JVM scorers use the JDK and the existing test classpath (JUnit, Jackson, jsoup).
