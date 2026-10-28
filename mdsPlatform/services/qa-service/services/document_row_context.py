"""Preserve native PDF columns beside an explicitly identified row.

Geometry selects additional original passages; it does not create a joined
record or certify table semantics. Every passage keeps its own native receipt.
"""
import re
from services.read_receipt import verify

VERSION = 'document-native-row-context-2026-10-27.1'
MAX_ROW_SOURCES = 12


def compact(value):
    return re.sub(r'\s+', '', value).casefold()


def same_row(left, right):
    if left.get('page') != right.get('page') or left.get('extractionMethod') != 'pdf-native' or right.get('extractionMethod') != 'pdf-native':
        return False
    a, b = left.get('bbox'), right.get('bbox')
    if not (isinstance(a, list) and isinstance(b, list) and len(a) == len(b) == 4):
        return False
    # Equal row bands avoid borrowing nearby rows or an entire text column.
    if not (0 < a[3] - a[1] <= 64 and 0 < b[3] - b[1] <= 64):
        return False
    return abs(a[1] - b[1]) <= 1.5 and abs(a[3] - b[3]) <= 1.5


def groups(representations, content, identifiers, page_bundles=None, question=''):
    passages = [r for r in representations if r.get('kind') == 'text_passage' and content.get(r['representationId'])
                and r.get('locator', {}).get('extractionMethod') == 'pdf-native']
    result = []
    for anchor in passages:
        matches = [s for s in identifiers if compact(s) in compact(content[anchor['representationId']])]
        if not matches:
            continue
        members = [r for r in passages if same_row(anchor['locator'], r['locator'])]
        if len(members) < 2:
            continue
        members.sort(key=lambda r: (r['locator']['bbox'][0], r['locator'].get('characterStart', 0), r['representationId']))
        group={'anchor': anchor, 'identifiers': matches, 'members': members, 'headers': [], 'columnHeaders': {}}
        if page_bundles:
            page=page_bundles.get(anchor['locator']['page']);candidates=[]
            for block in (page or {}).get('content',{}).get('nativeBlocks',[]):
                spans=[span for line in block['lines'] for span in line['spans'] if span['text'].strip()]
                if len({round(span['bbox'][0],1) for span in spans})<2 or block['bbox'][3]>=anchor['locator']['bbox'][1]:continue
                headers=[r for r in passages if r['locator'].get('page')==anchor['locator']['page'] and r['locator'].get('blockIndex')==block['blockIndex']]
                if headers:candidates.append((block,spans,headers))
            if candidates:
                block,spans,headers=max(candidates,key=lambda row:row[0]['bbox'][3])
                wanted=[span for span in spans if any(len(compact(part))>=2 and compact(part) in compact(question)
                    for part in re.split(r'[/、|，,:：\s]+',span['text']))]
                specific={compact(part) for span in wanted for part in re.split(r'[/、|，,:：\s]+',span['text'])
                    if len(compact(part))>=2 and compact(part) in compact(question)
                    and compact(part) not in {'单位','计量单位','数量','页码','编号','名称','行号','备注'}}
                if len(specific)<2:wanted=[]
                projected=[r for r in members if r['representationId']==anchor['representationId'] or
                    any(abs(r['locator']['bbox'][0]-span['bbox'][0])<=1.5 for span in wanted)] if wanted else members
                group.update(members=projected,headers=headers,headerColumns=[{'text':s['text'],'bbox':s['bbox']} for s in spans],
                    unrequestedColumnRepresentationIds=[r['representationId'] for r in members if r not in projected])
                for member in projected:
                    match=[s['text'] for s in spans if abs(member['locator']['bbox'][0]-s['bbox'][0])<=1.5]
                    if len(match)==1:group['columnHeaders'][member['representationId']]=match[0]
        result.append(group)
    return result


def verified(item, question):
    proof = item.get('provenance', {}).get('documentRowContext')
    if not proof or proof.get('version') != VERSION:
        return False
    bundle = item.get('nativeBundle', {})
    for anchor in proof.get('anchors', []):
        parent = anchor.get('bundle', {})
        try:
            verify(parent)
        except ValueError:
            continue
        identifier = anchor.get('identifier', '')
        if (identifier and identifier in question and compact(identifier) in compact(parent.get('content', {}).get('text', ''))
                and parent.get('identity') == bundle.get('identity')
                and same_row(parent.get('locator', {}), bundle.get('locator', {}))):
            return True
    return False
