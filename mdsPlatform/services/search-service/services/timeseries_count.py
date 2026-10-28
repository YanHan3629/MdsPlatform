"""Dimensionless counts over complete typed rows, with explicit count objects."""
from fractions import Fraction
from fastapi import HTTPException
from services.interval_statistics import TIME_NAMES,clock,exact_result

VERSION='typed-timeseries-count-2026-10-25.1'

def policy(mode,boundary):
    return {'countMode':mode,'duplicatePolicy':'preserve-physical-records' if mode=='records' else 'deduplicate-exact-equivalent-instants-reject-conflicts',
        'intervalBoundary':boundary,'missingPolicy':'count-record-presence' if mode=='records' else 'exclude-null-or-non-numeric',
        'interpolation':'none','outlierPolicy':'preserve'}

def execute(suite,scope,plan,version,index_version):
    from services.professional_retrievers import _evidence,_matches,_nested,MISSING_FIELD
    from services.exact_structured import source_decimal,numeric_match
    structured=suite._prepare('structured')
    if structured is None:raise HTTPException(status_code=409,detail='COUNT_TYPED_SOURCE_INDEX_REQUIRED')
    mode=plan.count_mode or 'numeric_samples';records_mode=mode=='records'
    tables=[table for table in scope.tables.values() if scope.allowed_asset(table['assetId'],plan)
        and (not plan.table_id or table['tableId']==plan.table_id)
        and (not plan.metric or plan.metric in {field['name'] for field in table['fields']})]
    start,end=clock(plan.time_range.start),clock(plan.time_range.end);output=[]
    for table in tables:
        fields={field['name']:field for field in table['fields']};times=[name for name in fields if name.lower() in TIME_NAMES]
        if len(times)!=1:continue
        time_field=times[0];zone=table.get('sourceTimePolicy',{}).get('sourceTimezone')
        rows=[row for row in structured.rows if row.get('unitId')==table['tableId']]
        if len(rows)!=table['rowCount']:raise HTTPException(status_code=409,detail='COUNT_TYPED_SOURCE_TABLE_INCOMPLETE')
        selected=[];samples={};excluded=[];duplicates=0;positions=set();measurement_units=set()
        try:
            for row in rows:
                position=row['locator']['rawRow']
                if position in positions:raise ValueError('COUNT_DUPLICATE_PHYSICAL_ROW')
                positions.add(position);values=row['values'];eligibility=row.get('calculationEligibility',{})
                selectors=[time_field]+(['series_id'] if 'series_id' in fields else [])
                if any(eligibility.get(name) is False for name in selectors):raise ValueError('COUNT_SELECTOR_NOT_CALCULABLE')
                if values.get('series_id',table['assetId'])!=plan.target_entity:continue
                stamp=clock(values[time_field],zone)
                if stamp<start or (stamp>end if plan.time_range.boundary=='[start,end]' else stamp>=end):continue
                accepted=scope.allowed_unit(row,plan,values,position)
                units={name:(values.get(field['unitField']) if field.get('unitField') else field.get('unit')) for name,field in fields.items()}
                for condition in plan.hard_conditions:
                    if eligibility.get(condition.field) is False:raise ValueError('COUNT_FILTER_NOT_CALCULABLE')
                    match=numeric_match({**row,'units':units},condition)
                    if match is None:match=_matches(_nested(values,condition.field,MISSING_FIELD),condition,units.get(condition.field))
                    accepted=accepted and bool(match)
                if not accepted:continue
                selected.append(position)
                if records_mode:continue
                if eligibility.get(plan.metric) is False:raise ValueError('COUNT_METRIC_NOT_CALCULABLE')
                number,original=source_decimal(row,plan.metric)
                if number is None:
                    excluded.append(position);continue
                if not original or not number.is_finite():raise ValueError('COUNT_SOURCE_NUMERIC_LEXEME_REQUIRED')
                if len(number.as_tuple().digits)>4096 or abs(number.as_tuple().exponent)>4096:raise ValueError('COUNT_SOURCE_PRECISION_LIMIT')
                unit=units.get(plan.metric)
                if plan.unit and plan.unit!=unit:raise ValueError('COUNT_MEASUREMENT_UNIT_MISMATCH')
                measurement_units.add(unit);signature=(Fraction(number),unit)
                if stamp in samples:
                    if samples[stamp]!=signature:raise ValueError('COUNT_DUPLICATE_SAMPLE_CONFLICT')
                    duplicates+=1
                else:samples[stamp]=signature
        except (ValueError,ArithmeticError) as error:
            raise HTTPException(status_code=422,detail=str(error)) from error
        count=len(selected) if records_mode else len(samples)
        calculation_policy=policy(mode,plan.time_range.boundary)
        facts={**exact_result(Fraction(count)),'value':count,'operation':'count','metric':plan.metric,'countMode':mode,
            'sampleCount':count,'matchedRowCount':len(selected),'sourceRecordCount':len(rows),
            'excludedNullOrNonNumericCount':len(excluded),'duplicateEquivalentSampleCount':duplicates,
            'countPolicy':'physical-records-in-window' if records_mode else 'unique-calculable-time-samples',
            'completeAuthorizedRange':True,'sourceMeasurementUnit':next(iter(measurement_units)) if len(measurement_units)==1 else None}
        output.append(_evidence(version=version,index_version=index_version,space='timeseries.facts',asset_id=table['assetId'],
            locator={**table['locator'],'tableId':table['tableId']},facts=facts,score=None,score_type='exact_programmatic',
            entity=plan.target_entity,time_range=plan.time_range.model_dump(),units={'value':'record' if records_mode else 'sample'},
            policy=calculation_policy,evidence_type='calculation',source_assets=[{'assetId':table['assetId'],'logicalPath':table['logicalPath']}]))
    return output
