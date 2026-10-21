"""Explicit, offline Unstructured comparison; emits counts/hashes, never source text or images.

Requires the separate pinned reference environment documented in pdf-upstream-table/README.md.
This is a Python reference launcher, not a Java product dependency or a production test.
"""

import argparse
import copy
import csv
import hashlib
import importlib
import importlib.metadata
import json
import os
import re
import shutil
import sys
from pathlib import Path


def digest(path):
    """Hash a local input without including its content in the evidence report."""
    with Path(path).open('rb') as source:
        return hashlib.file_digest(source, 'sha256').hexdigest()


def normalize(text):
    """Collapse ASCII whitespace, preserving case, punctuation and Unicode characters."""
    return re.sub(r'[ \t\n\v\f\r]+', ' ', text).strip()


def html_grid(html, labels=None):
    """Decode row-major spans without repairing missing/overlapping source cells."""
    from bs4 import BeautifulSoup
    soup = BeautifulSoup(html, 'html.parser')
    rows = soup.select('table > tr, table > thead > tr, table > tbody > tr, table > tfoot > tr')
    if not rows or len(rows) > 1000:
        raise ValueError('Invalid table row count')
    occupied, cells, columns = {}, [], 0
    for row, tr in enumerate(rows):
        column = 0
        for td in tr.find_all(['td', 'th'], recursive=False):
            while occupied.get(column, 0) > row:
                column += 1
            rs, cs = int(td.get('rowspan', 1)), int(td.get('colspan', 1))
            if rs < 1 or cs < 1 or row + rs > len(rows) or column + cs > 1000:
                raise ValueError('Invalid table span')
            for position in range(column, column + cs):
                if occupied.get(position, 0) > row:
                    raise ValueError('Overlapping table spans')
                occupied[position] = row + rs
            text = td.get_text()
            if labels is not None:
                text = BeautifulSoup(''.join(labels[len(cells)]['tokens']), 'html.parser').get_text()
            cells.append((row, column, rs, cs, normalize(text)))
            column += cs
            columns = max(columns, column)
    if labels is not None and len(labels) != len(cells):
        raise ValueError('Annotation count differs from topology')
    return len(rows), columns, cells


def cell_grid(cells):
    """Adapt upstream cells with actual row/column lists; never fabricate empty positions."""
    if not cells:
        return 0, 0, []
    result = []
    for cell in cells:
        rows, columns = sorted(cell['row_nums']), sorted(cell['column_nums'])
        if rows != list(range(rows[0], rows[-1] + 1)) or columns != list(range(columns[0], columns[-1] + 1)):
            raise ValueError('Nonrectangular upstream cell')
        result.append((rows[0], columns[0], len(rows), len(columns), normalize(cell['cell text'])))
    return max(c[0] + c[2] for c in result), max(c[1] + c[3] for c in result), result


def metrics(expected, actual):
    """Compare literal positional/span keys and exact normalized strings on this single input."""
    expected_keys = {c[:4] for c in expected[2]}
    actual_keys = {c[:4] for c in actual[2]}
    if len(expected_keys) != len(expected[2]) or len(actual_keys) != len(actual[2]):
        raise ValueError('Duplicate positional/span key')
    return {'rows': actual[0], 'columns': actual[1], 'anchors': len(actual[2]),
            'positional_span_matches': len(expected_keys & actual_keys),
            'exact_cell_text_matches': len(set(expected[2]) & set(actual[2])),
            'complete_topology': expected[:2] == actual[:2] and expected_keys == actual_keys,
            'complete_topology_and_text': expected[:2] == actual[:2] and set(expected[2]) == set(actual[2])}


def main():
    """Run isolated postprocessing, shared-weight recognition and the real public PDF entry point."""
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('capture', 'cache', 'filename', 'matched-model', 'default-model', 'layout-model', 'output'):
        parser.add_argument('--' + name, required=True)
    args = parser.parse_args()
    if Path(args.output).exists():
        raise FileExistsError(args.output)
    # Forbid implicit model downloads during comparison, including fallback backbone initialization.
    os.environ['HF_HUB_OFFLINE'] = '1'
    os.environ['TRANSFORMERS_OFFLINE'] = '1'
    import torch
    from PIL import Image
    from unstructured.partition.pdf_image.ocr import get_table_tokens
    from unstructured.partition.utils.ocr_models.tesseract_ocr import OCRAgentTesseract
    from unstructured.partition.utils.config import env_config
    from unstructured_inference.config import inference_config
    from unstructured_inference.models import tables
    from unstructured_inference.models.yolox import YOLOX_LABEL_MAP
    torch.set_num_threads(4)
    root = Path(__file__).resolve().parents[2]
    manifest = json.loads((root / 'benchmarks/review/pdf-pubtabnet/manifest.json').read_text())
    cache, capture = Path(args.cache), Path(args.capture)
    if (capture / 'source.sha256').read_text().strip() != digest(cache / args.filename):
        raise ValueError('Capture does not match selected original image')
    for name in (args.filename, 'PubTabNet_Examples.jsonl'):
        entry = next(e for e in manifest['inputs'] if e['filename'] == name)
        if digest(cache / name) != entry['sha256'] or (cache / name).stat().st_size != entry['bytes']:
            raise ValueError('Source manifest mismatch: ' + name)
    references = [json.loads(line) for line in (cache / 'PubTabNet_Examples.jsonl').read_text().splitlines()]
    reference = next(r for r in references if r['filename'] == args.filename)
    expected = html_grid('<table>' + ''.join(reference['html']['structure']['tokens']) + '</table>', reference['html']['cells'])
    with Image.open(capture / 'render.png') as source:
        image = source.convert('RGB')
    tokens = get_table_tokens(image, OCRAgentTesseract('eng'))
    class_map = {v: k for k, v in tables.get_class_map('structure').items()}
    with (capture / 'objects.tsv').open() as stream:
        objects = [{'label': class_map[int(r['label'])], 'score': float(r['score']),
                    'bbox': [float(r[k]) for k in ('left', 'top', 'right', 'bottom')]}
                   for r in csv.DictReader(stream, delimiter='\t')]
    structures = tables.objects_to_structures(
        tables.apply_thresholds_on_objects(copy.deepcopy(objects), tables.structure_class_thresholds),
        copy.deepcopy(tokens), tables.structure_class_thresholds)
    isolated = [tables.structure_to_cells(s, copy.deepcopy(tokens))[0] for s in structures]
    model = tables.tables_agent
    model.initialize(args.matched_model, device='cpu')
    shared = model.predict(image, ocr_tokens=copy.deepcopy(tokens), result_format='cells')
    # Use the supported local-model configuration, preserving model identity and upstream code.
    params = capture / 'yolox-params.json'
    configuration = json.dumps({'model_path': str(Path(args.layout_model).resolve()), 'label_map': YOLOX_LABEL_MAP})
    if params.exists():
        if params.read_text() != configuration:
            raise ValueError('Existing capture model configuration differs')
    else:
        with params.open('x') as output:
            output.write(configuration)
    os.environ['UNSTRUCTURED_DEFAULT_MODEL_INITIALIZE_PARAMS_JSON_PATH'] = str(params)
    model.initialize(args.default_model, device='cpu')
    from unstructured.partition.pdf import partition_pdf
    elements = partition_pdf(filename=str(capture / 'input.pdf'), strategy='hi_res', infer_table_structure=True,
                             languages=['eng'], pdf_image_dpi=300, hi_res_model_name='yolox')
    public = [html_grid(e.metadata.text_as_html) for e in elements if e.category == 'Table' and e.metadata.text_as_html]
    sources = ('unstructured.partition.pdf', 'unstructured.partition.pdf_image.ocr',
               'unstructured.partition.utils.ocr_models.tesseract_ocr', 'unstructured_inference.models.tables',
               'unstructured_inference.models.table_postprocess', 'unstructured_inference.config',
               'unstructured_inference.models.yolox', 'unstructured_inference.models.base')
    report = {'scope': 'One development-selected published train image; no held-out/general accuracy claim',
              'input': args.filename, 'source_commit': manifest['commit'],
              'published': {'rows': expected[0], 'columns': expected[1], 'anchors': len(expected[2])},
              'python': sys.version, 'dependencies': {d.metadata['Name']: d.version for d in importlib.metadata.distributions()},
              'source_sha256': {name: digest(importlib.import_module(name).__file__) for name in sources},
              'harness_sha256': digest(__file__), 'capture_harness_sha256': digest(root / 'benchmarks/tools/CaptureTableReferenceInput.java'),
              'capture_java_runtime': (capture / 'java-runtime.txt').read_text().splitlines(),
              'capture_sha256': {name: digest(capture / name) for name in ('input.pdf', 'render.png', 'objects.tsv', 'docweave-tables.txt', 'source.sha256', 'java-runtime.txt')},
              'model_sha256': {str(p): digest(p) for p in (Path(args.matched_model) / 'model.safetensors',
                                Path(args.default_model) / 'model.safetensors', Path(args.layout_model))},
              'tesseract_sha256': digest(Path(os.environ['TESSDATA_PREFIX']) / 'eng.traineddata'),
              'ocr_policy': 'Unmodified upstream Tesseract HOCR character filter and automatic text-height zoom; not DocWeave PSM6',
              'ocr_tokens': len(tokens), 'decoded_objects': len(objects),
              'tesseract_executable_sha256': digest(shutil.which('tesseract')),
              'settings': {'strategy': 'hi_res', 'render_dpi': 300, 'languages': ['eng'],
                           'inference_device': 'cpu', 'torch_threads': torch.get_num_threads(),
                           'table_model_size': model.feature_extractor.size,
                           'table_model_background_pad': inference_config.TABLE_IMAGE_BACKGROUND_PAD,
                           'class_thresholds': tables.structure_class_thresholds,
                           'public_table_crop_pad': env_config.TABLE_IMAGE_CROP_PAD,
                           'tesseract_character_confidence_threshold': env_config.TESSERACT_CHARACTER_CONFIDENCE_THRESHOLD,
                           'tesseract_text_height_quantile': env_config.TESSERACT_TEXT_HEIGHT_QUANTILE,
                           'tesseract_min_text_height': env_config.TESSERACT_MIN_TEXT_HEIGHT,
                           'tesseract_max_text_height': env_config.TESSERACT_MAX_TEXT_HEIGHT,
                           'tesseract_optimum_text_height': env_config.TESSERACT_OPTIMUM_TEXT_HEIGHT},
              'docweave_structure_tables': int((capture / 'docweave-tables.txt').read_text()),
              'isolated_upstream_postprocessing': [metrics(expected, cell_grid(c)) for c in isolated],
              'shared_weights_upstream_recognition': metrics(expected, cell_grid(shared)),
              'public_pdf_api_default_model_names_with_pinned_local_files': [metrics(expected, g) for g in public],
              'public_detected_tables': sum(e.category == 'Table' for e in elements),
              'public_element_types': [e.category for e in elements]}
    with Path(args.output).open('x') as output:
        json.dump(report, output, indent=2, sort_keys=True)
        output.write('\n')
    print(json.dumps({k: report[k] for k in ('isolated_upstream_postprocessing', 'shared_weights_upstream_recognition',
                                           'public_pdf_api_default_model_names_with_pinned_local_files')}), flush=True)


if __name__ == '__main__':
    main()
