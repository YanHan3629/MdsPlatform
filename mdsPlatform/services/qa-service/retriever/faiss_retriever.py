class FaissRetriever:
    """与 search-service 相同的 FAISS 检索工具（供未来向量检索复用）。"""

    @staticmethod
    def search(index, vector, top_k: int):
        scores, indices = index.search(vector, top_k)
        return scores[0].tolist(), indices[0].tolist()
