"""Prepare prediction-only detector proposals and compare fixed Java structure captures.

Optional benchmark tooling. Annotations enter only the compare command, never proposal preparation.
No OCR, production detector adapter, per-case tuning or held-out accuracy claim is involved.
"""
import argparse
import hashlib
import json
from pathlib import Path

from compare_scan_detectors import maximum_matches
from compare_tiled_regions import compare, iou, predictions, reference_tables, rows


def sha(path):
    """Return the identity of a local evidence file."""
    return hashlib.sha256(path.read_bytes()).hexdigest()


def proposals(profile, native, reference):
    """Map detector table classes to identical score/bounds TSV without changing scores or boxes."""
    if profile == 'tatr':
        return [{'score': float(row['score']), 'box': [float(row[key]) for key in ('left', 'top', 'right', 'bottom')]}
                for row in native if row['label'] == '0']
    if profile == 'yolox':
        return [obj for obj in reference if obj['label'] == 8]
    raise ValueError('Unknown detector profile')


def prepare(args):
    """Verify captured identities and export only detector predictions; never read annotations."""
    if args.output.exists(): raise FileExistsError(args.output)
    report = json.loads(args.summary.read_text())
    args.output.mkdir()
    hashes = {}
    for row in report['results']:
        name = row['input']
        if not name.startswith('cTDaR_') or Path(name).name != name: raise ValueError('Unexpected input name')
        capture = args.captures / name
        frame = rows(capture / 'source.tsv')[0]
        if frame['sha256'] != row['source_sha256']: raise ValueError('Changed capture source')
        if (capture / 'outcome.txt').read_text().strip() != row['outcome']: raise ValueError('Changed outcome')
        if row['outcome'] != 'OK': continue
        if sha(capture / 'render.png') != row['render_sha256']: raise ValueError('Changed raster')
        native = rows(capture / 'detections.tsv')
        directory = args.output / name; directory.mkdir()
        for profile in ('tatr', 'yolox'):
            objects = proposals(profile, native, row['yolox_all_objects'])
            path = directory / (profile + '.tsv')
            path.write_text('score\tleft\ttop\tright\tbottom\n' + ''.join(
                '\t'.join(map(str, [obj['score'], *obj['box']])) + '\n' for obj in objects))
            hashes[str(path.relative_to(args.output))] = sha(path)
    (args.output / 'manifest.json').write_text(json.dumps({
        'region_summary_sha256': sha(args.summary), 'proposal_sha256': hashes}, indent=2) + '\n')


def structure_metric(references, candidates):
    """Associate by geometry only, then score dimensions/spans without a topology-driven chooser."""
    pairs = maximum_matches([table['box'] for table in references], [table['box'] for table in candidates])
    matched = []
    for ref, pred in pairs:
        expected = references[ref]; actual = candidates[pred]
        exact = (expected['rows'] == actual['rows'] and expected['columns'] == actual['columns']
                 and expected['anchors'] == actual['anchors'])
        matched.append({'reference': ref, 'candidate': pred, 'envelope_iou': iou(expected['box'], actual['box']),
                        'reference_rows': expected['rows'], 'reference_columns': expected['columns'],
                        'predicted_rows': actual['rows'], 'predicted_columns': actual['columns'],
                        'reference_anchors': len(expected['anchors']), 'predicted_anchors': len(actual['anchors']),
                        'matching_anchors': len(expected['anchors'] & actual['anchors']), 'exact_topology': exact})
    return {'candidate_tables': len(candidates), 'matched_reference_tables_at_iou_0_5': len(pairs),
            'exact_topologies_one_to_one_at_iou_0_5': sum(row['exact_topology'] for row in matched),
            'matches': matched,
            'legacy_highest_positive_overlap': [compare(table, candidates) for table in references]}


def evaluate(args):
    """Verify all original pins and paired outputs before reading geometry labels for evaluation."""
    if args.output.exists(): raise FileExistsError(args.output)
    manifest = json.loads(args.manifest.read_text())
    for row in manifest['inputs']:
        if sha(args.cache / row['filename']) != row['sha256']: raise ValueError('Changed source or annotation')
    files = {path.relative_to(args.capture21) for path in args.capture21.rglob('*') if path.is_file()}
    if files != {path.relative_to(args.capture25) for path in args.capture25.rglob('*') if path.is_file()}:
        raise ValueError('Paired file sets differ')
    for path in files:
        if sha(args.capture21 / path) != sha(args.capture25 / path): raise ValueError('Paired output differs')
    proposal_pins = json.loads((args.proposals / 'manifest.json').read_text())
    for name, expected in proposal_pins['proposal_sha256'].items():
        if sha(args.proposals / name) != expected: raise ValueError('Changed proposal input')
    results = []
    for row in manifest['inputs']:
        if not row['filename'].endswith('.jpg'): continue
        name = Path(row['filename']).stem; capture = args.capture21 / name
        frame = rows(capture / 'source.tsv')[0]
        if frame['sha256'] != row['sha256']: raise ValueError('Capture source mismatch')
        references = reference_tables(args.cache / (name + '.xml'))
        result = {'input': name, 'reference_tables': len(references),
                  'domain': 'modern' if name in {'cTDaR_s101', 'cTDaR_s102', 'cTDaR_s103', 'cTDaR_s104'} else 'historical'}
        result['outcome'] = (capture / 'outcome.txt').read_text().strip() if (capture / 'outcome.txt').exists() else 'OK'
        result['profiles'] = {}
        for profile in ('tatr', 'yolox'):
            directory = capture / profile
            if result['outcome'] != 'OK':
                tables = []; outcome = result['outcome']
            else:
                expected = proposal_pins['proposal_sha256'][name + '/' + profile + '.tsv']
                if (directory / 'proposals.sha256').read_text().strip() != expected:
                    raise ValueError('Capture/proposal identity mismatch')
                outcome = (directory / 'outcome.txt').read_text().strip()
                tables = predictions(directory, frame)
            result['profiles'][profile] = {'outcome': outcome, **structure_metric(references, tables)}
        results.append(result)
    totals = {}
    for domain in ('all', 'modern', 'historical'):
        selected = [row for row in results if domain == 'all' or row['domain'] == domain]
        totals[domain] = {'inputs': len(selected), 'reference_tables': sum(row['reference_tables'] for row in selected)}
        for profile in ('tatr', 'yolox'):
            totals[domain][profile] = {key: sum(row['profiles'][profile][key] for row in selected) for key in
                ('candidate_tables', 'matched_reference_tables_at_iou_0_5', 'exact_topologies_one_to_one_at_iou_0_5')}
    report = {'scope': 'Fixed raw structure diagnostic; no OCR/rule composition/held-out or end-to-end claim',
              'association': 'Maximum-cardinality one-to-one envelope IoU>=0.5, geometry only',
              'paired_capture_files_identical': len(files), 'totals': totals, 'results': results}
    args.output.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(totals, indent=2))


def main():
    """Expose separate prediction preparation and annotation-based evaluation stages."""
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    prepare_parser = commands.add_parser('prepare')
    for name in ('captures', 'summary', 'output'): prepare_parser.add_argument('--' + name, type=Path, required=True)
    compare_parser = commands.add_parser('compare')
    for name in ('capture21', 'capture25', 'cache', 'manifest', 'proposals', 'output'):
        compare_parser.add_argument('--' + name, type=Path, required=True)
    args = parser.parse_args()
    (prepare if args.command == 'prepare' else evaluate)(args)


if __name__ == '__main__':
    main()
