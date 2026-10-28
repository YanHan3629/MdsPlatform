"""Lossless, bounded exact-result presentation for actual model context."""
import hashlib,json,re
from decimal import Decimal,InvalidOperation

VERSION='exact-calculation-context-2026-10-23.1'

def compact_decimal(expression):
    """Use decimal powers only when shorter, without rounding a single digit."""
    if not isinstance(expression,str) or len(expression)>32768:return expression
    try:value=Decimal(expression)
    except (InvalidOperation,ValueError):return expression
    if not value.is_finite():return expression
    sign,digits,exponent=value.as_tuple()
    if abs(exponent)>16384 or len(digits)>16384:return expression
    coefficient=''.join(map(str,digits));terms=[]
    for match in re.finditer(r'[1-9]+',coefficient):
        power=exponent+len(coefficient)-match.end()
        term=match[0]+(f'e{power}' if power else '')
        terms.append(('-' if sign else '')+term)
        if len(terms)>16:return expression
    candidate=(' + ' if not sign else ' - ').join(term.lstrip('-') for term in terms)
    if sign and candidate:candidate='-'+candidate
    return candidate if candidate and len(candidate)<len(expression) else expression

def calculation_context(item):
    facts=item.get('facts',{})
    if (item.get('evidenceType')!='calculation' or facts.get('arithmetic')!='exact-rational-source-lexemes-v1'
            or facts.get('status') or facts.get('completeFilteredSet') is not True or not facts.get('inputDigest')):return None
    if facts.get('exactValue') is not None:
        expression=compact_decimal(facts['exactValue'])
        result={'type':'number','exactExpression':expression,'expressionGrammar':'decimal-power-sum-v1'}
    elif facts.get('exactRatio'):
        ratio=facts['exactRatio']
        result={'type':'number','exactRatioExpressions':{key:compact_decimal(ratio[key]) for key in ('numerator','denominator')},'expressionGrammar':'decimal-power-sum-v1'}
        if facts.get('decimalRounded'):result.update(decimalApproximation=facts.get('decimalValue'),decimalIsApproximate=True)
    else:return None
    checksum=hashlib.sha256(json.dumps(facts,ensure_ascii=False,sort_keys=True,separators=(',',':')).encode()).hexdigest()
    context={'version':VERSION,'evidenceId':item['evidenceId'],'evidenceNature':'program_calculation','originalFormat':item.get('provenance',{}).get('originalFormat'),
        'logicalPath':(item.get('preview') or {}).get('logicalPath'),'locator':item.get('locator',{}),'operation':facts['operation'],'field':facts.get('field'),
        'result':result,'units':item.get('units',{}),'timeRange':item.get('timeRange'),'entityId':item.get('entityId'),
        'scope':{key:facts.get(key) for key in ('matchedRowCount','numericInputCount','excludedNullOrNonNumericCount','completeFilteredSet','inputDigest')},
        'countPolicy':facts.get('countPolicy','all-matched-rows') if facts['operation']=='count' else 'source-number-types-only',
        'calculationPolicy':item.get('calculationPolicy',{}),'arithmetic':facts['arithmetic'],'fullFactsSha256':checksum}
    if item.get('calculationBundle'):
        lineage=item['calculationBundle']
        context.update(resultFactId=lineage['resultFactId'],scopeFactId=lineage['scopeFactId'],
            lineageVersion=lineage['version'],nativeInputsSha256=lineage['inputsSha256'])
        if lineage['version'].startswith('native-timeseries-calculation-'):
            context['scope'].update({key:facts.get(key) for key in ('sampleCount','scannedNativeRowCount','duplicateEquivalentSampleCount')})
            context['scope']['verification']='independent-full-native-table-window-calculation'
            context['scope']['sourceTimePolicy']={key:lineage['sourceTimePolicy'].get(key) for key in ('status','sourceTimezone')}
            for key in ('requestedTimeRange','actualSampleTimeRange','sourceRecordTimeRange','sourceSeriesRecordCount',
                        'windowRecordCount','continuousCoverageVerified','observationSemantics',
                        'requestedBoundsOutsideSourceExtent','timeDisplayPolicy','sourceDeclarations'):
                if key in lineage['coverage']:context['scope'][key]=lineage['coverage'][key]
            if lineage['coverage'].get('intervalCalculation'):
                interval=lineage['coverage']['intervalCalculation']
                context['version']='typed-interval-context-2026-10-25.1'
                context['resultUnits']=context.pop('units')
                context['measurementUnit']=interval['sourceMeasurementUnit']
                context['scope']['sourceTimeDeclarations']=context['scope'].pop('sourceDeclarations')
                # The request window remains complete in scope. UI display
                # transforms stay in the lineage; the model receives UTC
                # source/sample spans and the full original zone declaration.
                context.pop('timeRange',None)
                context['scope'].pop('timeDisplayPolicy',None)
                context['calculationPolicy']={key:interval[key] for key in ('version','method','calculationInterpolation',
                    'sourceInterpolationPolicy','extrapolation','continuousMeasurementVerified','supportPointCount',
                    'includedDurationSecondsExactRatio','uncoveredDurationSecondsExactRatio','excludedIntervalCount',
                    'thresholdExpression','thresholdExactRatio','thresholdComparison','sourceMeasurementUnit')}
            if facts.get('countMode'):
                context['version']='typed-timeseries-count-context-2026-10-25.1'
                context['resultUnits']=context.pop('units')
                context['countMode']=facts['countMode']
                context['measurementUnit']=facts.get('sourceMeasurementUnit')
                context['scope']['metricValuesRead']=lineage['coverage']['metricValuesRead']
                context['scope']['sourceTimeDeclarations']=context['scope'].pop('sourceDeclarations')
                context.pop('timeRange',None)
                context['scope'].pop('timeDisplayPolicy',None)
    if facts['operation']=='count':
        if facts.get('countMode')!='numeric_samples':
            context['scope'].pop('numericInputCount',None)
            context['scope'].pop('excludedNullOrNonNumericCount',None)
    return context
