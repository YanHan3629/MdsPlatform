"""Local integrity receipt, captured immediately after an authorized read.

This is client audit evidence, not a server signature or a new source fact.
The wire digest and decoded digest serve different purposes; neither replaces
the frozen original-file identity or subsequent authorization checks.
"""
import copy,hashlib,json,re
VERSION='authorized-read-client-receipt-2026-10-24.2'
SHA=re.compile(r'^[0-9a-f]{64}$')
def canonical(value):return json.dumps(value,ensure_ascii=False,sort_keys=True,separators=(',',':'),allow_nan=False).encode()
def digest(value):return hashlib.sha256(canonical(value)).hexdigest()
def decoded_body(bundle):return {key:value for key,value in bundle.items() if key not in {'responseBodySha256','clientReadReceipt'}}
def attest(bundle,raw,scope,asset_id,operation):
    result=copy.deepcopy(bundle);body_sha=hashlib.sha256(raw).hexdigest()
    if digest(json.loads(raw))!=digest(decoded_body(result)):raise ValueError('READ_RECEIPT_WIRE_DECODE_MISMATCH')
    receipt={'version':VERSION,'responseBodySha256':body_sha,'decodedBodySha256':digest(decoded_body(result)),
        'requestSha256':digest({'scope':list(map(str,scope)),'assetId':str(asset_id),'operation':operation}),
        'scope':dict(zip(('datasetId','versionId','indexVersionId'),map(str,scope))),
        'operation':copy.deepcopy(operation),
        'assetId':str(asset_id),'readId':result.get('readId'),'readOutputSha256':result.get('readOutputSha256')}
    result['responseBodySha256']=body_sha;result['clientReadReceipt']=receipt
    from services.evidence_trace import current
    if current():current().event('nativeReadReceipt',receipt)
    return result
def verify(bundle):
    receipt=bundle.get('clientReadReceipt',{})
    if receipt.get('version')!=VERSION:raise ValueError('READ_RECEIPT_UNAVAILABLE')
    if not all(isinstance(receipt.get(key),str) and SHA.fullmatch(receipt[key]) for key in ('responseBodySha256','decodedBodySha256','requestSha256')):
        raise ValueError('READ_RECEIPT_HASH_INVALID')
    if receipt['decodedBodySha256']!=digest(decoded_body(bundle)) or receipt['responseBodySha256']!=bundle.get('responseBodySha256'):
        raise ValueError('READ_RECEIPT_BODY_CHANGED')
    identity=bundle.get('identity',{})
    if (set(receipt.get('scope',{}))!={'datasetId','versionId','indexVersionId'}
            or receipt['requestSha256']!=digest({'scope':[receipt['scope'][key] for key in ('datasetId','versionId','indexVersionId')],
                'assetId':receipt.get('assetId'),'operation':receipt.get('operation')})):
        raise ValueError('READ_RECEIPT_REQUEST_CHANGED')
    if (receipt.get('readId')!=bundle.get('readId') or receipt.get('readOutputSha256')!=bundle.get('readOutputSha256')
            or receipt.get('assetId')!=identity.get('assetId') or any(identity.get(key)!=value for key,value in receipt['scope'].items())):
        raise ValueError('READ_RECEIPT_SCOPE_CHANGED')
    return copy.deepcopy(receipt)

def mark_derivation(parent,child,recipe,parameters):
    """A client-created child must not inherit a wire receipt for its parent."""
    native=parent.get('clientReadReceipt');ancestors=copy.deepcopy(parent.get('clientReadParents',[]))
    if not native and not ancestors:return
    receipt=verify(parent) if native else None
    require_chain=parent.get('clientDerivationChain',[])
    if len(require_chain)>=8:raise ValueError('READ_DERIVATION_CHAIN_LIMIT')
    if not native and not parent.get('clientDerivation'):raise ValueError('READ_DERIVATION_PARENT_UNAVAILABLE')
    child.pop('clientReadReceipt',None);child.pop('responseBodySha256',None)
    child['clientReadParents']=([copy.deepcopy(receipt)] if receipt else [])+ancestors
    child['clientDerivationChain']=[*copy.deepcopy(require_chain),*([copy.deepcopy(parent['clientDerivation'])] if parent.get('clientDerivation') else [])]
    child['clientDerivation']={'recipeVersion':recipe,'parametersSha256':digest(parameters),
        'inputReadId':parent['readId'],'inputEvidenceId':parent['evidenceId'],'inputRepresentationId':parent['representationId'],
        'inputBodyKind':'native-wire' if receipt else 'client-derived',
        'inputDecodedBodySha256':receipt['decodedBodySha256'] if receipt else digest(decoded_body(parent)),
        'inputClientReadReceiptSha256':digest(receipt) if receipt else None,'scope':'Client transformation, not a new gateway response'}
