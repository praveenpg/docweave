"""Execute pinned Unstructured hi-res on whole original PDFs; all scoring remains on the JVM."""
import hashlib
import importlib.metadata
import json
import os
from pathlib import Path
import signal
import subprocess
import sys


def digest(path):
    """Hash one explicitly supplied upstream resource without modifying it."""
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def validate(manifest, sources, assets):
    """Require the installed runtime and every preinstalled pinned input; never install resources."""
    if importlib.metadata.version('unstructured') != manifest['unstructured_version']:
        raise ValueError('Unstructured version differs')
    for resource in manifest['resources']:
        path = assets / resource['local_path']
        if path.stat().st_size != resource['bytes'] or digest(path) != resource['sha256']:
            raise ValueError('Pinned upstream model differs')
    for case in manifest['cases']:
        path = sources / case['pdf']
        if path.stat().st_size != case['bytes'] or digest(path) != case['sha256']:
            raise ValueError('Pinned source PDF differs')
    import shutil
    executable = shutil.which('tesseract')
    if executable is None or digest(executable) != manifest['upstream_profile']['engine_sha256']:
        raise ValueError('Pinned Tesseract executable differs')
    if digest(Path(os.environ['TESSDATA_PREFIX']) / 'eng.traineddata') != manifest['upstream_profile']['english_model_sha256']:
        raise ValueError('Pinned English OCR model differs')


def worker(manifest, case, sources, assets, output):
    """Invoke the real public upstream pipeline without labels, reference crops or source patches."""
    import torch
    from unstructured_inference.models import tables
    from unstructured_inference.models.yolox import YOLOX_LABEL_MAP
    torch.set_num_threads(manifest['upstream_profile']['torch_threads'])
    params = output.parent / (case['id'] + '-yolox-params.json')
    params.write_text(json.dumps({'model_path': str((assets / 'layout/yolox_l0.05.onnx').resolve()),
                                 'label_map': YOLOX_LABEL_MAP}))
    os.environ['UNSTRUCTURED_DEFAULT_MODEL_INITIALIZE_PARAMS_JSON_PATH'] = str(params.resolve())
    tables.tables_agent.initialize(str((assets / 'default-table').resolve()), device='cpu')
    from unstructured.partition.pdf import partition_pdf
    profile = manifest['upstream_profile']
    elements = partition_pdf(filename=str(sources / case['pdf']), strategy=profile['strategy'],
                             infer_table_structure=profile['infer_table_structure'], languages=profile['languages'],
                             pdf_image_dpi=profile['pdf_image_dpi'], hi_res_model_name=profile['hi_res_model_name'])
    with output.open('x') as stream:
        json.dump({'outcome': 'SUCCESS', 'source_sha256': case['sha256'],
                   'elements': [element.to_dict() for element in elements]}, stream, ensure_ascii=False)


def main():
    """Capture all frozen documents with bounded child processes and explicit whole-document failures."""
    manifest_path, source_path, asset_path, output_path = map(Path, sys.argv[1:5])
    manifest = json.loads(manifest_path.read_text())
    os.environ.update(HF_HUB_OFFLINE='1', TRANSFORMERS_OFFLINE='1', UNSTRUCTURED_DISABLE_NOTICE='1',
                      OCR_AGENT=manifest['upstream_profile']['ocr_agent'])
    validate(manifest, source_path, asset_path)
    if len(sys.argv) == 6:
        case = next(case for case in manifest['cases'] if case['id'] == sys.argv[5])
        worker(manifest, case, source_path, asset_path, output_path)
        return
    output_path.mkdir()
    runtime = {'python': sys.version, 'packages': {dist.metadata['Name']: dist.version
                                                 for dist in importlib.metadata.distributions()},
               'manifest_sha256': digest(manifest_path), 'runner_sha256': digest(__file__)}
    (output_path / 'runtime.json').write_text(json.dumps(runtime, indent=2) + '\n')
    for case in manifest['cases']:
        target = output_path / (case['id'] + '.json')
        print('START', case['id'], flush=True)
        outcome = None
        with (output_path / (case['id'] + '.log')).open('x') as log:
            command = [sys.executable, __file__, str(manifest_path), str(source_path), str(asset_path), str(target), case['id']]
            with subprocess.Popen(command, stdout=log, stderr=log, start_new_session=True) as process:
                try:
                    if process.wait(timeout=manifest['document_timeout_seconds']) != 0:
                        outcome = 'UpstreamProcessFailure'
                except subprocess.TimeoutExpired:
                    outcome = 'UpstreamDocumentTimeout'
                    os.killpg(process.pid, signal.SIGTERM)
                    try:
                        process.wait(timeout=5)
                    except subprocess.TimeoutExpired:
                        os.killpg(process.pid, signal.SIGKILL)
                        process.wait(timeout=5)
        if outcome is not None:
            # No partial extraction is scored as successful when the entire-document pipeline fails.
            if target.exists():
                raise RuntimeError('Failed upstream process left an unexpected successful output')
            with target.open('x') as stream:
                json.dump({'outcome': outcome, 'source_sha256': case['sha256'], 'elements': []}, stream)
        captured = json.loads(target.read_text())
        print('END', case['id'], captured['outcome'], len(captured['elements']), flush=True)


if __name__ == '__main__':
    main()
