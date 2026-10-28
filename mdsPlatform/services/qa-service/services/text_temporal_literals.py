"""Conservative literal date/clock membership for scoped original TXT.

No timezone conversion, date arithmetic or temporal-role inference is allowed.
A lower-precision date or clock can be a projection of a source timestamp;
this does not establish what event the model associated with that value.
"""
import re
from datetime import date,time,datetime

VERSION='txt-temporal-literal-membership-2026-10-24.1'
DATE=re.compile(r'(?<![A-Za-z0-9])\d{4}-\d{2}-\d{2}(?:[Tt ]\d{2}:\d{2}(?::\d{2}(?:\.\d+)?)?(?:[Zz]|[+-]\d{2}:\d{2})?)?(?!\d)')
CLOCK=re.compile(r'(?<![A-Za-z0-9:+-])\d{1,2}:\d{2}(?::\d{2}(?:\.\d+)?)?(?!\d)')
CHINESE_DATE=re.compile(r'(?<!\d)(\d{4})年(\d{1,2})月(\d{1,2})日(?!\d)')

def tokens(text):
    """Consume whole ISO timestamps before recognizing standalone clocks."""
    taken=[];out=[]
    for pattern,kind in [(DATE,'date'),(CHINESE_DATE,'chinese-date'),(CLOCK,'clock')]:
        for match in pattern.finditer(text):
            if any(match.start()<end and match.end()>start for start,end in taken):continue
            value=match[0];key=None;projections=set()
            try:
                if kind=='chinese-date':key=('date',date(*map(int,match.groups())).isoformat())
                elif kind=='clock':
                    canonical=value if len(value.split(':')[0])==2 else '0'+value
                    time.fromisoformat(canonical);key=('clock',canonical)
                elif len(value)==10:date.fromisoformat(value);key=('date',value)
                else:
                    canonical=value[:10]+'T'+value[11:]
                    if canonical.endswith(('z','Z')):canonical=canonical[:-1]+'+00:00'
                    datetime.fromisoformat(canonical);key=('datetime',canonical)
                    projections.add(('date',canonical[:10]))
                    clock=re.match(r'\d{2}:\d{2}(?::\d{2}(?:\.\d+)?)?',canonical[11:])[0]
                    projections.add(('clock',clock));projections.add(('clock',clock[:5]))
            except ValueError:pass
            if key and key[0]=='clock' and len(key[1])>5:projections.add(('clock',key[1][:5]))
            taken.append((match.start(),match.end()))
            out.append({'start':match.start(),'end':match.end(),'value':value,'key':key,'projections':projections})
    return sorted(out,key=lambda row:row['start'])

def inventory(text):
    return {key for row in tokens(text) for key in ({row['key']}|row['projections']) if key}

def check(answer,focused,sources,target):
    allowed=inventory(focused);by_source=[]
    for index,source in enumerate(sources,1):
        bundle=source.evidence_bundle;loc=bundle['locator'];start=max(loc['characterStart'],target['characterStart']);end=min(loc['characterEnd'],target['characterEnd'])
        text=bundle['content']['text'][start-loc['characterStart']:end-loc['characterStart']] if end>start else ''
        by_source.append((index,source,inventory(text)))
    checks=[]
    for row in tokens(answer):
        key=row['key'];support=[(index,source) for index,source,values in by_source if key and key in values]
        checks.append({'version':VERSION,'answerStart':row['start'],'answerEnd':row['end'],'literal':row['value'],
            'supported':bool(key and key in allowed and support),'sourceIndices':[index for index,_ in support],
            'factIds':sorted({fact['factId'] for _,source in support for fact in source.evidence_bundle['facts'] if fact['factId'] in source.fact_ids}),
            'scope':'Value membership/projection only; event roles and per-sentence citation support remain unverified.'})
    return checks
