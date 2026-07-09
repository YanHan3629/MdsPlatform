from retriever.faiss_retriever import FaissRetriever
from schemas.search_resp import TextToImageResp
from services.index_registry import registry
from services.query_encoder import QueryEncoder
from services.result_assembler import ResultAssembler


class TextToImageService:
    def __init__(self):
        self.encoder = QueryEncoder()

    def search(self, req):
        loaded = registry.ensure_loaded(str(req.datasetId), str(req.versionId), str(req.indexVersionId))
        vector = self.encoder.encode_text(req.query)
        scores, indices = FaissRetriever.search(loaded.image_index, vector, req.topK)
        items = ResultAssembler.assemble_text_to_image(scores, indices, loaded.image_metadata)
        return TextToImageResp(items=items)
