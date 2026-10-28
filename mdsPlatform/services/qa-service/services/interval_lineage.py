"""Independent interval reconstruction from authorized native point witnesses.

This implementation imports no search executor code. Fractions and timedelta
components preserve source decimals, clipping instants and threshold lexemes.
"""
from datetime import datetime
from decimal import Decimal
from fractions import Fraction
from services.calculation_lineage import require

VERSION='source-interval-statistics-2026-10-25.1'
OPERATIONS={'time_weighted_average','duration_above'}

def ratio(value):return {'numerator':str(value.numerator),'denominator':str(value.denominator)}
def seconds(delta):return Fraction((delta.days*86400+delta.seconds)*1000000+delta.microseconds,1000000)

def calculate(points,window,operation,threshold_expression,measurement_unit):
    require(operation in OPERATIONS,'TIMESERIES_INTERVAL_OPERATION_UNSUPPORTED')
    start=datetime.fromisoformat(window['start']);end=datetime.fromisoformat(window['end'])
    require(operation!='duration_above' or isinstance(threshold_expression,str),'TIMESERIES_INTERVAL_THRESHOLD_REQUIRED')
    threshold=Fraction(Decimal(threshold_expression)) if operation=='duration_above' else None
    distinct={};duplicates=0
    for point in points:
        at=datetime.fromisoformat(point['time']);previous=distinct.get(at)
        if previous is not None:
            require(previous['value']==point['value'] and previous['unit']==point['unit'],'TIMESERIES_INTERVAL_DUPLICATE_CONFLICT')
            duplicates+=1
        else:distinct[at]=point
    timeline=sorted(distinct);durations=sorted(seconds(right-left) for left,right in zip(timeline,timeline[1:]))
    typical=Fraction(0)
    if durations:
        whole,remainder=divmod(len(durations)-1,4)
        typical=durations[whole]
        if remainder:typical+=(durations[whole+1]-durations[whole])*Fraction(remainder,4)
    contribution=Fraction(0);covered=Fraction(0);segments=[];excluded=[];support=set()
    for left,right in zip(timeline,timeline[1:]):
        begin=max(left,start);finish=min(right,end)
        if finish<=begin:continue
        a,b=distinct[left],distinct[right];duration=seconds(finish-begin)
        entry={'start':begin.astimezone(left.tzinfo).isoformat(),'end':finish.astimezone(left.tzinfo).isoformat(),
            'sourceRows':[a['rawRow'],b['rawRow']],'durationExactRatio':ratio(duration)}
        reason='NULL_OR_NON_NUMERIC_ENDPOINT' if a['value'] is None or b['value'] is None else (
            'GAP_GREATER_THAN_THREE_SOURCE_Q25_INTERVALS' if typical and seconds(right-left)>typical*3 else None)
        if reason:excluded.append({**entry,'reason':reason});continue
        if operation=='duration_above':area=duration if a['value']>threshold else Fraction(0)
        else:
            # Integrate the linear basis weights directly over the clipped
            # interval, independently of the executor's endpoint trapezoid.
            full=seconds(right-left);offset=seconds(begin-left)
            right_weight=(offset*duration+duration*duration/2)/full
            area=a['value']*(duration-right_weight)+b['value']*right_weight
        contribution+=area;covered+=duration;support.update(entry['sourceRows'])
        segments.append({**entry,'contributionExactRatio':ratio(area)})
    selected=[point for at,point in distinct.items() if start<=at and (at<=end if window['boundary']=='[start,end]' else at<end) and point['value'] is not None]
    summary={'version':VERSION,'method':'linear-trapezoidal-between-observations' if operation=='time_weighted_average' else 'previous-observation-held-until-next',
        'calculationInterpolation':'linear' if operation=='time_weighted_average' else 'previous-observation-hold',
        'sourceInterpolationPolicy':'none','estimatedBetweenObservations':True,'extrapolation':'none',
        'missingPolicy':'exclude-intervals-touching-null-or-filtered-points; never bridge',
        'gapPolicy':'exclude intervals strictly longer than 3 times source-series Q25 positive spacing',
        'typicalIntervalSecondsExactRatio':ratio(typical),'requestedDurationSecondsExactRatio':ratio(seconds(end-start)),
        'includedDurationSecondsExactRatio':ratio(covered),'uncoveredDurationSecondsExactRatio':ratio(seconds(end-start)-covered),
        'includedIntervalCount':len(segments),'excludedIntervalCount':len(excluded),'sourceRecordCount':len(points),
        'uniqueSourceTimeCount':len(timeline),'duplicateEquivalentSampleCount':duplicates,'sampleCount':len(selected),
        'supportPointCount':len(support),'continuousMeasurementVerified':False,
        'thresholdExpression':threshold_expression if threshold is not None else None,'thresholdExactRatio':ratio(threshold) if threshold is not None else None,
        'thresholdComparison':'>' if threshold is not None else None,'sourceMeasurementUnit':measurement_unit}
    require(covered,'TIMESERIES_INTERVAL_NO_SUPPORTED_INTERVALS')
    value=contribution/covered if operation=='time_weighted_average' else contribution
    return value,{**summary,'segments':segments,'excludedIntervals':excluded}
