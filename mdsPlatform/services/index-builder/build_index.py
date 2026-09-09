import json
import os
from pathlib import Path

import faiss
import numpy as np
import pandas as pd
import requests
import torch
import torch.nn.functional as F
from PIL import Image
from transformers import AutoModel, AutoProcessor, CLIPModel, CLIPProcessor

from representations import build_representations


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
IMAGE_BATCH_SIZE = int(os.environ.get("IMAGE_BATCH_SIZE", "16"))
TEXT_BATCH_SIZE = int(os.environ.get("TEXT_BATCH_SIZE", "32"))


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


def encode_images(model, processor, image_paths: list[Path], batch_size: int = IMAGE_BATCH_SIZE) -> np.ndarray:
    features = []
    for index in range(0, len(image_paths), batch_size):
        batch_paths = image_paths[index:index + batch_size]
        images = []
        for path in batch_paths:
            with Image.open(path) as image:
                images.append(image.convert("RGB"))
        inputs = processor(images=images, return_tensors="pt")
        inputs = {key: value.to(DEVICE) for key, value in inputs.items() if hasattr(value, "to")}
        with torch.no_grad():
            batch_features = F.normalize(model.get_image_features(**inputs), dim=-1)
        features.append(batch_features.cpu().numpy())
    return np.concatenate(features, axis=0) if features else np.empty((0, 0), dtype="float32")


def encode_texts(model, processor, texts: list[str], batch_size: int = TEXT_BATCH_SIZE) -> np.ndarray:
    features = []
    for index in range(0, len(texts), batch_size):
        batch_texts = texts[index:index + batch_size]
        inputs = processor(text=batch_texts, return_tensors="pt", padding=True, truncation=True, max_length=77)
        inputs = {key: value.to(DEVICE) for key, value in inputs.items() if hasattr(value, "to")}
        with torch.no_grad():
            batch_features = F.normalize(model.get_text_features(**inputs), dim=-1)
        features.append(batch_features.cpu().numpy())
    return np.concatenate(features, axis=0) if features else np.empty((0, 0), dtype="float32")


def build_index(vectors: np.ndarray, embedding_dim: int) -> faiss.Index:
    index = faiss.IndexFlatIP(embedding_dim)
    if len(vectors):
        index.add(vectors.astype("float32"))
    return index


def metadata_frame(rows: list[dict]) -> pd.DataFrame:
    safe_rows = [{key: value for key, value in row.items() if key != "actual_path"} for row in rows]
    columns = [
        "asset_id", "logical_path", "source_format", "representation_id",
        "representation_type", "kind", "text", "locator", "manifest_path", "preview_path",
    ]
    return pd.DataFrame(safe_rows).reindex(columns=columns)


def write_outputs(image_vectors: np.ndarray, text_vectors: np.ndarray,
                  image_rows: list[dict], text_rows: list[dict],
                  manifests: list[dict], embedding_dim: int):
    image_dir = OUTPUT_DIR / "indexes" / "image"
    text_dir = OUTPUT_DIR / "indexes" / "text"
    unified_dir = OUTPUT_DIR / "indexes" / "unified"
    representation_dir = OUTPUT_DIR / "representations"
    for directory in (image_dir, text_dir, unified_dir, representation_dir):
        directory.mkdir(parents=True, exist_ok=True)

    image_index = build_index(image_vectors, embedding_dim)
    text_index = build_index(text_vectors, embedding_dim)
    unified_vectors = np.concatenate([image_vectors, text_vectors], axis=0)
    unified_index = build_index(unified_vectors, embedding_dim)

    image_meta = metadata_frame(image_rows)
    text_meta = metadata_frame(text_rows)
    unified_meta = pd.concat([image_meta, text_meta], ignore_index=True)

    faiss.write_index(image_index, str(image_dir / "faiss.index"))
    faiss.write_index(text_index, str(text_dir / "faiss.index"))
    faiss.write_index(unified_index, str(unified_dir / "faiss.index"))
    image_meta.to_parquet(image_dir / "id_map.parquet", index=False)
    text_meta.to_parquet(text_dir / "id_map.parquet", index=False)
    unified_meta.to_parquet(unified_dir / "id_map.parquet", index=False)

    representation_catalog = {
        "schemaVersion": "1.0",
        "datasetId": DATASET_ID,
        "datasetVersionId": VERSION_ID,
        "assetCount": len(manifests),
        "textRepresentationCount": len(text_rows),
        "visualRepresentationCount": len(image_rows),
        "assets": [
            {
                "assetId": item["assetId"],
                "sourcePath": item["sourcePath"],
                "sourceFormat": item["sourceFormat"],
                "manifestPath": f"/representations/manifests/{item['assetId']}.json",
            }
            for item in manifests
        ],
    }
    (representation_dir / "manifest.json").write_text(
        json.dumps(representation_catalog, ensure_ascii=False, indent=2), encoding="utf-8"
    )

    manifest = {
        "schemaVersion": "2.0",
        "datasetId": DATASET_ID,
        "datasetVersionId": VERSION_ID,
        "indexVersionId": INDEX_VERSION_ID,
        "modelName": Path(MODEL_PATH).name,
        "embeddingDim": embedding_dim,
        "imageIndexPath": "/indexes/image/faiss.index",
        "textIndexPath": "/indexes/text/faiss.index",
        "imageMetadataPath": "/indexes/image/id_map.parquet",
        "textMetadataPath": "/indexes/text/id_map.parquet",
        "unifiedIndexPath": "/indexes/unified/faiss.index",
        "unifiedMetadataPath": "/indexes/unified/id_map.parquet",
        "representationManifestPath": "/representations/manifest.json",
        "imageCount": len(image_rows),
        "textCount": len(text_rows),
        "unifiedCount": len(unified_meta),
        "assetCount": len(manifests),
    }
    (OUTPUT_DIR / "indexes" / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8"
    )
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
        "unifiedIndexPath": manifest["unifiedIndexPath"],
        "unifiedMetadataPath": manifest["unifiedMetadataPath"],
        "representationManifestPath": manifest["representationManifestPath"],
        "manifestPath": "/indexes/manifest.json",
        "embeddingDim": manifest["embeddingDim"],
        "imageCount": manifest["imageCount"],
        "textCount": manifest["textCount"],
        "unifiedCount": manifest["unifiedCount"],
    }
    response = requests.post(url, headers=headers, json=body, timeout=60)
    response.raise_for_status()


def callback_failed(message: str):
    if not BACKEND_BASE_URL or not FAILED_PATH:
        return
    url = BACKEND_BASE_URL.rstrip("/") + FAILED_PATH
    headers = {"Content-Type": "application/json"}
    if BACKEND_BEARER_TOKEN:
        headers["Authorization"] = f"Bearer {BACKEND_BEARER_TOKEN}"
    response = requests.post(url, headers=headers, json={"errorMessage": message}, timeout=60)
    response.raise_for_status()


def main():
    try:
        if not VERSION_ID:
            raise RuntimeError("VERSION_ID/versionId is required")
        bundle = build_representations(INPUT_DIR, OUTPUT_DIR, VERSION_ID)
        if not bundle.manifests:
            raise RuntimeError("no supported business files found")
        if not bundle.text_units:
            raise RuntimeError("no text representations generated")

        model, processor = load_model()
        text_vectors = encode_texts(model, processor, [row["text"] for row in bundle.text_units])
        image_vectors = encode_images(model, processor, [row["actual_path"] for row in bundle.visual_units])
        embedding_dim = text_vectors.shape[1]
        if image_vectors.size and image_vectors.shape[1] != embedding_dim:
            raise RuntimeError("CLIP text and image embeddings are not in the same vector space")
        if not image_vectors.size:
            image_vectors = np.empty((0, embedding_dim), dtype="float32")

        manifest = write_outputs(
            image_vectors, text_vectors, bundle.visual_units, bundle.text_units,
            bundle.manifests, embedding_dim,
        )
        callback_ready(manifest)
    except Exception as exc:
        try:
            callback_failed(str(exc))
        finally:
            raise


if __name__ == "__main__":
    main()
