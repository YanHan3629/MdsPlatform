from pydantic import BaseModel, Field
from uuid import UUID


class TextToImageReq(BaseModel):
    datasetId: UUID
    versionId: UUID
    indexVersionId: UUID
    query: str = Field(min_length=1)
    topK: int = Field(default=5, ge=1, le=100)
