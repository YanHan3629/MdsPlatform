"""CPU-only query embeddings for the pinned Chinese BGE passage index."""

from __future__ import annotations

import os
from functools import lru_cache

import numpy as np
import torch
from transformers import AutoModel, AutoTokenizer


MODEL_REVISION = "7999e1d3359715c523056ef9478215996d62a620"
MODEL_VERSION = f"BAAI/bge-small-zh-v1.5@{MODEL_REVISION}"
MODEL_DIMENSION = 512
MODEL_PATH = os.environ.get("TEXT_EMBEDDING_MODEL_PATH", "/app/bge-small-zh-v1.5")
QUERY_INSTRUCTION = "为这个句子生成表示以用于检索相关文章："


@lru_cache(maxsize=1)
def _load_model(model_path: str):
    torch.set_num_threads(min(4, max(1, os.cpu_count() or 1)))
    tokenizer = AutoTokenizer.from_pretrained(model_path, local_files_only=True)
    model = AutoModel.from_pretrained(model_path, local_files_only=True, use_safetensors=True)
    if int(model.config.hidden_size) != MODEL_DIMENSION:
        raise ValueError("Chinese embedding dimension does not match its pinned model registration")
    model.eval().to("cpu")
    return tokenizer, model


def encode_query(text: str, *, model_path: str = MODEL_PATH) -> np.ndarray:
    tokenizer, model = _load_model(model_path)
    encoded = tokenizer(QUERY_INSTRUCTION + text, padding=True, truncation=True,
                        max_length=512, return_tensors="pt")
    with torch.inference_mode():
        pooled = model(**encoded).last_hidden_state[:, 0]
        normalized = torch.nn.functional.normalize(pooled, p=2, dim=1)
    return normalized.cpu().numpy().astype("float32")
