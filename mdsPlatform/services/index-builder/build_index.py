import hashlib
import io
import json
import os
import uuid
from pathlib import Path

import faiss
import numpy as np
import pandas as pd
import torch
import torch.nn.functional as F
from PIL import Image
from transformers import AutoModel, AutoProcessor, CLIPModel, CLIPProcessor
import requests

INPUT_DIR = Path(os.environ.get("INPUT_DIR", "/tmp/in"))
OUTPUT_DIR = Path(os.environ.get("OUTPUT_DIR", "/tmp/out"))
MODEL_PATH = os.environ.get("MODEL_PATH", "clip_model_chinese")
DATASET_ID = os.environ.get("DATASET_ID") or os.environ.get("datasetId")
VERSION_ID = os.environ.get("VERSION_ID") or os.environ.get("versionId")
INDEX_VERSION_ID = os.environ.get("INDEX_VERSION_ID") or os.environ.get("indexVersionId")
BACKEND_BASE_URL = os.environ.get("BACKEND_BASE_URL", "")
BACKEND_BEARER_TOKEN = os.environ.get("BACKEND_BEARER_TOKEN", "")
READY_PATH = os.environ.get("BACKEND_CALLBACK_READY") or os.environ.get("backendCallbackReady")
FAILED_PATH = os.environ.get("BACKEND_CALLBACK_FAILED") or os.environ.get("backendCallbackFailed")
OUTPUT_COMMIT_ID = os.environ.get("OUTPUT_COMMIT_ID")
DEVICE = "cuda" if torch.cuda.is_available() else "cpu"


def deterministic_asset_id(dataset_version_id: str, logical_path: str) -> str:
    source = f"{dataset_version_id}:{normalize_path(logical_path)}".encode("utf-8")
    md5 = bytearray(hashlib.md5(source).digest())
    md5[6] = (md5[6] & 0x0F) | 0x30
    md5[8] = (md5[8] & 0x3F) | 0x80
    return str(uuid.UUID(bytes=bytes(md5)))


def normalize_path(path: str) -> str:
    path = path.replace("\\", "/")
    if not path.startswith("/"):
        path = "/" + path
    while "//" in path:
        path = path.replace("//", "/")
    return path


def find_captions_json() -> Path:
    candidates = sorted(INPUT_DIR.rglob("*.json"))
    for path in candidates:
        if "caption" in path.name.lower():
            return path
    raise FileNotFoundError("captions json not found under /tmp/in")


def index_input_files() -> dict[str, Path]:
    mapping = {}
    for file in INPUT_DIR.rglob("*"):
        if file.is_file():
            rel = "/" + file.relative_to(INPUT_DIR).as_posix()
            mapping[file.name] = Path(rel)
    return mapping


def load_model():
    config_path = Path(MODEL_PATH) / "config.json"
    if config_path.exists():
        config = json.loads(config_path.read_text(encoding="utf-8"))
        if config.get("model_type") == "chinese_clip":
            model = AutoModel.from_pretrained(MODEL_PATH, local_files_only=True)
            processor = AutoProcessor.from_pretrained(MODEL_PATH, local_files_only=True)
        else:
            model = CLIPModel.from_pretrained(MODEL_PATH, local_files_only=True)
            processor = CLIPProcessor.from_pretrained(MODEL_PATH, local_files_only=True)
    else:
        model = CLIPModel.from_pretrained(MODEL_PATH)
        processor = CLIPProcessor.from_pretrained(MODEL_PATH)
    model = model.to(DEVICE)
    model.eval()
    return model, processor


def encode_images(model, processor, image_paths: list[Path], batch_size: int = 32) -> np.ndarray:
    features = []
    for i in range(0, len(image_paths), batch_size):
        batch_paths = image_paths[i:i + batch_size]
        images = [Image.open(path).convert("RGB") for path in batch_paths]
        inputs = processor(images=images, return_tensors="pt")
        inputs = {k: v.to(DEVICE) for k, v in inputs.items() if hasattr(v, "to")}
        with torch.no_grad():
            batch_features = model.get_image_features(**inputs)
            batch_features = F.normalize(batch_features, dim=-1)
        features.append(batch_features.cpu().numpy())
    return np.concatenate(features, axis=0)


def encode_texts(model, processor, texts: list[str], batch_size: int = 32) -> np.ndarray:
    features = []
    for i in range(0, len(texts), batch_size):
        batch_texts = texts[i:i + batch_size]
        inputs = processor(text=batch_texts, return_tensors="pt", padding=True, truncation=True, max_length=77)
        inputs = {k: v.to(DEVICE) for k, v in inputs.items() if hasattr(v, "to")}
        with torch.no_grad():
            batch_features = model.get_text_features(**inputs)
            batch_features = F.normalize(batch_features, dim=-1)
        features.append(batch_features.cpu().numpy())
    return np.concatenate(features, axis=0)


def build_index(vectors: np.ndarray) -> faiss.Index:
    dim = vectors.shape[1]
    index = faiss.IndexFlatIP(dim)
    index.add(vectors.astype("float32"))
    return index


def write_outputs(image_index, text_index, image_meta: pd.DataFrame, text_meta: pd.DataFrame, embedding_dim: int):
    image_dir = OUTPUT_DIR / "indexes" / "image"
    text_dir = OUTPUT_DIR / "indexes" / "text"
    image_dir.mkdir(parents=True, exist_ok=True)
    text_dir.mkdir(parents=True, exist_ok=True)

    image_index_path = image_dir / "faiss.index"
    text_index_path = text_dir / "faiss.index"
    image_meta_path = image_dir / "id_map.parquet"
    text_meta_path = text_dir / "id_map.parquet"
    manifest_path = OUTPUT_DIR / "indexes" / "manifest.json"

    faiss.write_index(image_index, str(image_index_path))
    faiss.write_index(text_index, str(text_index_path))
    image_meta.to_parquet(image_meta_path, index=False)
    text_meta.to_parquet(text_meta_path, index=False)

    manifest = {
        "datasetId": DATASET_ID,
        "datasetVersionId": VERSION_ID,
        "indexVersionId": INDEX_VERSION_ID,
        "modelName": Path(MODEL_PATH).name,
        "embeddingDim": embedding_dim,
        "imageIndexPath": "/indexes/image/faiss.index",
        "textIndexPath": "/indexes/text/faiss.index",
        "imageMetadataPath": "/indexes/image/id_map.parquet",
        "textMetadataPath": "/indexes/text/id_map.parquet",
        "imageCount": len(image_meta),
        "textCount": len(text_meta),
    }
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    return manifest


def callback_ready(manifest: dict):
    if not BACKEND_BASE_URL or not READY_PATH:
        return
    url = BACKEND_BASE_URL.rstrip("/") + READY_PATH
    headers = {"Content-Type": "application/json"}
    if BACKEND_BEARER_TOKEN:
        headers["Authorization"] = f"Bearer {BACKEND_BEARER_TOKEN}"
    body = {
        "indexCommitId": OUTPUT_COMMIT_ID,
        "imageIndexPath": manifest["imageIndexPath"],
        "textIndexPath": manifest["textIndexPath"],
        "imageMetadataPath": manifest["imageMetadataPath"],
        "textMetadataPath": manifest["textMetadataPath"],
        "manifestPath": "/indexes/manifest.json",
        "embeddingDim": manifest["embeddingDim"],
        "imageCount": manifest["imageCount"],
        "textCount": manifest["textCount"],
    }
    resp = requests.post(url, headers=headers, json=body, timeout=60)
    resp.raise_for_status()


def callback_failed(message: str):
    if not BACKEND_BASE_URL or not FAILED_PATH:
        return
    url = BACKEND_BASE_URL.rstrip("/") + FAILED_PATH
    headers = {"Content-Type": "application/json"}
    if BACKEND_BEARER_TOKEN:
        headers["Authorization"] = f"Bearer {BACKEND_BEARER_TOKEN}"
    resp = requests.post(url, headers=headers, json={"errorMessage": message}, timeout=60)
    resp.raise_for_status()


def main():
    try:
        captions_path = find_captions_json()
        basename_to_path = index_input_files()
        data = json.loads(captions_path.read_text(encoding="utf-8"))
        images = data.get("images", [])
        annotations = data.get("annotations", [])
        image_id_to_captions: dict[int, list[str]] = {}
        for ann in annotations:
            image_id = ann.get("image_id")
            caption = ann.get("caption")
            if image_id is None or not caption:
                continue
            image_id_to_captions.setdefault(image_id, []).append(str(caption).strip())

        rows = []
        for image in images:
            file_name = image.get("file_name")
            image_id = image.get("id")
            if not file_name:
                continue
            logical_path = basename_to_path.get(file_name) or basename_to_path.get(Path(file_name).name)
            if logical_path is None:
                continue
            actual_path = INPUT_DIR / logical_path.relative_to("/")
            if not actual_path.exists():
                continue
            captions = image_id_to_captions.get(image_id, [])
            asset_id = deterministic_asset_id(VERSION_ID, logical_path.as_posix())
            rows.append({
                "asset_id": asset_id,
                "logical_path": normalize_path(logical_path.as_posix()),
                "actual_path": actual_path,
                "file_name": file_name,
                "captions": captions,
            })

        if not rows:
            raise RuntimeError("no valid image-caption pairs found")

        model, processor = load_model()
        image_paths = [row["actual_path"] for row in rows]
        image_vectors = encode_images(model, processor, image_paths)

        text_rows = []
        for row in rows:
            captions = row["captions"] or [row["file_name"]]
            for caption in captions:
                text_rows.append({
                    "asset_id": row["asset_id"],
                    "logical_path": row["logical_path"],
                    "text": caption,
                })
        text_vectors = encode_texts(model, processor, [r["text"] for r in text_rows])

        image_index = build_index(image_vectors)
        text_index = build_index(text_vectors)

        image_meta = pd.DataFrame([
            {
                "asset_id": row["asset_id"],
                "logical_path": row["logical_path"],
                "texts": row["captions"] or [row["file_name"]],
            }
            for row in rows
        ])
        text_meta = pd.DataFrame(text_rows)
        manifest = write_outputs(image_index, text_index, image_meta, text_meta, image_vectors.shape[1])
        callback_ready(manifest)
    except Exception as e:
        try:
            callback_failed(str(e))
        finally:
            raise


if __name__ == "__main__":
    main()
