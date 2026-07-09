from fastapi import APIRouter
from pydantic import BaseModel
from uuid import UUID
from services.index_registry import registry

router = APIRouter()


class PreloadReq(BaseModel):
    datasetId: UUID
    versionId: UUID
    indexVersionId: UUID


@router.post("/indexes/preload")
def preload(req: PreloadReq):
    key = registry.build_key(str(req.datasetId), str(req.versionId), str(req.indexVersionId))
    registry.ensure_loaded(str(req.datasetId), str(req.versionId), str(req.indexVersionId))
    return {"success": True, "cacheKey": key}
