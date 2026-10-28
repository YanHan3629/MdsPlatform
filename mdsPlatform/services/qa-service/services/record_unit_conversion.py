"""Exact, case-sensitive declared-unit conversions; never infer a missing unit."""
from decimal import Decimal,InvalidOperation
from fractions import Fraction
import uuid
from services.answer_lineage import require,canonical

VERSION='native-record-exact-unit-equality-2026-10-26.1'
OPERATION='record_unit_value_equal'
# Each entry maps a literal source declaration to a canonical unit. Unknown
# units, currencies, count categories and row-dependent declarations stay closed.
UNITS={}
def _register(dimension,base,scales,offsets=None):
    for name,scale in scales.items():
        UNITS[name]=(dimension,base,Fraction(scale),Fraction((offsets or {}).get(name,'0')))
_register('power','W',{'mW':'0.001','W':'1','kW':'1000','MW':'1000000'})
_register('length','m',{'µm':'0.000001','um':'0.000001','mm':'0.001','cm':'0.01','m':'1','km':'1000'})
_register('area','m²',{'mm²':'0.000001','mm2':'0.000001','cm²':'0.0001','cm2':'0.0001','m²':'1','m2':'1'})
_register('volume','L',{'mL':'0.001','L':'1','mm³':'0.000001','mm3':'0.000001','cm³':'0.001','cm3':'0.001','m³':'1000','m3':'1000'})
_register('mass','kg',{'mg':'0.000001','g':'0.001','kg':'1','t':'1000'})
_register('duration','s',{'ms':'0.001','s':'1','min':'60','h':'3600'})
_register('temperature','K',{'K':'1','°C':'1','℃':'1'},{'°C':'273.15','℃':'273.15'})
_register('current','A',{'mA':'0.001','A':'1'})
_register('linear-velocity','m/s',{'mm/s':'0.001','m/s':'1'})
_register('rotational-frequency','rpm',{'rpm':'1','r/min':'1'})

def compatible(left,right):
    if not all(node.get('sourceType')=='number' and node.get('calculationEligible') for node in (left,right)):return False
    units=[UNITS.get(node.get('unit')) for node in (left,right)]
    return all(units) and units[0][:2]==units[1][:2]

def _number(node):
    try:value=Decimal(node['value']['sourceNumber'])
    except (InvalidOperation,KeyError,TypeError):raise ValueError('CLAIM_RECORD_NUMBER_INVALID')
    require(value.is_finite() and len(value.as_tuple().digits)<=4096 and abs(value.as_tuple().exponent)<=4096,'CLAIM_RECORD_NUMBER_BUDGET')
    return Fraction(value)

def _ratio(value):
    # Bound integer serialization as well as decimal input; do not change the
    # interpreter's integer-string guard or round a large exact result.
    require(abs(value.numerator)<10**4096 and value.denominator<10**4096,'CLAIM_RECORD_UNIT_CONVERSION_BUDGET')
    return [str(value.numerator),str(value.denominator)]

def calculation(left,right):
    from services.record_claim_view import comparison_pairs
    pair={'operation':OPERATION,'leftFactId':left['factId'],'rightFactId':right['factId']}
    require(pair in comparison_pairs([left,right]),'CLAIM_RECORD_COMPARISON_NOT_REGISTERED')
    require(compatible(left,right) and left['unit']!=right['unit'],'CLAIM_RECORD_UNIT_CONVERSION_UNAVAILABLE')
    units=[UNITS[node['unit']] for node in (left,right)]
    normalized=[_number(node)*unit[2]+unit[3] for node,unit in zip((left,right),units)]
    conversion={'canonicalUnit':units[0][1],'inputUnits':[left['unit'],right['unit']],
        'scales':[_ratio(unit[2]) for unit in units],'offsets':[_ratio(unit[3]) for unit in units],
        'normalizedValues':[_ratio(value) for value in normalized]}
    identifier=str(uuid.uuid5(uuid.NAMESPACE_URL,canonical([VERSION,OPERATION,left['factId'],right['factId'],left['recordView']['selector'],conversion])))
    return {'calculationId':identifier,'recipeVersion':VERSION,'evidenceNature':'program_calculation','operation':OPERATION,
        'inputFactIds':[left['factId'],right['factId']],'field':left['field'],'recordKey':left['recordView']['selector'],
        'unitConversion':conversion,'result':normalized[0]==normalized[1]}

def ratio_text(value):
    return value[0] if value[1]=='1' else value[0]+'/'+value[1]
