from retriever.faiss_retriever import FaissRetriever
from schemas.search_resp import TextToImageResp
from services.index_registry import registry
from services.query_encoder import QueryEncoder
from services.result_assembler import ResultAssembler


class TextToImageService:
    def __init__(self):
        self.encoder = QueryEncoder()

    def search_visual(self, loaded, query, top_k):
        """Image representations only; preserve page/frame identity and preview location."""
        import json
        if loaded.image_index is None or loaded.image_index.ntotal == 0:
            return []
        vector = self.encoder.encode_text(query)
        scores, indices = FaissRetriever.search(loaded.image_index, vector, min(top_k, loaded.image_index.ntotal))
        result = []
        for score, index in zip(scores, indices):
            if index < 0:
                continue
            row = loaded.image_metadata.iloc[index]
            locator = row.get("locator", {})
            if isinstance(locator, str):
                locator = json.loads(locator) if locator else {}
            path = row.get("preview_path")
            preview = {"logicalPath": str(row["logical_path"])}
            if isinstance(path, str) and path.startswith("/representations/"):
                preview.update(artifactPath=path, indexRepoId=loaded.bundle.get("indexRepoId"),
                               indexCommitId=loaded.bundle.get("indexCommitId"))
            result.append({"assetId": str(row["asset_id"]), "score": float(score),
                           "logicalPath": str(row["logical_path"]), "locator": locator if isinstance(locator, dict) else {},
                           "texts": ResultAssembler._normalize_texts(row), "preview": preview})
        return result

    def search(self, req):
        loaded = registry.ensure_loaded(str(req.datasetId), str(req.versionId), str(req.indexVersionId))
        vector = self.encoder.encode_text(req.query)
        if loaded.unified_index is not None and loaded.unified_metadata is not None:
            candidate_count = min(loaded.unified_index.ntotal, max(req.topK, req.topK * 5))
            scores, indices = FaissRetriever.search(loaded.unified_index, vector, candidate_count)
            items = ResultAssembler.assemble_unified_as_text_to_image(
                scores, indices, loaded.unified_metadata, req.topK
            )
            return TextToImageResp(items=items)
        scores, indices = FaissRetriever.search(loaded.image_index, vector, req.topK)
        items = ResultAssembler.assemble_text_to_image(scores, indices, loaded.image_metadata)
        return TextToImageResp(items=items)
