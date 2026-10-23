"""Execute only pinned Unstructured OCR on original scan images; JVM code owns every comparison."""
import hashlib
import importlib.metadata
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys


def digest(path):
    """Return the original byte identity of a supplied local resource."""
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def decoded(source):
    """Record the pixels upstream's image path hands to its OCR agent, without altering that path."""
    import numpy
    from PIL import Image
    with Image.open(source) as image:
        profile = image.info.get('icc_profile')
        array = numpy.array(image)
        return {'format': image.format, 'mode': image.mode, 'width': image.width, 'height': image.height,
                'frames': getattr(image, 'n_frames', 1), 'dpi': [float(value) for value in image.info.get('dpi', ())],
                'icc_profile_sha256': hashlib.sha256(profile).hexdigest() if profile else None,
                'array_shape': list(array.shape), 'array_dtype': str(array.dtype),
                'array_sha256': hashlib.sha256(array.tobytes()).hexdigest()}


def main():
    """Verify every original/model, forbid silent language fallback and retain each whole-image failure."""
    manifest_path, sources, tessdata, output = map(Path, sys.argv[1:5])
    manifest = json.loads(manifest_path.read_text())
    os.environ.update(HF_HUB_OFFLINE='1', TRANSFORMERS_OFFLINE='1', UNSTRUCTURED_DISABLE_NOTICE='1',
                      TESSDATA_PREFIX=str(tessdata.resolve()), OMP_THREAD_LIMIT='1',
                      OCR_AGENT='unstructured.partition.utils.ocr_models.tesseract_ocr.OCRAgentTesseract')
    if importlib.metadata.version('unstructured') != manifest['unstructured_version']:
        raise ValueError('Pinned runtime differs')
    if digest(shutil.which('tesseract')) != manifest['engine_sha256']:
        raise ValueError('Pinned engine differs')
    for case in manifest['cases']:
        source = sources / case['image']
        if source.stat().st_size != case['bytes'] or digest(source) != case['sha256']:
            raise ValueError('Original scan image differs')
        if digest(tessdata / (case['effective_upstream_language'] + '.traineddata')) != case['model_sha256']:
            raise ValueError('Explicit language/model binding differs')
    if len(sys.argv) == 6:
        case = next(case for case in manifest['cases'] if case['id'] == sys.argv[5])
        from unstructured.partition.common.lang import prepare_languages_for_tesseract
        if prepare_languages_for_tesseract([case['effective_upstream_language']]) != case['effective_upstream_language']:
            raise ValueError('Upstream would silently substitute the requested language')
        decode = decoded(sources / case['image'])
        from unstructured.partition.image import partition_image
        elements = partition_image(filename=str(sources / case['image']), strategy=manifest['strategy'],
                                   languages=[case['effective_upstream_language']], include_page_breaks=False)
        with output.open('x') as stream:
            json.dump({'outcome': 'SUCCESS', 'source_sha256': case['sha256'], 'model_sha256': case['model_sha256'],
                       'effective_upstream_language': case['effective_upstream_language'], 'decode': decode,
                       'elements': [element.to_dict() for element in elements]}, stream, ensure_ascii=False)
        return
    output.mkdir()
    from unstructured.partition.common.lang import prepare_languages_for_tesseract
    mapping = {language: prepare_languages_for_tesseract([language]) for language in ('deu', 'deu_latf', 'frk')}
    runtime = {'python': sys.version, 'packages': {dist.metadata['Name']: dist.version
                                                 for dist in importlib.metadata.distributions()},
               'manifest_sha256': digest(manifest_path), 'runner_sha256': digest(__file__),
               'literal_language_mapping': mapping, 'engine_sha256': manifest['engine_sha256']}
    (output / 'runtime.json').write_text(json.dumps(runtime, indent=2) + '\n')
    for case in manifest['cases']:
        target = output / (case['id'] + '.json')
        outcome = None
        print('START', case['id'], flush=True)
        with (output / (case['id'] + '.log')).open('x') as log:
            command = [sys.executable, __file__, str(manifest_path), str(sources), str(tessdata), str(target), case['id']]
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
            if target.exists():
                raise RuntimeError('Failed process left a partial successful capture')
            with target.open('x') as stream:
                json.dump({'outcome': outcome, 'source_sha256': case['sha256'], 'model_sha256': case['model_sha256'],
                           'effective_upstream_language': case['effective_upstream_language'], 'elements': []}, stream)
        result = json.loads(target.read_text())
        print('END', case['id'], result['outcome'], len(result['elements']), flush=True)


if __name__ == '__main__':
    main()
