from retriever.faiss_retriever import FaissRetriever
from schemas.search_resp import ImageToTextResp
from services.index_registry import registry
from services.query_encoder import QueryEncoder
from services.result_assembler import ResultAssembler


class ImageToTextService:
    def __init__(self):
        self.encoder = QueryEncoder()

    def search(self, dataset_id, version_id, index_version_id, top_k, content: bytes):
        loaded = registry.ensure_loaded(str(dataset_id), str(version_id), str(index_version_id))
        vector = self.encoder.encode_image_bytes(content)
        if loaded.unified_index is not None and loaded.unified_metadata is not None:
            candidate_count = min(loaded.unified_index.ntotal, max(top_k, top_k * 5))
            scores, indices = FaissRetriever.search(loaded.unified_index, vector, candidate_count)
            items = ResultAssembler.assemble_unified_as_image_to_text(
                scores, indices, loaded.unified_metadata, top_k
            )
            return ImageToTextResp(items=items)
        scores, indices = FaissRetriever.search(loaded.text_index, vector, top_k)
        items = ResultAssembler.assemble_image_to_text(scores, indices, loaded.text_metadata)
        return ImageToTextResp(items=items)
