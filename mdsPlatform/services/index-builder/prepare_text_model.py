"""Fetch and verify the immutable, CPU-sized Chinese embedding weights at image build time."""

from hashlib import sha256
from pathlib import Path

from huggingface_hub import snapshot_download


REVISION = "7999e1d3359715c523056ef9478215996d62a620"
WEIGHTS_SHA256 = "354763b9b1357bc9c44f62c6be2276321081ed2567773608c0d0785b61d5a026"
MODEL_DIR = Path("/app/bge-small-zh-v1.5")
FILES = ["config.json", "model.safetensors", "tokenizer.json", "tokenizer_config.json",
         "special_tokens_map.json", "vocab.txt"]


def ready() -> bool:
    if any(not (MODEL_DIR / name).is_file() for name in FILES):
        return False
    digest = sha256()
    with (MODEL_DIR / "model.safetensors").open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest() == WEIGHTS_SHA256


if not ready():
    snapshot_download("BAAI/bge-small-zh-v1.5", revision=REVISION, local_dir=MODEL_DIR,
                      allow_patterns=FILES)
if not ready():
    raise RuntimeError("Pinned Chinese embedding model files did not pass SHA-256 validation")
