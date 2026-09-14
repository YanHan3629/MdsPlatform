from __future__ import annotations

import json
import math
import re
import xml.etree.ElementTree as ET
from collections import Counter
from pathlib import Path
from typing import Iterable

import numpy as np
import pandas as pd
from PIL import Image

from .contracts import (
    PARSER_CONFIG_VERSION,
    ParseContext,
    ParsedAsset,
    ParsedItem,
    content_sha256,
    deterministic_asset_id,
    normalized_path,
    stable_id,
)


def _read_text(path: Path) -> str:
    for encoding in ("utf-8-sig", "utf-8", "gb18030"):
        try:
            return path.read_text(encoding=encoding)
        except UnicodeDecodeError:
            continue
    return path.read_text(encoding="utf-8", errors="replace")


def _chunks(text: str, limit: int = 900, overlap: int = 100):
    cursor = 0
    while cursor < len(text):
        end = min(len(text), cursor + limit)
        if end < len(text):
            split = max(text.rfind("\n", cursor, end), text.rfind("。", cursor, end))
            if split > cursor + limit // 2:
                end = split + 1
        yield cursor, end, text[cursor:end]
        if end >= len(text):
            break
        cursor = max(cursor + 1, end - overlap)


class AssetParser:
    name = "base"
    version = "1.0"
    extensions: tuple[str, ...] = ()

    def supports(self, path: Path) -> bool:
        return path.suffix.lower() in self.extensions

    def _asset(self, path: Path, context: ParseContext, items: list[ParsedItem],
               attributes=None, quality_flags=None) -> ParsedAsset:
        checksum = content_sha256(path)
        return ParsedAsset(
            asset_id=deterministic_asset_id(context.dataset_version_id, context.logical_path),
            dataset_version_id=context.dataset_version_id,
            logical_path=normalized_path(context.logical_path),
            source_format=("JPG" if path.suffix.lower() == ".jpeg" else path.suffix[1:].upper()),
            content_sha256=checksum,
            parser_name=self.name,
            parser_version=self.version,
            parser_config_version=PARSER_CONFIG_VERSION,
            items=items,
            attributes=attributes or {},
            quality_flags=quality_flags or [],
        )

    def parse(self, path: Path, context: ParseContext) -> ParsedAsset:
        raise NotImplementedError


class ImageParser(AssetParser):
    name = "image"
    extensions = (".jpg", ".jpeg", ".png")

    def parse(self, path: Path, context: ParseContext) -> ParsedAsset:
        with Image.open(path) as image:
            attrs = {"width": image.width, "height": image.height, "mode": image.mode, "format": image.format}
        items = [
            ParsedItem("visual", {"path": str(path), "caption": path.name}, {"region": "full-image"}),
            ParsedItem("text", path.stem.replace("_", " "), {"source": "filename"}),
        ]
        return self._asset(path, context, items, attrs)


class TextParser(AssetParser):
    name = "text"
    extensions = (".txt",)

    def parse(self, path: Path, context: ParseContext) -> ParsedAsset:
        text = _read_text(path)
        items = [ParsedItem("text", chunk, {"characterStart": start, "characterEnd": end})
                 for start, end, chunk in _chunks(text) if chunk.strip()]
        return self._asset(path, context, items, {"characterCount": len(text)})


class DocumentParser(AssetParser):
    name = "pdf"
    extensions = (".pdf",)

    def parse(self, path: Path, context: ParseContext) -> ParsedAsset:
        import fitz

        items: list[ParsedItem] = []
        flags: list[str] = []
        document = fitz.open(path)
        for page_number, page in enumerate(document, start=1):
            text = page.get_text("text").strip()
            if text:
                for start, end, chunk in _chunks(text):
                    items.append(ParsedItem("text", chunk, {
                        "page": page_number, "characterStart": start, "characterEnd": end,
                    }))
            else:
                flags.append(f"PAGE_{page_number}_NO_EXTRACTABLE_TEXT")
            preview = context.output_dir / "representations" / stable_id(
                context.dataset_version_id, context.logical_path
            ) / "visual" / f"page-{page_number:04d}.png"
            preview.parent.mkdir(parents=True, exist_ok=True)
            page.get_pixmap(matrix=fitz.Matrix(1.25, 1.25), alpha=False).save(preview)
            items.append(ParsedItem("visual", {"path": str(preview), "caption": f"{path.name} 第 {page_number} 页"}, {"page": page_number}))
            try:
                finder = getattr(page, "find_tables", None)
                if finder:
                    for table_number, table in enumerate(finder().tables, start=1):
                        frame = table.to_pandas()
                        items.append(ParsedItem("table", _frame_records(frame), {
                            "page": page_number, "table": table_number, "rawRowStart": 1,
                        }))
            except Exception:
                flags.append(f"PAGE_{page_number}_TABLE_EXTRACTION_UNAVAILABLE")
        attributes = {"pageCount": document.page_count, "textExtraction": "native", "renderedPages": document.page_count}
        document.close()
        return self._asset(path, context, items, attributes, sorted(set(flags)))


def _scalar(value):
    # JSON cells may legitimately contain nested arrays or objects.  Calling
    # pd.isna on those values returns an array, whose truth value raises a
    # ValueError and used to isolate an otherwise valid JSON asset as failed.
    if isinstance(value, dict):
        return {str(key): _scalar(child) for key, child in value.items()}
    if isinstance(value, (list, tuple, np.ndarray)):
        return [_scalar(child) for child in value]
    try:
        missing = pd.isna(value)
        if isinstance(missing, (bool, np.bool_)) and bool(missing):
            return None
    except (TypeError, ValueError):
        pass
    if hasattr(value, "isoformat"):
        try:
            return value.isoformat()
        except Exception:
            pass
    if hasattr(value, "item"):
        return value.item()
    return value


def _frame_records(frame: pd.DataFrame) -> dict:
    columns = []
    for column in frame.columns:
        values = frame[column].dropna()
        columns.append({
            "name": str(column),
            "dtype": str(frame[column].dtype),
            "nullable": bool(frame[column].isna().any()),
            "sample": [_scalar(value) for value in values.head(5)],
        })
    rows = []
    for position, (_, row) in enumerate(frame.iterrows(), start=2):
        rows.append({"rawRow": position, "values": {str(key): _scalar(value) for key, value in row.items()}})
    return {"columns": columns, "records": rows}


class TableParser(AssetParser):
    name = "table"
    extensions = (".csv", ".xlsx")

    def parse(self, path: Path, context: ParseContext) -> ParsedAsset:
        if path.suffix.lower() == ".csv":
            sheets = {"root": pd.read_csv(path, encoding_errors="replace", low_memory=False)}
        else:
            sheets = pd.read_excel(path, sheet_name=None)
        items = [ParsedItem("table", _frame_records(frame), {"sheet": str(name), "rawRowStart": 2}, units=context.unit_mapping)
                 for name, frame in sheets.items()]
        return self._asset(path, context, items, {"sheetCount": len(sheets)})


class JsonParser(AssetParser):
    name = "json"
    version = "1.1"
    extensions = (".json", ".jsonl")

    def parse(self, path: Path, context: ParseContext) -> ParsedAsset:
        text = _read_text(path)
        if path.suffix.lower() == ".jsonl":
            values = [json.loads(line) for line in text.splitlines() if line.strip()]
        else:
            values = json.loads(text)
        table_items: list[ParsedItem] = []
        if isinstance(values, list) and (not values or all(isinstance(item, dict) for item in values)):
            table_items.append(ParsedItem("table", _frame_records(pd.json_normalize(values)), {"jsonPath": "$", "rawRowStart": 1}, units=context.unit_mapping))
        elif isinstance(values, dict):
            for key, child in values.items():
                if isinstance(child, list) and child and all(isinstance(item, dict) for item in child):
                    table_items.append(ParsedItem("table", _frame_records(pd.json_normalize(child)), {"jsonPath": f"$.{key}", "rawRowStart": 1}, units=context.unit_mapping))
        if table_items:
            return self._asset(path, context, table_items, {"tableCount": len(table_items)})
        items = [ParsedItem("text", chunk, {"jsonPath": "$", "characterStart": start, "characterEnd": end})
                 for start, end, chunk in _chunks(json.dumps(values, ensure_ascii=False, indent=2))]
        return self._asset(path, context, items, {"structured": True})


class SvgParser(AssetParser):
    name = "svg"
    extensions = (".svg",)

    def parse(self, path: Path, context: ParseContext) -> ParsedAsset:
        root = ET.fromstring(_read_text(path))
        text_nodes = ["".join(node.itertext()).strip() for node in root.iter() if node.tag.rsplit("}", 1)[-1] == "text"]
        preview = context.output_dir / "representations" / deterministic_asset_id(context.dataset_version_id, context.logical_path) / "visual" / "source.png"
        preview.parent.mkdir(parents=True, exist_ok=True)
        flags: list[str] = []
        items = [ParsedItem("text", value, {"svgElement": "text", "sequence": index})
                 for index, value in enumerate(text_nodes, start=1) if value]
        try:
            import cairosvg
            cairosvg.svg2png(url=str(path), write_to=str(preview), output_width=1024, output_height=1024)
            items.append(ParsedItem("visual", {"path": str(preview), "caption": path.name}, {"region": "full-svg"}))
        except Exception as exc:
            flags.append("SVG_RENDER_FAILED")
            flags.append(type(exc).__name__)
        attrs = {"width": root.attrib.get("width"), "height": root.attrib.get("height"), "viewBox": root.attrib.get("viewBox")}
        return self._asset(path, context, items, attrs, flags)


def _mesh_features(vertices: np.ndarray, faces: list[list[int]]) -> tuple[dict, np.ndarray]:
    if not np.isfinite(vertices).all():
        raise ValueError("MESH_NONFINITE_VERTEX")
    minimum, maximum = vertices.min(axis=0), vertices.max(axis=0)
    size = maximum - minimum
    area = 0.0
    signed_volume = 0.0
    edges: Counter[tuple[int, int]] = Counter()
    directed: Counter[tuple[int, int]] = Counter()
    degenerate = False
    center = vertices.mean(axis=0)
    for face in faces:
        if len(face) < 3 or any(index < 0 or index >= len(vertices) for index in face):
            raise ValueError("MESH_INVALID_FACE_INDEX")
        if len(set(face)) != len(face): degenerate = True
        for index in range(len(face)):
            edge = tuple(sorted((face[index], face[(index + 1) % len(face)])))
            edges[edge] += 1
            directed[(face[index], face[(index + 1) % len(face)])] += 1
        origin = vertices[face[0]]
        for index in range(1, len(face) - 1):
            a, b = vertices[face[index]], vertices[face[index + 1]]
            triangle_area = np.linalg.norm(np.cross(a - origin, b - origin)) / 2.0
            area += triangle_area
            if triangle_area <= np.finfo(float).eps * max(float(np.linalg.norm(size)) ** 2, 1e-20): degenerate = True
            signed_volume += float(np.dot(origin - center, np.cross(a - center, b - center))) / 6.0
    closed = bool(faces) and all(count == 2 for count in edges.values())
    oriented = closed and all(directed[(a, b)] == directed[(b, a)] == 1 for a, b in edges)
    volume_valid = closed and oriented and not degenerate
    centered = vertices - vertices.mean(axis=0)
    eigenvalues = np.sort(np.linalg.eigvalsh(np.cov(centered.T)))[::-1] if len(vertices) > 2 else np.zeros(3)
    moment_sum = float(eigenvalues.sum())
    scale = math.sqrt(moment_sum) if moment_sum > 0 else (float(np.linalg.norm(size)) or 1.0)
    moment_ratios = eigenvalues / (moment_sum or 1.0)
    vector = np.asarray([
        *moment_ratios,
        area / (scale * scale),
        (abs(signed_volume) / (scale ** 3)) if volume_valid else 0.0,
        math.log1p(len(faces)) / 20.0,
        math.log1p(1) / 5.0,
        1.0 if closed else 0.0,
    ], dtype="float32")
    attrs = {
        "vertexCount": len(vertices), "faceCount": len(faces),
        "bounds": {"minimum": minimum.tolist(), "maximum": maximum.tolist(), "size": size.tolist()},
        "surfaceArea": area, "closed": closed,
        "volume": abs(signed_volume) if volume_valid else None,
        "orientationConsistent": oriented, "degenerateFaces": degenerate,
        "volumeValidation": "closed-oriented-nondegenerate; self-intersection not certified" if volume_valid else "invalid",
        "featureSpaceId": "mesh-principal-moments-v2",
        "principalMomentRatios": moment_ratios.tolist(),
        "shapeFeatureRotationInvariant": True,
        "shapeFeatureScaleInvariant": True,
        "featureVector": vector.tolist(),
    }
    return attrs, vector


class MeshParser(AssetParser):
    name = "obj-mesh"
    version = "2.0"
    extensions = (".obj",)

    def parse(self, path: Path, context: ParseContext) -> ParsedAsset:
        vertices, faces, materials = [], [], set()
        for line_number, line in enumerate(_read_text(path).splitlines(), start=1):
            parts = line.split()
            if not parts:
                continue
            if parts[0] == "v" and len(parts) >= 4:
                try:
                    vertices.append([float(parts[1]), float(parts[2]), float(parts[3])])
                except ValueError:
                    pass
            elif parts[0] == "f" and len(parts) >= 4:
                try:
                    indices = [int(value.split("/", 1)[0]) for value in parts[1:]]
                    faces.append([index - 1 if index > 0 else len(vertices) + index if index < 0 else -1 for index in indices])
                except ValueError:
                    pass
            elif parts[0] in {"mtllib", "usemtl"} and len(parts) > 1:
                materials.add(" ".join(parts[1:]))
        if not vertices:
            return self._asset(path, context, [], {"valid": False}, ["MESH_NO_VERTICES", "UNIT_UNKNOWN"])
        array = np.asarray(vertices, dtype=float)
        attrs, _ = _mesh_features(array, faces)
        attrs.update({"materials": sorted(materials), "unitSource": "mapping" if context.unit_mapping else "unknown", "coordinateSystem": "source"})
        flags = [] if faces else ["MESH_NO_FACES"]
        if not context.unit_mapping:
            flags.append("UNIT_UNKNOWN")
        if not attrs["closed"]:
            flags.append("NON_CLOSED_MESH")
        if attrs["closed"] and not attrs["orientationConsistent"]:
            flags.append("INCONSISTENT_FACE_ORIENTATION")
        if attrs["degenerateFaces"]:
            flags.append("DEGENERATE_MESH_FACE")
        flags.append("MESH_SELF_INTERSECTION_NOT_CERTIFIED")
        item = ParsedItem("geometry", attrs, {"partId": "root"}, entity_id="root", units=context.unit_mapping, quality_flags=flags)
        return self._asset(path, context, [item], attrs, flags)


class CadParser(AssetParser):
    name = "step-opencascade"
    version = "2.0"
    extensions = (".step", ".stp")

    def parse(self, path: Path, context: ParseContext) -> ParsedAsset:
        from .cad_geometry import read_cad_asset
        return read_cad_asset(self, path, context)


class ParserRegistry:
    def __init__(self, parsers: Iterable[AssetParser] | None = None):
        self._parsers: list[AssetParser] = []
        for parser in parsers or ():
            self.register(parser)

    def register(self, parser: AssetParser) -> None:
        overlap = set(parser.extensions).intersection(ext for item in self._parsers for ext in item.extensions)
        if overlap:
            raise ValueError(f"parser extension already registered: {sorted(overlap)}")
        self._parsers.append(parser)

    def resolve(self, path: Path) -> AssetParser | None:
        return next((parser for parser in self._parsers if parser.supports(path)), None)

    @property
    def supported_extensions(self) -> set[str]:
        return {ext for parser in self._parsers for ext in parser.extensions}


def default_parser_registry() -> ParserRegistry:
    return ParserRegistry([ImageParser(), TextParser(), DocumentParser(), TableParser(), JsonParser(), SvgParser(), MeshParser(), CadParser()])
