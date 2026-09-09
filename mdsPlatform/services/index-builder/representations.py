"""Build CLIP-compatible representations for every business file in a dataset.

The source object is never changed.  Each converter emits a deterministic JSON
manifest plus text and/or image units that can be embedded in CLIP's shared
vector space.  Table converters preserve temporal semantics explicitly so that
time-range and trend questions are not answered from unordered row samples.
"""

from __future__ import annotations

import hashlib
import json
import math
import re
import uuid
from collections import Counter
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable

import numpy as np
import pandas as pd
from PIL import Image, ImageDraw, ImageFont


SUPPORTED_EXTENSIONS = {
    ".jpg", ".jpeg", ".png", ".svg", ".txt", ".csv", ".json",
    ".jsonl", ".xlsx", ".pdf", ".obj", ".step",
}
INTERNAL_NAMES = {"faiss.index", "id_map.parquet", "manifest.json"}
TIME_NAME_RE = re.compile(
    r"(^|[_\-\s])(time|timestamp|datetime|date|created|updated|recorded|采集时间|时间|日期|时刻)($|[_\-\s])",
    re.IGNORECASE,
)
GROUP_NAME_RE = re.compile(r"(^|[_\-])(device|sensor|machine|equipment|asset|station|id|设备|传感器|测点)($|[_\-])", re.IGNORECASE)


@dataclass
class RepresentationBundle:
    manifests: list[dict[str, Any]]
    text_units: list[dict[str, Any]]
    visual_units: list[dict[str, Any]]


def normalize_path(path: str) -> str:
    value = path.replace("\\", "/")
    if not value.startswith("/"):
        value = "/" + value
    while "//" in value:
        value = value.replace("//", "/")
    return value


def deterministic_asset_id(dataset_version_id: str, logical_path: str) -> str:
    source = f"{dataset_version_id}:{normalize_path(logical_path)}".encode("utf-8")
    digest = bytearray(hashlib.md5(source).digest())
    digest[6] = (digest[6] & 0x0F) | 0x30
    digest[8] = (digest[8] & 0x3F) | 0x80
    return str(uuid.UUID(bytes=bytes(digest)))


def _json_safe(value: Any) -> Any:
    if value is None or isinstance(value, (str, int, bool)):
        return value
    if isinstance(value, float):
        return value if math.isfinite(value) else None
    if isinstance(value, (pd.Timestamp,)):
        return value.isoformat()
    if isinstance(value, np.generic):
        return _json_safe(value.item())
    if isinstance(value, dict):
        return {str(k): _json_safe(v) for k, v in value.items()}
    if isinstance(value, (list, tuple, set)):
        return [_json_safe(v) for v in value]
    return str(value)


def _unit_id(asset_id: str, kind: str, sequence: int) -> str:
    return f"{asset_id}:{kind}:{sequence:05d}"


def _read_text(path: Path) -> str:
    raw = path.read_bytes()
    for encoding in ("utf-8-sig", "utf-8", "gb18030", "latin-1"):
        try:
            return raw.decode(encoding)
        except UnicodeDecodeError:
            continue
    return raw.decode("utf-8", errors="replace")


def _chunks(text: str, max_chars: int = 700, overlap: int = 80) -> list[str]:
    compact = re.sub(r"[ \t]+", " ", text).strip()
    if not compact:
        return []
    paragraphs = [part.strip() for part in re.split(r"\n\s*\n", compact) if part.strip()]
    result: list[str] = []
    current = ""
    for paragraph in paragraphs:
        if len(current) + len(paragraph) + 1 <= max_chars:
            current = f"{current}\n{paragraph}".strip()
            continue
        if current:
            result.append(current)
        if len(paragraph) <= max_chars:
            current = paragraph
            continue
        step = max(1, max_chars - overlap)
        result.extend(paragraph[i:i + max_chars] for i in range(0, len(paragraph), step))
        current = ""
    if current:
        result.append(current)
    return result


def _format_number(value: float) -> str:
    if not math.isfinite(float(value)):
        return "不可用"
    return f"{float(value):.6g}"


def _parse_datetime(series: pd.Series, name: str) -> pd.Series:
    non_null = series.dropna()
    if non_null.empty:
        return pd.Series(pd.NaT, index=series.index, dtype="datetime64[ns]")
    if pd.api.types.is_numeric_dtype(non_null) and TIME_NAME_RE.search(str(name)):
        numeric = pd.to_numeric(series, errors="coerce")
        median = numeric.dropna().abs().median()
        unit = "ms" if median > 10_000_000_000 else "s"
        return pd.to_datetime(numeric, unit=unit, errors="coerce", utc=True)
    return pd.to_datetime(series, errors="coerce", utc=True, format="mixed")


def detect_temporal_column(frame: pd.DataFrame) -> tuple[str | None, pd.Series | None, float]:
    """Return the most credible time column, parsed timestamps and confidence."""
    best: tuple[str | None, pd.Series | None, float] = (None, None, 0.0)
    for column in frame.columns:
        series = frame[column]
        non_null = int(series.notna().sum())
        if non_null == 0:
            continue
        parsed = _parse_datetime(series, str(column))
        ratio = float(parsed.notna().sum()) / non_null
        name_bonus = 0.25 if TIME_NAME_RE.search(str(column)) else 0.0
        # A non-time numeric column can be parsed as nanoseconds, so numeric
        # candidates require a semantic column-name signal.
        if pd.api.types.is_numeric_dtype(series) and name_bonus == 0:
            continue
        score = min(1.0, ratio * 0.8 + name_bonus)
        if ratio >= 0.6 and score > best[2]:
            best = (str(column), parsed, score)
    return best


def _iso(timestamp: pd.Timestamp) -> str:
    return timestamp.isoformat().replace("+00:00", "Z")


def _series_order(values: pd.Series) -> str:
    differences = values.dropna().diff().dropna().dt.total_seconds()
    if differences.empty:
        return "single"
    if bool((differences >= 0).all()):
        return "ascending"
    if bool((differences <= 0).all()):
        return "descending"
    return "unsorted"


def _temporal_metadata(frame: pd.DataFrame, time_column: str, parsed: pd.Series,
                       confidence: float, group_column: str | None = None) -> dict[str, Any]:
    valid = parsed.dropna()
    ordering_by_series: dict[str, str] = {}
    interval_parts: list[pd.Series] = []
    if group_column:
        work = frame.assign(__parsed_time=parsed).dropna(subset=["__parsed_time"])
        for value, part in work.groupby(group_column, dropna=True, sort=False):
            series = part["__parsed_time"]
            ordering_by_series[str(value)] = _series_order(series)
            interval_parts.append(series.sort_values().drop_duplicates().diff().dropna().dt.total_seconds())
        distinct_orders = set(ordering_by_series.values())
        order = f"{next(iter(distinct_orders))}-by-series" if len(distinct_orders) == 1 else "mixed-by-series"
        duplicate_count = int(work.duplicated(subset=[group_column, "__parsed_time"]).sum())
    else:
        order = _series_order(valid.sort_index())
        duplicate_count = int(valid.duplicated().sum())
        interval_parts.append(valid.sort_values().drop_duplicates().diff().dropna().dt.total_seconds())
    intervals = pd.concat(interval_parts, ignore_index=True) if interval_parts else pd.Series(dtype=float)
    median_interval = float(intervals.median()) if not intervals.empty else None
    irregular = False
    if median_interval and len(intervals) > 1:
        deviations = (intervals - median_interval).abs()
        irregular = bool((deviations > max(1.0, abs(median_interval) * 0.1)).any())

    return {
        "detected": True,
        "timeColumn": time_column,
        "confidence": round(confidence, 3),
        "timeStart": _iso(valid.min()),
        "timeEnd": _iso(valid.max()),
        "ordering": order,
        "validTimeRows": int(valid.size),
        "invalidTimeRows": int(len(frame) - valid.size),
        "duplicateTimestampCount": duplicate_count,
        "medianIntervalSeconds": median_interval,
        "irregularSampling": irregular,
        "timezone": "UTC",
        "seriesKey": group_column,
        "seriesCount": len(ordering_by_series) if group_column else 1,
        "orderingBySeries": ordering_by_series,
    }


def _metric_summary(frame: pd.DataFrame, parsed: pd.Series, metric: str) -> dict[str, Any] | None:
    values = pd.to_numeric(frame[metric], errors="coerce")
    valid_mask = values.notna() & parsed.notna()
    if int(valid_mask.sum()) < 2:
        return None
    points = pd.DataFrame({"time": parsed[valid_mask], "value": values[valid_mask]}).sort_values("time")
    first = float(points.iloc[0]["value"])
    last = float(points.iloc[-1]["value"])
    change = last - first
    scale = max(abs(first), abs(last), float(points["value"].std(ddof=0) or 0.0), 1e-12)
    relative = change / scale
    trend = "stable" if abs(relative) < 0.01 else ("rising" if change > 0 else "falling")
    min_idx = points["value"].idxmin()
    max_idx = points["value"].idxmax()
    return {
        "metric": str(metric),
        "count": int(len(points)),
        "startValue": first,
        "endValue": last,
        "change": change,
        "minimum": float(points.loc[min_idx, "value"]),
        "minimumAt": _iso(points.loc[min_idx, "time"]),
        "maximum": float(points.loc[max_idx, "value"]),
        "maximumAt": _iso(points.loc[max_idx, "time"]),
        "mean": float(points["value"].mean()),
        "trend": trend,
    }


def _metric_texts(source_name: str, time_column: str, time_start: str, time_end: str,
                  summary: dict[str, Any], group_label: str | None = None) -> tuple[str, str]:
    prefix = f"文件 {source_name}"
    if group_label:
        prefix += f"，对象 {group_label}"
    trend_zh = {"rising": "上升", "falling": "下降", "stable": "基本稳定"}[summary["trend"]]
    trend_text = (
        f"{prefix}：按 {time_column} 从 {time_start} 到 {time_end}，"
        f"指标 {summary['metric']} 从 {_format_number(summary['startValue'])} 变化到 "
        f"{_format_number(summary['endValue'])}，总变化 {_format_number(summary['change'])}，趋势{trend_zh}。"
    )
    extrema_text = (
        f"{prefix}：指标 {summary['metric']} 在 {time_start} 到 {time_end} 期间，"
        f"最小值 {_format_number(summary['minimum'])} 出现于 {summary['minimumAt']}，"
        f"最大值 {_format_number(summary['maximum'])} 出现于 {summary['maximumAt']}。"
    )
    return trend_text, extrema_text


def describe_table(frame: pd.DataFrame, source_name: str, locator: dict[str, Any] | None = None,
                   max_window_rows: int = 200) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    """Describe a table without losing time order or time-window provenance."""
    frame = frame.copy()
    frame.columns = [str(column) for column in frame.columns]
    locator = dict(locator or {})
    column_types = {column: str(dtype) for column, dtype in frame.dtypes.items()}
    attributes: dict[str, Any] = {
        "rowCount": int(len(frame)),
        "columnCount": int(len(frame.columns)),
        "columns": list(frame.columns),
        "columnTypes": column_types,
        "missingValueCounts": {column: int(frame[column].isna().sum()) for column in frame.columns},
    }
    units: list[dict[str, Any]] = []
    time_column, parsed, confidence = detect_temporal_column(frame)
    if time_column is None or parsed is None:
        attributes["temporal"] = {"detected": False}
        preview = frame.head(20).fillna("").astype(str).to_dict(orient="records")
        units.append({
            "kind": "table-overview",
            "text": f"表格 {source_name}，共 {len(frame)} 行 {len(frame.columns)} 列，字段为：{'、'.join(frame.columns)}。样例：{json.dumps(preview, ensure_ascii=False)}",
            "locator": locator,
        })
        return attributes, units

    group_columns = [
        column for column in frame.columns
        if column != time_column
        and GROUP_NAME_RE.search(column)
        and 1 < frame[column].nunique(dropna=True) <= 50
    ]
    group_column = group_columns[0] if group_columns else None
    temporal = _temporal_metadata(frame, time_column, parsed, confidence, group_column)
    attributes["temporal"] = temporal
    units.append({
        "kind": "temporal-overview",
        "text": (
            f"时序表格 {source_name}，共 {len(frame)} 行 {len(frame.columns)} 列，时间列为 {time_column}，"
            f"时间范围从 {temporal['timeStart']} 到 {temporal['timeEnd']}，"
            f"原始顺序为 {temporal['ordering']}，采样间隔中位数为 {temporal['medianIntervalSeconds']} 秒，"
            f"采样{'不规则' if temporal['irregularSampling'] else '规则'}。字段：{'、'.join(frame.columns)}。"
        ),
        "locator": {**locator, "timeStart": temporal["timeStart"], "timeEnd": temporal["timeEnd"]},
    })

    numeric_columns = [
        column for column in frame.columns
        if column != time_column and pd.to_numeric(frame[column], errors="coerce").notna().sum() >= 2
    ][:16]
    work = frame.assign(__parsed_time=parsed).dropna(subset=["__parsed_time"])
    groups: Iterable[tuple[str | None, pd.DataFrame]]
    if group_column:
        groups = ((f"{group_column}={value}", part) for value, part in work.groupby(group_column, dropna=True, sort=False))
    else:
        groups = [(None, work)]

    temporal_metrics: list[dict[str, Any]] = []
    for group_label, group in groups:
        group = group.sort_values("__parsed_time", kind="stable")
        for offset in range(0, len(group), max_window_rows):
            window = group.iloc[offset:offset + max_window_rows]
            window_times = window["__parsed_time"]
            if window.empty:
                continue
            time_start = _iso(window_times.min())
            time_end = _iso(window_times.max())
            for metric in numeric_columns:
                summary = _metric_summary(window, window_times, metric)
                if summary is None:
                    continue
                metric_record = {
                    **summary,
                    "timeStart": time_start,
                    "timeEnd": time_end,
                    "group": group_label,
                }
                temporal_metrics.append(metric_record)
                unit_locator = {
                    **locator,
                    "timeColumn": time_column,
                    "timeStart": time_start,
                    "timeEnd": time_end,
                    "group": group_label,
                    "metric": metric,
                    "sortedByTime": True,
                }
                trend_text, extrema_text = _metric_texts(
                    source_name, time_column, time_start, time_end, summary, group_label
                )
                units.append({"kind": "temporal-trend", "text": trend_text, "locator": unit_locator})
                units.append({"kind": "temporal-extrema", "text": extrema_text, "locator": unit_locator})
    attributes["temporalMetrics"] = temporal_metrics
    return attributes, units


def _font(size: int = 18):
    candidates = [
        "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
        "C:/Windows/Fonts/arial.ttf",
    ]
    for candidate in candidates:
        if Path(candidate).exists():
            return ImageFont.truetype(candidate, size=size)
    return ImageFont.load_default()


def render_table_preview(frame: pd.DataFrame, target: Path, title: str) -> None:
    preview = frame.head(18).iloc[:, :8].fillna("").astype(str)
    width, row_height = 1400, 34
    height = max(180, (len(preview) + 3) * row_height)
    image = Image.new("RGB", (width, height), "white")
    draw = ImageDraw.Draw(image)
    font = _font(17)
    draw.text((20, 12), title[:120], fill="black", font=font)
    columns = list(preview.columns)
    cell_width = max(120, (width - 40) // max(1, len(columns)))
    y = 55
    for index, column in enumerate(columns):
        draw.rectangle((20 + index * cell_width, y, 20 + (index + 1) * cell_width, y + row_height), fill="#dce9f7", outline="#8090a0")
        draw.text((25 + index * cell_width, y + 7), str(column)[:18], fill="black", font=font)
    for row_index, (_, row) in enumerate(preview.iterrows(), start=1):
        y = 55 + row_index * row_height
        for column_index, value in enumerate(row):
            draw.rectangle((20 + column_index * cell_width, y, 20 + (column_index + 1) * cell_width, y + row_height), outline="#b8b8b8")
            draw.text((25 + column_index * cell_width, y + 7), str(value)[:18], fill="black", font=font)
    target.parent.mkdir(parents=True, exist_ok=True)
    image.save(target, format="PNG")


def _base_manifest(asset_id: str, logical_path: str, source_format: str) -> dict[str, Any]:
    return {
        "schemaVersion": "1.0",
        "assetId": asset_id,
        "sourceFormat": source_format,
        "sourcePath": logical_path,
        "title": Path(logical_path).name,
        "summary": "",
        "keywords": [],
        "attributes": {},
        "textUnits": [],
        "visualUnits": [],
    }


def _add_text_units(manifest: dict[str, Any], units: Iterable[dict[str, Any]]) -> None:
    for unit in units:
        sequence = len(manifest["textUnits"]) + 1
        manifest["textUnits"].append({
            "unitId": _unit_id(manifest["assetId"], "text", sequence),
            "kind": unit.get("kind", "text"),
            "text": str(unit.get("text", "")).strip(),
            "locator": _json_safe(unit.get("locator", {})),
        })


def _add_visual_unit(manifest: dict[str, Any], path: Path, output_dir: Path,
                     caption: str, kind: str, locator: dict[str, Any] | None = None) -> None:
    sequence = len(manifest["visualUnits"]) + 1
    manifest["visualUnits"].append({
        "unitId": _unit_id(manifest["assetId"], "visual", sequence),
        "kind": kind,
        "path": str(path),
        "outputPath": normalize_path(str(path.relative_to(output_dir))) if path.is_relative_to(output_dir) else None,
        "caption": caption,
        "locator": _json_safe(locator or {}),
    })


def _table_manifest(path: Path, logical_path: str, asset_id: str, output_dir: Path,
                    sheets: dict[str, pd.DataFrame], source_format: str) -> dict[str, Any]:
    manifest = _base_manifest(asset_id, logical_path, source_format)
    manifest["summary"] = f"{source_format} 表格文件，包含 {len(sheets)} 个数据表。"
    manifest["attributes"]["sheetCount"] = len(sheets)
    manifest["attributes"]["sheets"] = {}
    for sheet_index, (sheet_name, frame) in enumerate(sheets.items(), start=1):
        locator = {"sheet": sheet_name}
        attributes, units = describe_table(frame, path.name, locator)
        manifest["attributes"]["sheets"][sheet_name] = attributes
        _add_text_units(manifest, units)
        preview = output_dir / "representations" / asset_id / "visual" / f"sheet-{sheet_index:03d}.png"
        render_table_preview(frame, preview, f"{path.name} / {sheet_name}")
        _add_visual_unit(manifest, preview, output_dir, f"{path.name} 工作表 {sheet_name} 数据预览", "table-preview", locator)
    return manifest


def _json_tables(value: Any) -> dict[str, pd.DataFrame]:
    tables: dict[str, pd.DataFrame] = {}
    if isinstance(value, list) and value and all(isinstance(item, dict) for item in value):
        tables["root"] = pd.json_normalize(value)
    elif isinstance(value, dict):
        for key, child in value.items():
            if isinstance(child, list) and child and all(isinstance(item, dict) for item in child):
                tables[str(key)] = pd.json_normalize(child)
    return tables


def _pdf_manifest(path: Path, logical_path: str, asset_id: str, output_dir: Path) -> dict[str, Any]:
    manifest = _base_manifest(asset_id, logical_path, "PDF")
    try:
        import fitz  # PyMuPDF
        document = fitz.open(path)
        manifest["attributes"]["pageCount"] = document.page_count
        for page_index, page in enumerate(document, start=1):
            text = page.get_text("text").strip()
            if text:
                _add_text_units(manifest, ({"kind": "pdf-page-text", "text": chunk, "locator": {"page": page_index}} for chunk in _chunks(text)))
            preview = output_dir / "representations" / asset_id / "visual" / f"page-{page_index:04d}.png"
            preview.parent.mkdir(parents=True, exist_ok=True)
            page.get_pixmap(matrix=fitz.Matrix(1.4, 1.4), alpha=False).save(preview)
            _add_visual_unit(manifest, preview, output_dir, f"{path.name} 第 {page_index} 页", "pdf-page", {"page": page_index})
        manifest["summary"] = f"PDF 文档 {path.name}，共 {document.page_count} 页。"
        document.close()
    except Exception as exc:
        manifest["attributes"]["conversionWarning"] = str(exc)
        manifest["summary"] = f"PDF 文档 {path.name}，页面转换失败，仅可通过文件名检索。"
        _add_text_units(manifest, [{"kind": "file-fallback", "text": manifest["summary"], "locator": {}}])
    return manifest


def _obj_manifest(path: Path, logical_path: str, asset_id: str, output_dir: Path) -> dict[str, Any]:
    manifest = _base_manifest(asset_id, logical_path, "OBJ")
    vertices: list[tuple[float, float, float]] = []
    face_count = 0
    material_names: set[str] = set()
    for line in _read_text(path).splitlines():
        if line.startswith("v "):
            parts = line.split()
            if len(parts) >= 4:
                try:
                    vertices.append((float(parts[1]), float(parts[2]), float(parts[3])))
                except ValueError:
                    pass
        elif line.startswith("f "):
            face_count += 1
        elif line.startswith("usemtl "):
            material_names.add(line[7:].strip())
    bounds = None
    if vertices:
        array = np.asarray(vertices, dtype=float)
        minimum, maximum = array.min(axis=0), array.max(axis=0)
        bounds = {"minimum": minimum.tolist(), "maximum": maximum.tolist(), "size": (maximum - minimum).tolist()}
    manifest["attributes"] = {"vertexCount": len(vertices), "faceCount": face_count, "materials": sorted(material_names), "bounds": bounds}
    manifest["summary"] = f"OBJ 三维模型 {path.name}，包含 {len(vertices)} 个顶点、{face_count} 个面，材质 {len(material_names)} 种。"
    _add_text_units(manifest, [{"kind": "geometry-summary", "text": manifest["summary"] + (f"包围盒尺寸为 {bounds['size']}。" if bounds else ""), "locator": {}}])
    if vertices:
        points = np.asarray(vertices, dtype=float)
        for name, axes in (("front", (0, 1)), ("side", (1, 2)), ("top", (0, 2))):
            projected = points[:, axes]
            lo, hi = projected.min(axis=0), projected.max(axis=0)
            span = np.maximum(hi - lo, 1e-9)
            xy = (projected - lo) / span
            image = Image.new("RGB", (768, 768), "white")
            draw = ImageDraw.Draw(image)
            sample_step = max(1, len(xy) // 20000)
            for x, y in xy[::sample_step]:
                px, py = int(40 + x * 688), int(728 - y * 688)
                draw.ellipse((px - 1, py - 1, px + 1, py + 1), fill="#1f5f99")
            preview = output_dir / "representations" / asset_id / "visual" / f"view-{name}.png"
            preview.parent.mkdir(parents=True, exist_ok=True)
            image.save(preview)
            _add_visual_unit(manifest, preview, output_dir, f"{path.name} {name} 标准投影视图", "model-view", {"view": name})
    return manifest


def _step_manifest(path: Path, logical_path: str, asset_id: str) -> dict[str, Any]:
    manifest = _base_manifest(asset_id, logical_path, "STEP")
    content = _read_text(path)
    entity_counts = Counter(re.findall(r"=\s*([A-Z0-9_]+)\s*\(", content))
    products = [name for name in re.findall(r"PRODUCT\s*\(\s*'([^']*)'", content, re.IGNORECASE) if name]
    top_entities = dict(entity_counts.most_common(20))
    manifest["attributes"] = {"products": products[:100], "entityCounts": top_entities}
    product_text = "、".join(products[:20]) if products else "未标注产品名称"
    manifest["summary"] = f"STEP 工程模型 {path.name}，产品或零件：{product_text}。主要实体类型：{top_entities}。"
    _add_text_units(manifest, [{"kind": "cad-summary", "text": manifest["summary"], "locator": {}}])
    return manifest


def build_asset_representation(path: Path, input_dir: Path, output_dir: Path,
                               dataset_version_id: str, captions: dict[str, list[str]] | None = None) -> dict[str, Any]:
    logical_path = normalize_path(path.relative_to(input_dir).as_posix())
    asset_id = deterministic_asset_id(dataset_version_id, logical_path)
    extension = path.suffix.lower()
    source_format = "JPG" if extension == ".jpeg" else extension.lstrip(".").upper()
    captions = captions or {}

    if extension in {".csv", ".jsonl", ".xlsx"}:
        if extension == ".csv":
            frame = pd.read_csv(path, encoding_errors="replace", low_memory=False)
            return _table_manifest(path, logical_path, asset_id, output_dir, {"root": frame}, "CSV")
        if extension == ".jsonl":
            records = [json.loads(line) for line in _read_text(path).splitlines() if line.strip()]
            frame = pd.json_normalize(records)
            return _table_manifest(path, logical_path, asset_id, output_dir, {"root": frame}, "JSONL")
        sheets = pd.read_excel(path, sheet_name=None)
        return _table_manifest(path, logical_path, asset_id, output_dir, sheets, "XLSX")

    if extension == ".json":
        value = json.loads(_read_text(path))
        tables = _json_tables(value)
        if tables:
            return _table_manifest(path, logical_path, asset_id, output_dir, tables, "JSON")
        manifest = _base_manifest(asset_id, logical_path, "JSON")
        serialized = json.dumps(value, ensure_ascii=False, indent=2)
        manifest["summary"] = f"JSON 文件 {path.name}。"
        _add_text_units(manifest, ({"kind": "json-content", "text": chunk, "locator": {"characterOffset": index * 620}} for index, chunk in enumerate(_chunks(serialized))))
        return manifest

    if extension == ".txt":
        manifest = _base_manifest(asset_id, logical_path, "TXT")
        text = _read_text(path)
        manifest["summary"] = f"文本文件 {path.name}，共 {len(text)} 个字符。"
        _add_text_units(manifest, ({"kind": "text-chunk", "text": chunk, "locator": {"chunk": index + 1}} for index, chunk in enumerate(_chunks(text))))
        return manifest

    if extension == ".pdf":
        return _pdf_manifest(path, logical_path, asset_id, output_dir)
    if extension == ".obj":
        return _obj_manifest(path, logical_path, asset_id, output_dir)
    if extension == ".step":
        return _step_manifest(path, logical_path, asset_id)

    manifest = _base_manifest(asset_id, logical_path, source_format)
    if extension in {".jpg", ".jpeg", ".png"}:
        description = captions.get(path.name, [])
        manifest["summary"] = description[0] if description else f"图像文件 {path.name}。"
        _add_text_units(manifest, [{"kind": "image-caption", "text": text, "locator": {}} for text in (description or [manifest["summary"]])])
        _add_visual_unit(manifest, path, output_dir, manifest["summary"], "source-image")
        return manifest
    if extension == ".svg":
        manifest["summary"] = f"SVG 矢量图 {path.name}。"
        _add_text_units(manifest, [{"kind": "image-caption", "text": manifest["summary"], "locator": {}}])
        try:
            import cairosvg
            preview = output_dir / "representations" / asset_id / "visual" / "source.png"
            preview.parent.mkdir(parents=True, exist_ok=True)
            cairosvg.svg2png(url=str(path), write_to=str(preview), output_width=1024, output_height=1024)
            _add_visual_unit(manifest, preview, output_dir, manifest["summary"], "source-image")
        except Exception as exc:
            manifest["attributes"]["conversionWarning"] = str(exc)
        return manifest
    raise ValueError(f"unsupported source format: {extension}")


def load_coco_captions(input_dir: Path) -> tuple[dict[str, list[str]], set[Path]]:
    captions: dict[str, list[str]] = {}
    metadata_files: set[Path] = set()
    for path in input_dir.rglob("*.json"):
        try:
            value = json.loads(_read_text(path))
        except Exception:
            continue
        if not isinstance(value, dict) or not isinstance(value.get("images"), list) or not isinstance(value.get("annotations"), list):
            continue
        image_names = {item.get("id"): item.get("file_name") for item in value["images"] if isinstance(item, dict)}
        for annotation in value["annotations"]:
            if not isinstance(annotation, dict):
                continue
            name = image_names.get(annotation.get("image_id"))
            caption = annotation.get("caption")
            if name and caption:
                captions.setdefault(Path(str(name)).name, []).append(str(caption).strip())
        metadata_files.add(path.resolve())
    return captions, metadata_files


def build_representations(input_dir: Path, output_dir: Path, dataset_version_id: str) -> RepresentationBundle:
    captions, metadata_files = load_coco_captions(input_dir)
    manifests: list[dict[str, Any]] = []
    for path in sorted(input_dir.rglob("*")):
        if not path.is_file() or path.resolve() in metadata_files:
            continue
        relative_parts = {part.lower() for part in path.relative_to(input_dir).parts}
        if "indexes" in relative_parts or "representations" in relative_parts or path.name.lower() in INTERNAL_NAMES:
            continue
        if path.suffix.lower() not in SUPPORTED_EXTENSIONS:
            continue
        manifests.append(build_asset_representation(path, input_dir, output_dir, dataset_version_id, captions))

    text_units: list[dict[str, Any]] = []
    visual_units: list[dict[str, Any]] = []
    manifest_dir = output_dir / "representations" / "manifests"
    manifest_dir.mkdir(parents=True, exist_ok=True)
    for manifest in manifests:
        manifest_path = manifest_dir / f"{manifest['assetId']}.json"
        manifest_path.write_text(json.dumps(_json_safe(manifest), ensure_ascii=False, indent=2), encoding="utf-8")
        for unit in manifest["textUnits"]:
            if unit["text"]:
                text_units.append({
                    "asset_id": manifest["assetId"],
                    "logical_path": manifest["sourcePath"],
                    "source_format": manifest["sourceFormat"],
                    "representation_id": unit["unitId"],
                    "representation_type": "TEXT",
                    "kind": unit["kind"],
                    "text": unit["text"],
                    "locator": json.dumps(unit["locator"], ensure_ascii=False),
                    "manifest_path": normalize_path(str(manifest_path.relative_to(output_dir))),
                })
        for unit in manifest["visualUnits"]:
            visual_units.append({
                "asset_id": manifest["assetId"],
                "logical_path": manifest["sourcePath"],
                "source_format": manifest["sourceFormat"],
                "representation_id": unit["unitId"],
                "representation_type": "IMAGE",
                "kind": unit["kind"],
                "text": unit["caption"],
                "locator": json.dumps(unit["locator"], ensure_ascii=False),
                "manifest_path": normalize_path(str(manifest_path.relative_to(output_dir))),
                "actual_path": Path(unit["path"]),
                "preview_path": unit["outputPath"] or manifest["sourcePath"],
            })
    return RepresentationBundle(manifests, text_units, visual_units)
