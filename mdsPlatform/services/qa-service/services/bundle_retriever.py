import re
from typing import List, Optional

from services.manifest_loader import ManifestLoader


class BundleRetriever:
    """基于索引 bundle 的轻量本地检索（不依赖 CLIP 编码）。

    直接消费与检索服务相同的 parquet 文本元数据，用词元/字元重叠打分；
    用于在无法调用 search-service 时，仍可依据数据空间内容作答。
    """

    def __init__(self):
        self._loader = ManifestLoader()
        self._cache = {}

    def _load(self, dataset_id, version_id, index_version_id):
        key = f"{dataset_id}:{version_id}:{index_version_id}"
        if key not in self._cache:
            self._cache[key] = self._loader.load(str(dataset_id), str(version_id), str(index_version_id))
        return self._cache[key]

    @staticmethod
    def _tokens(text: str) -> set:
        text = (text or "").lower()
        words = set(re.findall(r"[a-z0-9_]+", text))
        cjk = re.findall(r"[\u4e00-\u9fff]", text)
        bigrams = {a + b for a, b in zip(cjk, cjk[1:])}
        return words | bigrams

    def retrieve(self, dataset_id, version_id, index_version_id, query: str, top_k: int = 8) -> list:
        loaded = self._load(dataset_id, version_id, index_version_id)
        df = loaded["text_metadata"]
        if df is None or df.empty:
            return []
        q_tokens = self._tokens(query)
        if not q_tokens:
            return []
        rows = []
        for _, row in df.iterrows():
            text = str(row.get("text") or row.get("texts") or row.get("caption") or "")
            tokens = self._tokens(text)
            if not tokens:
                continue
            overlap = len(q_tokens & tokens)
            if overlap <= 0:
                continue
            score = overlap / max(1, len(q_tokens))
            rows.append((score, row, text))
        rows.sort(key=lambda x: x[0], reverse=True)
        out = []
        for score, row, text in rows[:top_k]:
            out.append(
                {
                    "asset_id": row.get("asset_id"),
                    "logical_path": row.get("logical_path"),
                    "text": text,
                    "score": float(score),
                }
            )
        return out
