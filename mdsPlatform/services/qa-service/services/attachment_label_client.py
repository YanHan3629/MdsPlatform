"""Collect bounded OCR observations for the exact user images sent to Qwen."""

import json
import os
import re
from time import monotonic
from urllib.error import URLError
from urllib.request import HTTPRedirectHandler, ProxyHandler, Request, build_opener


class _NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


_OPENER = build_opener(ProxyHandler({}), _NoRedirect)
_RECIPE = 'attachment-label-double-ocr-1'
_LABEL = re.compile(r'[A-Za-z\u3400-\u9fff][A-Za-z\u3400-\u9fff _-]{1,63}')
_VALUE = re.compile(r'[+-]?[0-9]{1,12}(?:\.[0-9]{1,8})?')
_UNIT = re.compile(r'[A-Za-z\u3400-\u9fffμµ℃°]{1,8}')
_ENDPOINT = 'http://127.0.0.1:18083/internal/v1/evidence/attachment-labels'
_DRAWING_UNITS = {'mm','cm','m','um','μm','毫米','厘米','米','微米'}
_ELECTRICAL_UNITS = {'v', 'hz', '伏', '伏特', '赫兹'}


def _image_labels(part, token, timeout):
    body = json.dumps({'imageSha256': part['sha256'], 'imageBase64': part['url'].split(',', 1)[1]}).encode()
    request = Request(_ENDPOINT, data=body, headers={
        'Content-Type': 'application/json',
        'X-MDS-Attachment-Label-Token': token,
    })
    try:
        with _OPENER.open(request, timeout=timeout) as response:
            raw = response.read(24577)
        if len(raw) > 24576:
            return None
        labels = json.loads(raw)
        if (
            labels['recipe'] != _RECIPE
            or labels['imageSha256'] != part['sha256']
            or (labels['width'], labels['height']) != (part['width'], part['height'])
            or not isinstance(labels['observations'], list)
            or not 0 <= len(labels['observations']) <= 12
        ):
            return None
        if 'drawingUnitRecipe' in labels:
            if labels['drawingUnitRecipe'] != 'drawing-unit-double-ocr-1':
                return None
            units = labels.get('drawingUnits')
            if not isinstance(units,list) or len(units)>4:
                return None
            for entry in units:
                box = entry.get('bbox')
                if (set(entry) != {'unitText','bbox'} or entry['unitText'] not in _DRAWING_UNITS
                    or not isinstance(box,list) or len(box)!=4 or any(type(v) is not int for v in box)
                    or not 0 <= box[0] < box[2] <= part['width'] or not 0 <= box[1] < box[3] <= part['height']):
                    return None
            if len({entry['unitText'] for entry in units})>1:
                return None
        elif labels.get('drawingUnits'):
            return None
        if not labels['observations'] and not labels.get('drawingUnits'):
            return None
        for item in labels['observations']:
            box = item['bbox']
            if (
                set(item) != {'label', 'valueText', 'unitText', 'bbox'}
                or not _LABEL.fullmatch(item['label'])
                or not _VALUE.fullmatch(item['valueText'])
                or (item['unitText'] is not None and not _UNIT.fullmatch(item['unitText']))
                or not isinstance(box, list) or len(box) != 4 or any(type(n) is not int for n in box)
                or not 0 <= box[0] < box[2] <= part['width']
                or not 0 <= box[1] < box[3] <= part['height']
            ):
                return None
    except (OSError, URLError, ValueError, KeyError, TypeError):
        return None
    return labels


def collect(parts, budget, *, include_electrical=True):
    token = os.environ.get('QA_ATTACHMENT_LABEL_TOKEN')
    if not token or not parts:
        return {}
    deadline = monotonic() + min(9.0, max(0.0, budget.remaining() - 12.0))
    results = {}
    for part in parts:
        timeout = min(6.0, deadline - monotonic(), budget.remaining())
        if timeout < 0.5:
            break
        labels = _image_labels(part, token, timeout)
        if not labels:
            continue
        if not include_electrical:
            observations = [entry for entry in labels['observations']
                            if (entry['unitText'] or '').casefold() not in _ELECTRICAL_UNITS]
            labels = {**labels, 'observations': observations}
        if labels['observations'] or labels.get('drawingUnits'):
            results[part['sourceId']] = labels
    return results
