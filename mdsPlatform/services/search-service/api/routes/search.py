from fastapi import APIRouter, UploadFile, File, Form
from uuid import UUID
from schemas.search_req import TextToImageReq
from schemas.search_resp import TextToImageResp, ImageToTextResp
from schemas.professional import EvidencePack, ProfessionalSearchRequest
from services.text_to_image_service import TextToImageService
from services.image_to_text_service import ImageToTextService
from services.index_registry import registry
from services.professional_retrievers import ProfessionalRetrieverSuite

router = APIRouter()


@router.post("/search/text-to-image", response_model=TextToImageResp)
def text_to_image(req: TextToImageReq):
    service = TextToImageService()
    return service.search(req)


@router.post("/search/image-to-text", response_model=ImageToTextResp)
async def image_to_text(
    datasetId: UUID = Form(...),
    versionId: UUID = Form(...),
    indexVersionId: UUID = Form(...),
    topK: int = Form(5),
    file: UploadFile = File(...)
):
    content = await file.read()
    service = ImageToTextService()
    return service.search(datasetId, versionId, indexVersionId, topK, content)


@router.post("/search/execute", response_model=EvidencePack)
def execute_professional_search(req: ProfessionalSearchRequest):
    """Execute only schema-validated, bounded and read-only retrieval steps."""
    loaded = registry.ensure_loaded(str(req.dataset_id), str(req.version_id), str(req.index_version_id))

    def visual_search(plan):
        if not plan.query_text:
            return []
        return TextToImageService().search_visual(loaded, plan.query_text, plan.top_k)

    suite = ProfessionalRetrieverSuite(
        loaded.manifest.get("indexes", []), loaded.professional_files or {}, visual_search,
        cache=loaded.professional_cache if loaded.professional_cache is not None else {},
    )
    return suite.execute(req.plan, str(req.version_id), str(req.index_version_id))


@router.get("/indexes/{dataset_id}/{version_id}/{index_version_id}/capabilities")
def index_capabilities(dataset_id: UUID, version_id: UUID, index_version_id: UUID):
    loaded = registry.ensure_loaded(str(dataset_id), str(version_id), str(index_version_id))
    return {
        "datasetId": dataset_id,
        "versionId": version_id,
        "indexVersionId": index_version_id,
        "snapshot": {"dataVersion": str(version_id), "indexVersion": str(index_version_id)},
        "capabilities": [{
            "semanticSpace": item.get("semanticSpace"),
            "indexType": item.get("indexType"),
            "status": item.get("status"),
            "coverage": item.get("coverage", {}),
        } for item in loaded.manifest.get("indexes", [])],
    }
