"""Evaluate fixed whole-page rule/model composition captures against original cTDaR labels.

Annotation geometry is evaluation-only. No transcription, label repair or case-specific tuning.
"""
import argparse
import hashlib
import json
from pathlib import Path

from compare_detector_structures import structure_metric
from compare_tiled_regions import predictions, rectangle, reference_tables, rows

PROFILES = ('model', 'rules', 'composed')


def sha(path):
    """Fingerprint an explicit local source or numeric evidence file."""
    return hashlib.sha256(path.read_bytes()).hexdigest()


def canonical_tables(directory):
    """Compare complete numeric geometry without capture-local table/crop IDs or provenance text."""
    outcome = (directory / 'outcome.txt').read_text().strip()
    if outcome != 'OK': return outcome, ()
    anchors = rows(directory / 'anchors.tsv')
    tables = []
    for table in rows(directory / 'tables.tsv'):
        cells = sorted(tuple(int(anchor[key]) for key in ('row', 'column', 'row_span', 'column_span'))
                       + rectangle(anchor) for anchor in anchors
                       if anchor['crop'] == table['crop'] and anchor['table'] == table['table'])
        tables.append((int(table['rows']), int(table['columns']), *rectangle(table), tuple(cells)))
    return outcome, tuple(sorted(tables))


def same_physical_frame(first, second):
    """Require original image identity/placement; render DPI and floored canvas size may differ."""
    return all(first[key] == second[key] for key in
               ('sha256', 'original_width', 'original_height', 'pdf_width', 'pdf_height'))


def evaluate(args):
    """Check source pins, paired bytes and the historical model control before reading labels."""
    if args.output.exists(): raise FileExistsError(args.output)
    manifest = json.loads(args.manifest.read_text())
    for row in manifest['inputs']:
        if sha(args.cache / row['filename']) != row['sha256']: raise ValueError('Changed source/annotation')
    baseline = json.loads((args.baseline / 'manifest.json').read_text())
    if sha(args.baseline / 'summary.json') != baseline['summary_sha256']: raise ValueError('Changed baseline summary')
    for name, expected in baseline['capture_sha256'].items():
        if sha(args.baseline / name) != expected: raise ValueError('Changed baseline evidence')
    files = {path.relative_to(args.capture21) for path in args.capture21.rglob('*') if path.is_file()}
    if files != {path.relative_to(args.capture25) for path in args.capture25.rglob('*') if path.is_file()}:
        raise ValueError('Paired output file sets differ')
    for name in files:
        if sha(args.capture21 / name) != sha(args.capture25 / name): raise ValueError('Paired output differs')
    expected_sources = {Path(row['filename']).stem for row in manifest['inputs'] if row['filename'].endswith('.jpg')}
    if {path.name for path in args.capture21.iterdir()} != expected_sources: raise ValueError('Changed input set')
    results = []
    for row in manifest['inputs']:
        if not row['filename'].endswith('.jpg'): continue
        name = Path(row['filename']).stem; capture = args.capture21 / name
        control = args.baseline / 'java21' / name
        frame = rows(capture / 'source.tsv')[0]
        control_frame = rows(control / 'source.tsv')[0]
        if not same_physical_frame(frame, control_frame): raise ValueError('Changed original source placement')
        if int(frame['render_dpi']) != args.dpi: raise ValueError('Changed fixed resolution profile')
        if frame['sha256'] != row['sha256']: raise ValueError('Source identity mismatch')
        outcome = (capture / 'outcome.txt').read_text().strip() if (capture / 'outcome.txt').exists() else 'OK'
        if args.dpi == 150:
            if (capture / 'source.tsv').read_bytes() != (control / 'source.tsv').read_bytes():
                raise ValueError('Changed control source frame')
            if outcome == 'OK':
                if (capture / 'render.sha256').read_bytes() != (control / 'render.sha256').read_bytes():
                    raise ValueError('Changed rendered control raster')
                if canonical_tables(capture / 'model') != canonical_tables(control / 'tatr'):
                    raise ValueError('Model control changed on ' + name)
            elif (control / 'outcome.txt').read_text().strip() != outcome:
                raise ValueError('Changed control whole-source outcome')
        references = reference_tables(args.cache / (name + '.xml'))
        record = {'input': name, 'reference_tables': len(references), 'reference_anchor_keys': sum(len(t['anchors']) for t in references),
                  'domain': 'modern' if name in {'cTDaR_s101', 'cTDaR_s102', 'cTDaR_s103', 'cTDaR_s104'} else 'historical',
                  'outcome': outcome, 'same_original_placement_verified': True,
                  'model_control_reproduced': True if args.dpi == 150 else None, 'profiles': {}}
        for profile in PROFILES:
            directory = capture / profile
            profile_outcome = outcome if outcome != 'OK' else (directory / 'outcome.txt').read_text().strip()
            tables = predictions(directory, frame) if outcome == 'OK' else []
            metric = structure_metric(references, tables)
            metric['matching_anchor_keys'] = sum(match['matching_anchors'] for match in metric['matches'])
            provenance = [table['provenance'] for table in rows(directory / 'tables.tsv')] if profile_outcome == 'OK' else []
            record['profiles'][profile] = {'outcome': profile_outcome, **metric,
                    'ruled_tables': sum(value.startswith('raster_ruled_interiors_v1;') for value in provenance),
                    'model_tables': sum(value.startswith('detector_sha256=') for value in provenance)}
        results.append(record)
    totals = {}
    for domain in ('all', 'modern', 'historical'):
        selected = [row for row in results if domain == 'all' or row['domain'] == domain]
        totals[domain] = {'inputs': len(selected), 'reference_tables': sum(row['reference_tables'] for row in selected),
                          'reference_anchor_keys': sum(row['reference_anchor_keys'] for row in selected)}
        for profile in PROFILES:
            totals[domain][profile] = {key: sum(row['profiles'][profile][key] for row in selected) for key in
                    ('candidate_tables', 'matched_reference_tables_at_iou_0_5', 'exact_topologies_one_to_one_at_iou_0_5',
                     'matching_anchor_keys', 'ruled_tables', 'model_tables')}
            totals[domain][profile]['non_ok_inputs'] = sum(row['profiles'][profile]['outcome'] != 'OK' for row in selected)
    report = {'scope': 'Fixed whole-page public layout strategies only; no OCR/held-out/end-to-end accuracy claim',
              'association': 'Maximum-cardinality one-to-one envelope IoU>=0.5, geometry only',
              'render_dpi': args.dpi, 'control_dpi': 150,
              'paired_capture_files_identical': len(files), 'totals': totals, 'results': results}
    args.output.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(totals, indent=2))


def main():
    """Require explicit fresh output, pinned original cache and prior model-control evidence."""
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('capture21', 'capture25', 'cache', 'manifest', 'baseline', 'output'):
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--dpi', type=int, choices=(150, 300), default=150)
    evaluate(parser.parse_args())


if __name__ == '__main__':
    main()
