"""Registered source projections for worksheet cells and formula caches.

This adapter reports read values and existing cache declarations. It never
executes a formula or proves the complete business interpretation.
"""
import re
from services.table_evidence_plan import model_context,validate_item

VERSION='worksheet-source-claim-view-2026-10-24.1'

def validate_source(source):
    provenance=source['provenance']
    validate_item(provenance['validatedTableQuestion'],{
        'assetId':source['asset_id'],'dataVersion':source['data_version'],'indexVersion':source['index_version'],
        'nativeBundle':source['evidence_bundle'],'nativeModelContext':source['model_context'],'provenance':provenance})

def view(source,fact):
    bundle=source['evidence_bundle'];target=source['provenance']['tableTarget'];loc=fact['locator']
    result={'version':VERSION,'locator':{key:loc[key] for key in ('sheet','rawRow','cell') if key in loc},
        'selectable':target['kind']=='row' or loc['cell'] in target['cells'],'supportFactIds':[]}
    if target['kind']=='cells' and loc['cell'] in target['cells'] and loc['cell'] not in target['headerCells']:
        column=re.sub(r'\d+$','',loc['cell'])
        header=next(f for f in bundle['facts'] if f['locator']['cell'] in target['headerCells']
            and re.sub(r'\d+$','',f['locator']['cell'])==column)
        result['sourceColumnHeader']={'factId':header['factId'],'cell':header['locator']['cell'],'value':header['value']}
        result['supportFactIds']=[header['factId']]
    if 'FORMULA_CACHE_UNVERIFIED' in fact.get('qualityFlags',[]):
        row=next(row for row in model_context(bundle,target)['facts'] if row['factId']==fact['factId'])
        result['formulaCache']={'sourceExpression':fact['sourceValue'],'cachedValue':fact['value'],
            'cacheSourceRaw':row['cacheSourceRaw'],'cacheStatus':row['cacheStatus'],'formulaExecution':row['formulaExecution'],
            'usableForCalculation':False}
    return result
