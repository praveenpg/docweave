"""Aggregate all fixed upstream comparison inputs without excluding misses or multiplying duplicate matches."""

import argparse
import hashlib
import json
from pathlib import Path


PROFILES = ('isolated_upstream_postprocessing', 'shared_weights_upstream_recognition',
            'public_pdf_api_default_model_names_with_pinned_local_files')


def aggregate(cases, profile):
    """Require exactly one candidate for complete-table credit; retain every input and duplicate count."""
    result = dict(inputs=len(cases), nonempty_tables=0, inputs_with_table=0, inputs_with_multiple_tables=0,
                  exactly_one_complete_topology=0, exactly_one_complete_topology_and_text=0,
                  best_candidate_positional_span_matches=0)
    for case in cases:
        values = case[profile]
        tables = values if isinstance(values, list) else [values]
        nonempty = [table for table in tables if table['anchors'] > 0]
        result['nonempty_tables'] += len(nonempty)
        result['inputs_with_table'] += bool(nonempty)
        result['inputs_with_multiple_tables'] += len(nonempty) > 1
        single = len(tables) == 1 and len(nonempty) == 1
        if profile == PROFILES[2]:
            single = single and case['public_detected_tables'] == 1
        result['exactly_one_complete_topology'] += single and nonempty[0]['complete_topology']
        result['exactly_one_complete_topology_and_text'] += single and nonempty[0]['complete_topology_and_text']
        # Upper-envelope diagnostic for multiple predictions; never sum repeated keys from duplicate candidates.
        result['best_candidate_positional_span_matches'] += max(
            (table['positional_span_matches'] for table in tables), default=0)
    return result


def candidate_summary(report):
    """Summarize existing Java measurements while keeping their distinct OCR/parser configuration explicit."""
    tables = [t for entry in report['results'] for t in entry.get('emitted_tables', [])]
    return {'inputs': len(report['results']), 'nonempty_tables': len(tables),
            'inputs_with_table': sum(bool(e.get('emitted_tables')) for e in report['results']),
            'exactly_one_complete_topology': sum(
                len(e.get('emitted_tables', [])) == 1 and e['emitted_tables'][0]['same_shape']
                and e['emitted_tables'][0]['positional_span_matches'] == e['published_anchors']
                for e in report['results']),
            'exactly_one_complete_topology_and_text': sum(
                len(e.get('emitted_tables', [])) == 1 and e['emitted_tables'][0]['complete_shape_text_match']
                for e in report['results'])}


def main():
    """Validate the complete source set, align reference dimensions, then save text-free measurements."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--reports', type=Path, required=True)
    parser.add_argument('--candidate', type=Path, required=True)
    parser.add_argument('--detector-baseline', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists():
        raise FileExistsError(args.output)
    root = Path(__file__).resolve().parents[2]
    manifest = json.loads((root / 'benchmarks/review/pdf-pubtabnet/manifest.json').read_text())
    expected = {e['filename'] for e in manifest['inputs'] if e['filename'].endswith('.png')}
    files = sorted(args.reports.glob('*-comparison.json'))
    cases = [json.loads(path.read_text()) for path in files]
    if len(cases) != 20 or len({c['input'] for c in cases}) != 20 or {c['input'] for c in cases} != expected:
        raise ValueError('Require all20 unique pinned inputs, with no missing/duplicate cases')
    if any(c['source_commit'] != manifest['commit'] for c in cases):
        raise ValueError('Mixed source revisions')
    for field in ('dependencies', 'source_sha256', 'harness_sha256', 'capture_harness_sha256', 'model_sha256',
                  'settings', 'tesseract_sha256', 'tesseract_executable_sha256'):
        if any(c[field] != cases[0][field] for c in cases):
            raise ValueError('Mixed comparison configuration: ' + field)
    summaries = {profile: aggregate(cases, profile) for profile in PROFILES}
    for name, path in (('docweave_explicit_structure_cell_ocr', args.candidate),
                       ('docweave_historical_detector_and_structure', args.detector_baseline)):
        data = json.loads(path.read_text())
        if len(data['results']) != 20 or {e['input'] for e in data['results']} != expected:
            raise ValueError('Incomplete Java comparison measurements')
        by_name = {e['input']: e for e in data['results']}
        for case in cases:
            candidate = by_name[case['input']]
            if any(case['published'][field] != candidate['published_' + field]
                   for field in ('rows', 'columns', 'anchors')):
                raise ValueError('Python/Java reference shape disagreement')
        summaries[name] = candidate_summary(data)
    total_anchors = sum(c['published']['anchors'] for c in cases)
    if total_anchors != 1380:
        raise ValueError('Published denominator changed')
    result = {'scope': 'All20 original scientific table crops embedded whole in PDFs; upstream train examples, not held-out/general PDF accuracy',
              'published_anchors': total_anchors, 'summaries': summaries, 'cases': cases,
              'harness_sha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
              'source_reports_sha256': {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in files},
              'java_measurement_sha256': {str(p): hashlib.sha256(p.read_bytes()).hexdigest()
                                          for p in (args.candidate, args.detector_baseline)}}
    with args.output.open('x') as output:
        json.dump(result, output, indent=2, sort_keys=True)
        output.write('\n')
    print(json.dumps(summaries, indent=2))


if __name__ == '__main__':
    main()
