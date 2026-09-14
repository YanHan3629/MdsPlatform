from __future__ import annotations

import hashlib
import json
import uuid
from dataclasses import asdict, dataclass, field, is_dataclass
from pathlib import Path
from typing import Any


SCHEMA_VERSION = "1.0"
PARSER_CONFIG_VERSION = "2026-09-09"
ORGANIZER_VERSION = "2026-09-10.4"


def normalized_path(value: str) -> str:
    value = value.replace("\\", "/")
    return value if value.startswith("/") else "/" + value


def content_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def stable_id(*parts: str) -> str:
    return str(uuid.uuid5(uuid.NAMESPACE_URL, ":".join(str(part) for part in parts)))


def deterministic_asset_id(dataset_version_id: str, logical_path: str) -> str:
    """Match Java UUID.nameUUIDFromBytes and the existing CLIP asset identity."""
    source = f"{dataset_version_id}:{normalized_path(logical_path)}".encode("utf-8")
    digest = bytearray(hashlib.md5(source).digest())
    digest[6] = (digest[6] & 0x0F) | 0x30
    digest[8] = (digest[8] & 0x3F) | 0x80
    return str(uuid.UUID(bytes=bytes(digest)))


@dataclass
class ParseContext:
    dataset_version_id: str
    logical_path: str
    output_dir: Path
    timezone: str | None = None
    unit_mapping: dict[str, str] = field(default_factory=dict)
    time_column: str | None = None
    entity_columns: list[str] = field(default_factory=list)
    duplicate_policy: str = "keep-last-by-source-row"
    interval_boundary: str = "[start,end)"


@dataclass
class ParsedItem:
    kind: str
    payload: Any
    locator: dict[str, Any]
    entity_id: str | None = None
    units: dict[str, str] = field(default_factory=dict)
    quality_flags: list[str] = field(default_factory=list)


@dataclass
class ParsedAsset:
    asset_id: str
    dataset_version_id: str
    logical_path: str
    source_format: str
    content_sha256: str
    parser_name: str
    parser_version: str
    parser_config_version: str
    items: list[ParsedItem]
    attributes: dict[str, Any] = field(default_factory=dict)
    quality_flags: list[str] = field(default_factory=list)


@dataclass
class SemanticUnit:
    unit_id: str
    unit_type: str
    asset_id: str
    dataset_version_id: str
    logical_path: str
    content_sha256: str
    locator: dict[str, Any]
    entity_id: str | None
    units: dict[str, str]
    quality_flags: list[str]
    parser_name: str
    parser_version: str
    parser_config_version: str
    organizer_version: str
    payload: Any

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


def json_safe(value: Any) -> Any:
    if value is None or isinstance(value, (str, int, float, bool)):
        return value
    if isinstance(value, Path):
        return str(value)
    if is_dataclass(value):
        return json_safe(asdict(value))
    if isinstance(value, dict):
        return {str(key): json_safe(item) for key, item in value.items()}
    if isinstance(value, (list, tuple, set)):
        return [json_safe(item) for item in value]
    if hasattr(value, "item"):
        try:
            return value.item()
        except Exception:
            pass
    return str(value)


def write_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(json_safe(value), ensure_ascii=False, indent=2), encoding="utf-8")
