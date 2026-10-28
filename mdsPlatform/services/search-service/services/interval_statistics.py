"""Exact, window-clipped interval estimates over all typed source records.

Interpolation describes a calculation assumption, not source measurements.
Nulls and filtered records remain timeline barriers. No extrapolation occurs.
"""
import hashlib
import json
import math
import re
from datetime import datetime,timezone
from decimal import Decimal,Inexact,localcontext
from fractions import Fraction
from zoneinfo import ZoneInfo

VERSION='source-interval-statistics-2026-10-25.1'
OPERATIONS={'time_weighted_average','duration_above'}
TIME_NAMES={'timestamp','datetime','time','date','recorded_at','时间','日期','时间戳','采样时间'}
EPOCH=datetime(1970,1,1,tzinfo=timezone.utc)

def rational(value):
    return {'numerator':str(value.numerator),'denominator':str(value.denominator)}

def exact_result(value):
    if value is None:return {'value':None,'status':'NO_SUPPORTED_INTERVALS'}
    with localcontext() as context:
        context.prec=4096;decimal=Decimal(value.numerator)/Decimal(value.denominator);rounded=bool(context.flags[Inexact])
        if rounded:
            context.prec=50;decimal=Decimal(value.numerator)/Decimal(value.denominator)
        number=float(value)
        return {'value':number if math.isfinite(number) else None,'exactRatio':rational(value),
            'exactValue':str(decimal) if not rounded else None,'decimalValue':str(decimal),'decimalRounded':rounded,
            'decimalPrecision':context.prec,'arithmetic':'exact-rational-source-lexemes-v1'}

def clock(value,zone=None):
    if not isinstance(value,str) or (match:=re.search(r'[T ]\d\d:\d\d:\d\d\.(\d+)',value)) and match[1][6:].strip('0'):
        raise ValueError('INTERVAL_SOURCE_TIME_PRECISION_UNSUPPORTED')
    stamp=datetime.fromisoformat(value.replace('Z','+00:00'))
    if stamp.tzinfo is None:
        if not zone:raise ValueError('INTERVAL_SOURCE_TIMEZONE_UNAVAILABLE')
        tz=ZoneInfo(zone);valid=[]
        for fold in (0,1):
            candidate=stamp.replace(tzinfo=tz,fold=fold)
            if candidate.astimezone(timezone.utc).astimezone(tz).replace(tzinfo=None)==stamp:valid.append(candidate)
        if not valid or len({candidate.utcoffset() for candidate in valid})!=1:raise ValueError('INTERVAL_SOURCE_LOCAL_TIME_AMBIGUOUS')
        stamp=valid[0]
    delta=stamp.astimezone(timezone.utc)-EPOCH
    return (delta.days*86400+delta.seconds)*1000000+delta.microseconds

def timestamp(microseconds):
    from datetime import timedelta
    return (EPOCH+timedelta(microseconds=microseconds)).isoformat()

def calculate(points,window,operation,threshold=None):
    """Points carry integer UTC microseconds, exact numbers and source row IDs."""
    if operation not in OPERATIONS:raise ValueError('INTERVAL_OPERATION_UNSUPPORTED')
    start,end=clock(window['start']),clock(window['end'])
    if start>=end or window.get('boundary') not in {'[start,end]','[start,end)'}:raise ValueError('INTERVAL_WINDOW_INVALID')
    if operation=='duration_above' and not isinstance(threshold,Fraction):raise ValueError('INTERVAL_THRESHOLD_REQUIRED')
    ordered={};duplicates=0
    for point in points:
        previous=ordered.get(point['time'])
        if previous is not None:
            if any(previous.get(key)!=point.get(key) for key in ('number','unit','selected','trusted')):
                raise ValueError('INTERVAL_DUPLICATE_CONFLICT')
            duplicates+=1
        else:ordered[point['time']]=point
    ordered=[ordered[key] for key in sorted(ordered)]
    deltas=sorted(right['time']-left['time'] for left,right in zip(ordered,ordered[1:]))
    quartile=Fraction(0)
    if deltas:
        position=Fraction(len(deltas)-1,4);lower=position.numerator//position.denominator
        quartile=Fraction(deltas[lower])+(position-lower)*(deltas[min(lower+1,len(deltas)-1)]-deltas[lower])
    segments=[];excluded=[];area=Fraction(0);covered=Fraction(0);support=set()
    for left,right in zip(ordered,ordered[1:]):
        begin,finish=max(start,left['time']),min(end,right['time'])
        if finish<=begin:continue
        duration=Fraction(finish-begin,1000000)
        segment={'start':timestamp(begin),'end':timestamp(finish),'sourceRows':[left['rawRow'],right['rawRow']],
            'durationExactRatio':rational(duration)}
        if not left.get('trusted',True) or not right.get('trusted',True):raise ValueError('INTERVAL_SOURCE_CELL_NOT_CALCULABLE')
        reason='FILTERED_ENDPOINT' if not left.get('selected',True) or not right.get('selected',True) else (
            'NULL_OR_NON_NUMERIC_ENDPOINT' if left['number'] is None or right['number'] is None else (
            'GAP_GREATER_THAN_THREE_SOURCE_Q25_INTERVALS' if quartile and right['time']-left['time']>3*quartile else None))
        if reason:
            excluded.append({**segment,'reason':reason});continue
        if operation=='time_weighted_average':
            slope=(right['number']-left['number'])/Fraction(right['time']-left['time'])
            first=left['number']+slope*(begin-left['time']);last=left['number']+slope*(finish-left['time'])
            contribution=(first+last)*duration/2
        else:contribution=duration if left['number']>threshold else Fraction(0)
        segments.append({**segment,'contributionExactRatio':rational(contribution)})
        area+=contribution;covered+=duration;support.update(segment['sourceRows'])
    requested=Fraction(end-start,1000000)
    in_window=lambda point:start<=point['time'] and (point['time']<=end if window['boundary']=='[start,end]' else point['time']<end)
    valid=[point for point in ordered if in_window(point) and point.get('selected',True) and point['number'] is not None]
    result=(area/covered if operation=='time_weighted_average' else area) if covered else None
    summary={'version':VERSION,'method':'linear-trapezoidal-between-observations' if operation=='time_weighted_average' else 'previous-observation-held-until-next',
        'calculationInterpolation':'linear' if operation=='time_weighted_average' else 'previous-observation-hold',
        'sourceInterpolationPolicy':'none','estimatedBetweenObservations':True,'extrapolation':'none',
        'missingPolicy':'exclude-intervals-touching-null-or-filtered-points; never bridge',
        'gapPolicy':'exclude intervals strictly longer than 3 times source-series Q25 positive spacing',
        'typicalIntervalSecondsExactRatio':rational(quartile/1000000),'requestedDurationSecondsExactRatio':rational(requested),
        'includedDurationSecondsExactRatio':rational(covered),'uncoveredDurationSecondsExactRatio':rational(requested-covered),
        'includedIntervalCount':len(segments),'excludedIntervalCount':len(excluded),'sourceRecordCount':len(points),
        'uniqueSourceTimeCount':len(ordered),'duplicateEquivalentSampleCount':duplicates,'sampleCount':len(valid),
        'supportPointCount':len(support),'continuousMeasurementVerified':False,
        'thresholdExpression':str(threshold.numerator) if threshold is not None and threshold.denominator==1 else None,
        'thresholdExactRatio':rational(threshold) if threshold is not None else None,'thresholdComparison':'>' if threshold is not None else None}
    return {**exact_result(result),'sampleCount':len(valid),'intervalCalculation':{**summary,'segments':segments,'excludedIntervals':excluded}}

def execute(suite,scope,plan,version,index_version):
    """Use complete typed tables, including null rows absent from old series facts."""
    from fastapi import HTTPException
    from services.professional_retrievers import _evidence,_matches,_nested,MISSING_FIELD
    from services.exact_structured import source_decimal,numeric_match
    if plan.operation not in OPERATIONS:return None
    structured=suite._prepare('structured')
    if structured is None:raise HTTPException(status_code=409,detail='INTERVAL_TYPED_SOURCE_INDEX_REQUIRED')
    tables=[table for table in scope.tables.values() if scope.allowed_asset(table['assetId'],plan)
        and plan.metric in {field['name'] for field in table['fields']}]
    threshold=None
    if plan.operation=='duration_above':
        expression=getattr(plan,'threshold_expression',None)
        threshold=Fraction(Decimal(expression if expression is not None else str(plan.threshold)))
    output=[]
    for table in tables:
        fields={field['name']:field for field in table['fields']};times=[field for field in fields if field.lower() in TIME_NAMES]
        if len(times)!=1:continue
        time_field=times[0];zone=table.get('sourceTimePolicy',{}).get('sourceTimezone')
        rows=[row for row in structured.rows if row.get('unitId')==table['tableId']]
        if len(rows)!=table['rowCount']:raise HTTPException(status_code=409,detail='INTERVAL_TYPED_SOURCE_TABLE_INCOMPLETE')
        points=[];units=set()
        try:
            for row in rows:
                values=row['values'];entity=values.get('series_id',table['assetId'])
                if entity!=plan.target_entity:continue
                eligibility=row.get('calculationEligibility',{})
                if any(eligibility.get(field) is False for field in (time_field,'series_id')):raise ValueError('INTERVAL_SELECTOR_NOT_CALCULABLE')
                numeric,original=source_decimal(row,plan.metric)
                if numeric is not None and (not original or not numeric.is_finite()):raise ValueError('INTERVAL_SOURCE_NUMERIC_LEXEME_REQUIRED')
                if numeric is not None and (len(numeric.as_tuple().digits)>4096 or abs(numeric.as_tuple().exponent)>4096):raise ValueError('INTERVAL_SOURCE_NUMERIC_PRECISION_LIMIT')
                field=fields[plan.metric];unit=values.get(field['unitField']) if field.get('unitField') else field.get('unit')
                if numeric is not None:
                    if not unit or (plan.unit and unit!=plan.unit):raise ValueError('INTERVAL_UNIT_MAPPING_REQUIRED')
                    units.add(unit)
                selected=scope.allowed_unit(row,plan,values,row.get('locator',{}).get('rawRow'))
                for condition in plan.hard_conditions:
                    if eligibility.get(condition.field) is False:raise ValueError('INTERVAL_FILTER_CELL_NOT_CALCULABLE')
                    source_units={name:(values.get(field['unitField']) if field.get('unitField') else field.get('unit')) for name,field in fields.items()}
                    match=numeric_match({**row,'units':source_units},condition)
                    if match is None:match=_matches(_nested(values,condition.field,MISSING_FIELD),condition,source_units.get(condition.field))
                    selected=selected and bool(match)
                points.append({'time':clock(values[time_field],zone),'number':Fraction(numeric) if numeric is not None else None,
                    'rawRow':row['locator']['rawRow'],'unit':unit,'selected':selected,'trusted':eligibility.get(plan.metric,True)})
            if not points:continue
            if len(units)>1:raise ValueError('INTERVAL_UNIT_MAPPING_REQUIRED')
            facts=calculate(points,plan.time_range.model_dump(),plan.operation,threshold)
        except (ValueError,ArithmeticError) as error:
            raise HTTPException(status_code=422,detail=str(error)) from error
        facts.update(operation=plan.operation,metric=plan.metric,completeAuthorizedRange=True)
        policy=facts['intervalCalculation'];unit=next(iter(units),plan.unit)
        facts['intervalCalculation']['sourceMeasurementUnit']=unit
        facts['intervalCalculation']['thresholdExpression']=(getattr(plan,'threshold_expression',None) or str(plan.threshold)) if plan.operation=='duration_above' else None
        result_unit='s' if plan.operation=='duration_above' else unit
        output.append(_evidence(version=version,index_version=index_version,space='timeseries.facts',asset_id=table['assetId'],
            locator={**table['locator'],'tableId':table['tableId']},facts=facts,score=None,score_type='exact_programmatic',entity=plan.target_entity,
            time_range=plan.time_range.model_dump(),units={'value':result_unit},policy={key:value for key,value in policy.items() if key not in {'segments','excludedIntervals'}},
            flags=['INTERVAL_ESTIMATE_NOT_CONTINUOUS_MEASUREMENT'],evidence_type='calculation',
            source_assets=[{'assetId':table['assetId'],'logicalPath':table['logicalPath']}]))
    return output
