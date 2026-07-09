from pydantic import BaseModel
from typing import List
from uuid import UUID


class TextToImageItem(BaseModel):
    assetId: UUID
    score: float
    logicalPath: str
    texts: List[str]


class TextToImageResp(BaseModel):
    items: List[TextToImageItem]


class ImageToTextItem(BaseModel):
    assetId: UUID
    score: float
    text: str
    logicalPath: str


class ImageToTextResp(BaseModel):
    items: List[ImageToTextItem]
