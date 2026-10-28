"""One registered equality across every matching same-key source (three/four).

Pair checks reuse the binary typed-value and exact declared-unit contracts.
String numbers remain strings. This operation does not establish physical truth.
"""
import uuid
from services.answer_lineage import require,canonical

VERSION='native-record-group-equality-2026-10-26.1'
OPERATION='record_group_value_equal'

def registered(nodes):
    from services.record_claim_view import comparison_pairs
    ordered=sorted(nodes,key=lambda node:node['recordView']['sourcePath'])
    if not 3<=len(ordered)<=4:return None
    paths=[node['recordView']['sourcePath'] for node in ordered]
    if len(set(paths))!=len(paths) or len({node['identity']['assetId'] for node in ordered})!=len(ordered):return None
    if any(sorted(node['recordView']['requiredSourcePaths'])!=paths for node in ordered):return None
    if len({node['field'] for node in ordered})!=1:return None
    if any(not comparison_pairs([ordered[0],node]) for node in ordered[1:]):return None
    return {'operation':OPERATION,'inputFactIds':[node['factId'] for node in ordered]}

def calculation(nodes):
    from services.record_claim_view import comparison_pairs,equal,identifier_comparison_scope
    operation=registered(nodes);require(operation is not None,'CLAIM_RECORD_GROUP_NOT_REGISTERED')
    by_id={node['factId']:node for node in nodes};ordered=[by_id[identifier] for identifier in operation['inputFactIds']]
    first=ordered[0];checks=[]
    for node in ordered[1:]:
        pair,=comparison_pairs([first,node])
        if pair['operation']=='record_unit_value_equal':
            from services.record_unit_conversion import calculation as unit_calculation
            result=unit_calculation(first,node)
            checks.append({'operation':pair['operation'],'result':result['result'],'unitConversion':result['unitConversion']})
        else:checks.append({'operation':pair['operation'],'result':equal(first,node),**identifier_comparison_scope(first,node)})
    payload=[VERSION,operation,first['recordView']['selector'],[node['unit'] for node in ordered],checks]
    return {'calculationId':str(uuid.uuid5(uuid.NAMESPACE_URL,canonical(payload))),'recipeVersion':VERSION,
        'evidenceNature':'program_calculation',**operation,'field':first['field'],'recordKey':first['recordView']['selector'],
        'inputTypes':[node['sourceType'] for node in ordered],'inputUnits':[node['unit'] for node in ordered],
        'method':'Each subsequent source compared against the first; exact registered typed-value/unit rules.',
        'pairChecks':checks,'result':all(check['result'] for check in checks)}
