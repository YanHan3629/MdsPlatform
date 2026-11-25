"""Locate literal numeric labels in the image actually sent to the model."""

from collections import defaultdict
from decimal import Decimal
from io import BytesIO
import os
from pathlib import Path
import re
import subprocess
from tempfile import TemporaryDirectory
from time import monotonic
import unicodedata

from PIL import Image

from .document_native import OCR_LANGUAGES, recognize


RECIPE = 'attachment-label-double-ocr-1'
MAX_PIXELS = 2_097_152
MAX_LABELS = 12
DRAWING_UNIT_RECIPE = 'drawing-unit-double-ocr-1'
DRAWING_UNITS = {'mm','cm','m','um','μm','毫米','厘米','米','微米'}
VALUE = r'[+-]?[0-9]{1,12}(?:\.[0-9]{1,8})?'
LABEL = re.compile(
    r'(?P<label>[A-Za-z\u3400-\u9fff][A-Za-z\u3400-\u9fff _-]{1,63}?)'
    r'(?:\s*[:=：]\s*|\s+)'
    rf'(?P<value>{VALUE})'
    r'(?:\s*(?P<unit>毫米|厘米|微米|千米|米|公斤|千克|kg|mm|cm|km|m|μm|um|rpm|kPa|MPa|Pa|℃|°C|°|伏特|伏|赫兹|V|Hz))?',
    re.I,
)
ELECTRICAL_PAIR = re.compile(
    r'(?:(?:[A-Za-z\u3400-\u9fff][A-Za-z\u3400-\u9fff _-]{0,63})(?:\s*[:=：]\s*|\s+)|(?:AC|交流)\s*)?'
    rf'(?P<voltage>{VALUE})\s*(?:V|伏特?)\s*~?\s*/\s*(?P<frequency>{VALUE})\s*(?:Hz|赫兹)',
    re.I,
)


def _drawing_unit(text):
    normalized = unicodedata.normalize('NFKC',text).strip().strip(' 。;；')
    match = re.fullmatch(r'(?:units?|图纸单位|尺寸单位|长度单位|单位)\s*[:：=]?\s*[\[(（]?\s*(mm|cm|um|μm|m|毫米|厘米|微米|米)\s*[\])）]?',normalized,re.I)
    return match[1].casefold() if match else None


def _drawing_unit_candidates(lines):
    candidates = []
    for words in lines:
        for start in range(len(words)):
            for end in range(start+1,min(len(words),start+4)+1):
                window = words[start:end]
                unit = _drawing_unit(' '.join(word['text'] for word in window))
                if unit:
                    candidates.append((window,unit))
                    break
    return candidates


def _parse_line(text):
    normalized = unicodedata.normalize('NFKC', text).strip().strip(' 。;；')
    electrical = ELECTRICAL_PAIR.fullmatch(normalized)
    if electrical:
        return (('电压', electrical['voltage'], 'V'), ('频率', electrical['frequency'], 'Hz'))
    match = LABEL.fullmatch(normalized)
    if match is None:
        return ()
    label = ' '.join(match['label'].split())
    return ((label, match['value'], match['unit']),)


def _same_label(left, right):
    return (
        left[0].casefold() == right[0].casefold()
        and Decimal(left[1]) == Decimal(right[1])
        and (left[2] or '').casefold() == (right[2] or '').casefold()
    )


def observe(raw, timeout_seconds):
    deadline = monotonic() + timeout_seconds
    with Image.open(BytesIO(raw)) as image:
        if image.format != 'PNG' or getattr(image, 'n_frames', 1) != 1 or image.width * image.height > MAX_PIXELS:
            raise ValueError('ATTACHMENT_LABEL_IMAGE_UNSUPPORTED')
        image.load()
        result = recognize(image, timeout_seconds=max(0.1, deadline - monotonic()))
        lines = defaultdict(list)
        for word in result['words']:
            lines[word['block'], word['paragraph'], word['line']].append(word)
        labels = []
        units = []
        crop_failures = 0
        crop_reads = 0
        parsed = [(words, _parse_line(' '.join(word['text'] for word in words))) for words in lines.values()]
        unit_candidates = _drawing_unit_candidates(lines.values())
        candidates = [(words,items,False) for words,items in parsed if items]
        candidates = [(words,unit,True) for words,unit in unit_candidates if unit] + candidates
        with TemporaryDirectory(prefix='mds-attachment-label-') as directory:
            crop = Path(directory) / 'label.png'
            for words, first, unit_declaration in candidates:
                if (len(labels) == MAX_LABELS and not unit_declaration) or deadline - monotonic() < 0.2:
                    break
                if not unit_declaration and len(labels) + len(first) > MAX_LABELS:
                    continue
                boxes = [word['bbox'] for word in words]
                left, top = min(b[0] for b in boxes), min(b[1] for b in boxes)
                right, bottom = max(b[2] for b in boxes), max(b[3] for b in boxes)
                pad = max(3, (bottom - top) // 5)
                image.crop((max(0, left - pad), max(0, top - pad), min(image.width, right + pad),
                            min(image.height, bottom + pad))).save(crop)
                try:
                    second = subprocess.run(
                        ['tesseract', str(crop), 'stdout', '-l', OCR_LANGUAGES, '--psm', '7'],
                        stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, encoding='utf-8',
                        timeout=max(0.1, deadline - monotonic()), check=False, env={**os.environ, 'OMP_THREAD_LIMIT': '1'},
                    )
                except subprocess.TimeoutExpired:
                    crop_failures += 1
                    break
                if second.returncode:
                    crop_failures += 1
                    continue
                crop_reads += 1
                if unit_declaration:
                    if _drawing_unit(second.stdout) == first and len(units) < 4:
                        units.append({'unitText':first,'bbox':[left,top,right,bottom]})
                    continue
                confirmed = _parse_line(second.stdout)
                if len(first) == len(confirmed) and all(_same_label(a, b) for a, b in zip(first, confirmed)):
                    labels.extend({'label': label, 'valueText': value, 'unitText': unit,
                                   'bbox': [left, top, right, bottom]} for label, value, unit in first)
        if len({entry['unitText'] for entry in units}) > 1:
            units = []
        return {'recipe': RECIPE, 'drawingUnitRecipe':DRAWING_UNIT_RECIPE,'drawingUnits':units,
                'width': image.width, 'height': image.height, 'observations': labels,
                'ocrStatus': result['status'], 'recognizedWordCount': len(result['words']),
                'candidateLineCount': len(candidates), 'pageOcrMilliseconds': result.get('elapsedMs'),
                'cropOcrReads': crop_reads, 'cropOcrFailures': crop_failures}
