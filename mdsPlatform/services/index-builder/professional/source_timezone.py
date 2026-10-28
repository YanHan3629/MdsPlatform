"""Bounded worksheet time declarations, with exact original cell spans."""
import re
from zoneinfo import ZoneInfo,ZoneInfoNotFoundError

VERSION='worksheet-source-timezone-2026-10-24.3'
DECLARATION=re.compile(r'(?:日期(?:时间)?(?:均|都)?(?:为|采用|使用)|(?:时区|timezone|time\s+zone)\s*[:：=为])\s*([A-Za-z][A-Za-z0-9_./+:-]*)',re.I)
UNSEPARATED=re.compile(r'(?:时区|\btimezone|\btime\s+zone)\s+((?:[A-Za-z][A-Za-z0-9_.+-]*/)+[A-Za-z0-9_.+-]+|UTC|GMT)(?=\s*(?:本地时间|时间|。|[.;，,]|$))',re.I)
MARKER=re.compile(r'日期(?:时间)?(?:均|都)?(?:为|采用|使用)[^。；;\n]{0,80}(?:本地时间|时区)|(?:时区|timezone|time\s+zone)\s*[:：=为]',re.I)
BARE=re.compile(r'^\s*((?:[A-Za-z][A-Za-z0-9_.+-]*/)+[A-Za-z0-9_.+-]+|UTC|GMT)(?=\s*(?:本地时间|时间|。|[.;，,]|$))')

def worksheet_policy(sheet,header_row):
    declarations=[];unparsed=[]
    # Only the native pre-header cells can declare the whole table timezone.
    # Data-row notes and formulas cannot silently redefine another record.
    for row in sheet.iter_rows(min_row=1,max_row=max(0,header_row-1)) if header_row>1 else []:
        for cell in row:
            text=cell.value
            if cell.data_type=='f' or not isinstance(text,str):continue
            matches=list(DECLARATION.finditer(text))
            matches.extend(UNSEPARATED.finditer(text))
            bare=BARE.search(text)
            if bare and not any(match.start()<=bare.start(1)<match.end() for match in matches):matches.append(bare)
            if MARKER.search(text) and not matches:
                unparsed.append({'sheet':sheet.title,'cell':cell.coordinate,'sourceText':text})
            for match in matches:
                name=match[1].rstrip('.:')
                # A generic "date is ..." note is not itself a timezone
                # declaration unless it names a zone or local-time role.
                if not re.search(r'时区|timezone|time\s+zone',match[0],re.I) and '/' not in name and name not in {'UTC','GMT'} and not re.match(r'\s*(?:本地时间|时区)',text[match.end():]):continue
                try:zone=ZoneInfo(name);valid=True
                except (ZoneInfoNotFoundError,ValueError):zone=None;valid=False
                declarations.append({'timezone':name,'validIanaTimezone':valid,'evidenceNature':'source_declaration',
                    'locator':{'sheet':sheet.title,'cell':cell.coordinate,'characterStart':match.start(1),
                        'characterEnd':match.start(1)+len(name),'characterIndexUnit':'unicode-codepoint'},'sourceText':text})
    names=sorted({row['timezone'] for row in declarations if row['validIanaTimezone']})
    invalid=any(not row['validIanaTimezone'] for row in declarations) or bool(unparsed)
    status='UNSUPPORTED_DECLARATION' if invalid else 'CONFLICTING_DECLARATIONS' if len(names)>1 else 'DECLARED' if names else 'NOT_DECLARED'
    flags={'UNSUPPORTED_DECLARATION':['SOURCE_TIMEZONE_DECLARATION_UNSUPPORTED'],
        'CONFLICTING_DECLARATIONS':['SOURCE_TIMEZONE_DECLARATION_CONFLICT']}.get(status,[])
    return {'version':VERSION,'scope':'worksheet-pre-header','status':status,
        'sourceTimezone':names[0] if status=='DECLARED' else None,'declarations':declarations,
        'unparsedDeclarations':unparsed,'qualityFlags':flags}

def resolve(policy,configured=None):
    """A conflicting explicit mapping cannot erase the source declaration."""
    policy=policy or {};flags=list(policy.get('qualityFlags',[]));status=policy.get('status')
    if configured:
        try:ZoneInfo(configured)
        except (ZoneInfoNotFoundError,ValueError):return None,[*flags,'SOURCE_TIMEZONE_CONFIG_UNSUPPORTED'],False
    if status in {'UNSUPPORTED_DECLARATION','CONFLICTING_DECLARATIONS'}:return None,flags,False
    declared=policy.get('sourceTimezone') if status=='DECLARED' else None
    if declared and configured and configured!=declared:
        return None,[*flags,'SOURCE_TIMEZONE_CONFIG_CONFLICT'],False
    return declared or configured,flags,True
