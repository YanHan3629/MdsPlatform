class FaissRetriever:
    @staticmethod
    def search(index, vector, top_k: int):
        scores, indices = index.search(vector, top_k)
        return scores[0].tolist(), indices[0].tolist()
