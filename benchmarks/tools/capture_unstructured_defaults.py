"""Execute the unchanged Unstructured default PDF entry point; JVM tooling owns comparison/scoring."""
import hashlib
import importlib.metadata
import json
import os
from pathlib import Path
import signal
import subprocess
import sys


def digest(path):
    """Identify original pinned bytes without modifying the input."""
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def main():
    """Capture every default result or explicit failure without selecting strategies/languages by source."""
    manifest_path, cache, output = map(Path, sys.argv[1:4])
    manifest = json.loads(manifest_path.read_text())
    os.environ.update(HF_HUB_OFFLINE='1', TRANSFORMERS_OFFLINE='1', UNSTRUCTURED_DISABLE_NOTICE='1')
    for name in ('PDF_RENDER_DPI', 'OCR_AGENT', 'UNSTRUCTURED_DEFAULT_MODEL_INITIALIZE_PARAMS_JSON_PATH'):
        os.environ.pop(name, None)
    if importlib.metadata.version('unstructured') != manifest['unstructured_version']:
        raise ValueError('Pinned upstream runtime differs')
    for case in manifest['cases']:
        source = cache / case['pdf']
        if source.stat().st_size != case['bytes'] or digest(source) != case['sha256']:
            raise ValueError('Pinned default source differs')
    if len(sys.argv) == 5:
        case = next(case for case in manifest['cases'] if case['id'] == sys.argv[4])
        from unstructured.partition.pdf import partition_pdf
        elements = partition_pdf(filename=str(cache / case['pdf']))
        with output.open('x') as stream:
            json.dump({'outcome': 'SUCCESS', 'source_sha256': case['sha256'],
                       'elements': [element.to_dict() for element in elements]}, stream, ensure_ascii=False)
        return
    output.mkdir()
    from unstructured.partition.utils.config import env_config
    runtime = {'python': sys.version, 'packages': {dist.metadata['Name']: dist.version
                                                 for dist in importlib.metadata.distributions()},
               'manifest_sha256': digest(manifest_path), 'runner_sha256': digest(__file__),
               'default_render_dpi': env_config.PDF_RENDER_DPI, 'default_ocr_agent': env_config.OCR_AGENT}
    (output / 'runtime.json').write_text(json.dumps(runtime, indent=2) + '\n')
    for case in manifest['cases']:
        target = output / (case['id'] + '.json')
        outcome = None
        print('START', case['id'], flush=True)
        with (output / (case['id'] + '.log')).open('x') as log:
            command = [sys.executable, __file__, str(manifest_path), str(cache), str(target), case['id']]
            with subprocess.Popen(command, stdout=log, stderr=log, start_new_session=True) as process:
                try:
                    if process.wait(timeout=manifest['upstream_timeout_seconds']) != 0:
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
            if target.exists():
                raise RuntimeError('Failed process left a partial successful capture')
            with target.open('x') as stream:
                json.dump({'outcome': outcome, 'source_sha256': case['sha256'], 'elements': []}, stream)
        captured = json.loads(target.read_text())
        print('END', case['id'], captured['outcome'], len(captured['elements']), flush=True)


if __name__ == '__main__':
    main()
