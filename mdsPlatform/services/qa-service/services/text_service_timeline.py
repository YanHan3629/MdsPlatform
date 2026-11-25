"""Derive service elapsed time from labeled native TXT timestamps."""
import re
from fractions import Fraction

from services.document_time import TIMESTAMP, elapsed_microseconds


VERSION = 'txt-service-timeline-1'
STAMP = re.compile(r'(?<![0-9A-Za-z])' + TIMESTAMP + r'(?![0-9A-Za-z:+-])')
INTAKE = re.compile(r'受理|登记')
INSPECTION = re.compile(r'检查|检视')
REVIEW = re.compile(r'复核|复测|复验')
ROLE_PATTERNS = {'intake': INTAKE, 'inspection': INSPECTION, 'review': REVIEW}
UNIT_SCALES = {'分钟': 60_000_000, '小时': 3_600_000_000, '秒': 1_000_000}


def requested(question):
    return bool(re.search(r'(?:从|自)\s*(?:受理|登记)[^。！？?\n]{0,24}(?:到|至)[^。！？?\n]{0,12}(?:检查|检视)', question)
                and re.search(r'隔多久|多久|多长|间隔|相差|耗时|多少\s*(?:分钟|小时|秒)', question)
                and REVIEW.search(question))


def derive(question, text, origin, *, include_review_duration=False):
    stamps_by_role = {role: [] for role in ROLE_PATTERNS}
    offset = origin
    for line in text.splitlines(keepends=True):
        stamps = list(STAMP.finditer(line))
        if stamps:
            label = line[:stamps[0].start()]
            labeled = [role for role, pattern in ROLE_PATTERNS.items() if pattern.search(label)]
            if len(labeled) == 1:
                role, = labeled
                if len(stamps) == (2 if role == 'review' else 1):
                    stamps_by_role[role].append([
                        {'value': match[0], 'characterStart': offset + match.start(),
                         'characterEnd': offset + match.end()}
                        for match in stamps
                    ])
        offset += len(line)
    if any(len(values) != 1 for values in stamps_by_role.values()):
        return None
    intake, = stamps_by_role['intake'][0]
    inspection, = stamps_by_role['inspection'][0]
    review_start, review_end = stamps_by_role['review'][0]
    try:
        elapsed = elapsed_microseconds(intake['value'], inspection['value'])
        elapsed_microseconds(inspection['value'], review_start['value'])
        review_elapsed = elapsed_microseconds(review_start['value'], review_end['value'])
    except ValueError:
        return None
    units = set(re.findall(r'多少\s*(分钟|小时|秒)', question))
    if len(units) > 1:
        return None
    unit = next(iter(units), '分钟' if elapsed % UNIT_SCALES['分钟'] == 0 else '秒')
    exact = str(Fraction(elapsed, UNIT_SCALES[unit]))
    proof = {'version': VERSION, 'intake': intake, 'inspectionStart': inspection,
             'reviewStart': review_start, 'reviewEnd': review_end,
             'elapsedMicroseconds': elapsed, 'exactValue': exact, 'unit': unit,
             'displayValue': f'{exact} {unit}'}
    if include_review_duration:
        review_units = [name for name, scale in UNIT_SCALES.items() if review_elapsed % scale == 0] or ['秒']
        proof['reviewDuration'] = {
            'elapsedMicroseconds': review_elapsed,
            'exactRepresentations': [
                f'{Fraction(review_elapsed, UNIT_SCALES[name])} {name}' for name in review_units
            ],
        }
    return proof
