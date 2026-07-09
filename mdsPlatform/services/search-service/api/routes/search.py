from fastapi import APIRouter, UploadFile, File, Form
from uuid import UUID
from schemas.search_req import TextToImageReq
from schemas.search_resp import TextToImageResp, ImageToTextResp
from services.text_to_image_service import TextToImageService
from services.image_to_text_service import ImageToTextService

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
