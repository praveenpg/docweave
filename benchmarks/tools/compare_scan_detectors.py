"""Compare native TATR and pinned upstream YOLOX table proposals on identical Java rasters.

This is optional reference tooling, not a production dependency or a complete PDF benchmark.
Annotations are evaluation-only. No class thresholds, source inputs or model bytes are tuned.
"""
import argparse
from collections import Counter
import hashlib
import importlib.metadata
import json
import math
import os
from pathlib import Path
import time
import sys

from compare_tiled_regions import iou, reference_tables, rows

EXPECTED_MODEL = '134301ca94fb0df8027be9a6dad1908fe6218af8ffa4d34f0819c7c2226195f3'
EXPECTED_SOURCE = '664280229981663cb6e95c3beb417c71c69260afd82aab254833f3ffc52eecb9'


def sha(path):
    """Fingerprint pinned inputs without retaining their bytes."""
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def maximum_matches(references, candidates, threshold=0.5):
    """Count one-to-one envelope matches; duplicate predictions cannot multiply source credit."""
    edges = [[index for index, candidate in sorted(enumerate(candidates),
              key=lambda pair: iou(reference, pair[1]), reverse=True)
              if iou(reference, candidate) >= threshold] for reference in references]
    owners = {}

    def assign(reference, seen):
        """Find an augmenting path so matching cardinality is independent of input ordering."""
        for prediction in edges[reference]:
            if prediction in seen: continue
            seen.add(prediction)
            if prediction not in owners or assign(owners[prediction], seen):
                owners[prediction] = reference
                return True
        return False

    for index in range(len(references)): assign(index, set())
    return sorted((reference, prediction) for prediction, reference in owners.items())


def source_box(box, frame):
    """Invert the physical PDF placement, not integer raster-canvas floor dimensions."""
    xscale = int(frame['original_width']) / (float(frame['pdf_width']) * float(frame['render_dpi']) / 72)
    yscale = int(frame['original_height']) / (float(frame['pdf_height']) * float(frame['render_dpi']) / 72)
    return (box[0] * xscale, box[1] * yscale, box[2] * xscale, box[3] * yscale)


def metric(references, candidates):
    """Retain candidate counts and geometry-only matches without calling unmatched boxes false positives."""
    matches = maximum_matches(references, candidates)
    return {'table_proposals': len(candidates), 'matched_reference_tables_at_iou_0_5': len(matches),
            'unmatched_proposals': len(candidates) - len(matches),
            'matches': [{'reference': ref, 'proposal': pred, 'envelope_iou': iou(references[ref], candidates[pred])}
                        for ref, pred in matches]}


def main():
    """Verify all source/model/capture pins before running unmodified upstream prediction code offline."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--capture21', type=Path, required=True)
    parser.add_argument('--capture25', type=Path, required=True)
    parser.add_argument('--cache', type=Path, required=True)
    parser.add_argument('--manifest', type=Path, required=True)
    parser.add_argument('--model', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists(): raise FileExistsError(args.output)
    if sha(args.model) != EXPECTED_MODEL: raise ValueError('Pinned YOLOX model hash mismatch')
    if 'quantized' in str(args.model):
        raise ValueError('Use a nonquantized model path; upstream selects thresholds by path spelling')
    manifest = json.loads(args.manifest.read_text())
    for entry in manifest['inputs']:
        if sha(args.cache / entry['filename']) != entry['sha256']:
            raise ValueError('Changed source/annotation: ' + entry['filename'])
    capture_files = {p.relative_to(args.capture21) for p in args.capture21.rglob('*') if p.is_file()}
    if capture_files != {p.relative_to(args.capture25) for p in args.capture25.rglob('*') if p.is_file()}:
        raise ValueError('Paired capture file sets differ')
    for name in capture_files:
        if sha(args.capture21 / name) != sha(args.capture25 / name):
            raise ValueError('Paired captured input differs: ' + str(name))
    os.environ['HF_HUB_OFFLINE'] = '1'
    os.environ['TRANSFORMERS_OFFLINE'] = '1'
    import onnxruntime as ort
    from PIL import Image
    import unstructured_inference.models.yolox as upstream
    if sha(Path(upstream.__file__)) != EXPECTED_SOURCE:
        raise ValueError('Upstream YOLOX source changed')
    if importlib.metadata.version('unstructured-inference') != '1.6.13':
        raise ValueError('Expected pinned unstructured-inference1.6.13')
    if str(upstream.YOLOX_LABEL_MAP[8]) != 'Table': raise ValueError('Unexpected upstream table class')
    ort.disable_telemetry_events()
    options = ort.SessionOptions(); options.intra_op_num_threads = 4; options.inter_op_num_threads = 1
    session = ort.InferenceSession(str(args.model), sess_options=options, providers=['CPUExecutionProvider'])
    # Explicit trusted local session avoids model discovery/downloads. Prediction code is unmodified.
    model = upstream.UnstructuredYoloXModel()
    model.model = session
    model.model_path = str(args.model)
    model.layout_classes = upstream.YOLOX_LABEL_MAP
    results = []
    for entry in manifest['inputs']:
        if not entry['filename'].endswith('.jpg'): continue
        name = Path(entry['filename']).stem; capture = args.capture21 / name
        frame = rows(capture / 'source.tsv')[0]
        if frame['sha256'] != entry['sha256']: raise ValueError('Capture/source identity mismatch')
        references = [table['box'] for table in reference_tables(args.cache / (name + '.xml'))]
        outcome = (capture / 'outcome.txt').read_text().strip()
        record = {'input': name, 'source_sha256': entry['sha256'], 'reference_tables': len(references),
                  'outcome': outcome}
        if outcome != 'OK':
            record['tatr'] = metric(references, []); record['yolox'] = metric(references, [])
            results.append(record)
            continue
        render = capture / 'render.png'
        if sha(render) != (capture / 'render.sha256').read_text().strip():
            raise ValueError('Render hash mismatch')
        native = rows(capture / 'detections.tsv')
        tatr = [source_box(tuple(float(row[key]) for key in ('left', 'top', 'right', 'bottom')), frame)
                for row in native if row['label'] == '0']
        with Image.open(render) as image:
            borrowed = image.convert('RGB')
        started = time.monotonic()
        predicted = model.predict(borrowed)
        elapsed = time.monotonic() - started
        objects = [{'label': int(label), 'score': float(score), 'box': list(map(float, box))}
                   for label, score, box in zip(predicted.element_class_ids, predicted.element_probs, predicted.element_coords)]
        if any(not all(math.isfinite(value) for value in [obj['score'], *obj['box']])
               or obj['box'][2] <= obj['box'][0] or obj['box'][3] <= obj['box'][1] for obj in objects):
            raise ValueError('Invalid upstream predicted geometry')
        alternative = [source_box(obj['box'], frame) for obj in objects if obj['label'] == 8]
        record.update({'render_sha256': sha(render), 'tatr': metric(references, tatr),
                       'yolox': metric(references, alternative), 'yolox_all_objects': objects,
                       'yolox_class_counts': dict(Counter(str(obj['label']) for obj in objects)),
                       'yolox_elapsed_seconds': elapsed})
        results.append(record)
        print(name, 'TATR:', record['tatr']['matched_reference_tables_at_iou_0_5'],
              'YOLOX:', record['yolox']['matched_reference_tables_at_iou_0_5'], flush=True)
    report = {'scope': 'Region proposals only; exact shared rasters; no structure/OCR end-to-end or held-out claim',
              'inputs': len(results), 'reference_tables': sum(row['reference_tables'] for row in results),
              'whole_source_limit_inputs': sum(row['outcome'] != 'OK' for row in results),
              'paired_jvm_capture_files_identical': len(capture_files),
              'matching': 'Maximum-cardinality one-to-one axis-aligned envelope IoU>=0.5; not calibrated confidence',
              'settings': {'tatr_threshold': 0.5, 'tatr_max_side': 800, 'tatr_table_class': 0,
                           'yolox_score_threshold': 0.25, 'yolox_class_agnostic_nms': 0.1,
                           'yolox_input_shape': [1024, 768], 'yolox_table_class': 8,
                           'yolox_cpu_threads': 4, 'yolox_preprocessing': 'Unmodified upstream RGB/cv2 bilinear/114 padding',
                           'boundary_handling': 'TATR clips predictions; YOLOX raw boxes retained without repair'},
              'reference_identity': {'model_sha256': EXPECTED_MODEL, 'yolox_source_sha256': EXPECTED_SOURCE,
                                     'packages': {name: importlib.metadata.version(name) for name in
                                                  ('unstructured-inference', 'onnxruntime', 'numpy', 'pillow', 'opencv-python')},
                                     'python': sys.version},
              'aggregate': {profile: {key: sum(row[profile][key] for row in results) for key in
                           ('table_proposals', 'matched_reference_tables_at_iou_0_5', 'unmatched_proposals')}
                            for profile in ('tatr', 'yolox')}, 'results': results}
    args.output.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report['aggregate'], indent=2))


if __name__ == '__main__':
    main()
