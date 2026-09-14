from __future__ import annotations

from datetime import timezone
from typing import Any
from zoneinfo import ZoneInfo

import numpy as np
import pandas as pd

from .contracts import ORGANIZER_VERSION, ParsedAsset, SemanticUnit, stable_id


TIME_NAMES = ("time", "timestamp", "datetime", "date", "日期", "时间", "采集时间", "记录时间")
ENTITY_NAMES = ("device", "device_id", "equipment", "entity", "sensor", "part_id", "设备", "测点", "零件")
METRIC_UNIT_SUFFIXES = (
    ("_kwh", "kWh"), ("_wh", "Wh"), ("_percent", "%"), ("_pct", "%"),
    ("_celsius", "°C"), ("_degc", "°C"), ("_mm", "mm"), ("_cm", "cm"),
    ("_km", "km"), ("_min", "min"), ("_ms", "ms"), ("_hour", "h"),
    ("_hours", "h"), ("_sec", "s"), ("_seconds", "s"), ("_c", "°C"),
)


def _parse_time_value(value: Any) -> pd.Timestamp:
    """Parse explicit timestamp encodings without treating arbitrary numbers as ns."""
    if isinstance(value, (int, float, np.integer, np.floating)) and not isinstance(value, bool):
        numeric = float(value)
        if not np.isfinite(numeric):
            raise ValueError("non-finite time")
        magnitude = abs(numeric)
        if 20_000 <= magnitude <= 80_000:  # Excel 1900 date system
            return pd.Timestamp("1899-12-30") + pd.to_timedelta(numeric, unit="D")
        if magnitude >= 100_000_000_000:
            return pd.to_datetime(numeric, unit="ms")
        if magnitude >= 1_000_000_000:
            return pd.to_datetime(numeric, unit="s")
        raise ValueError("ambiguous numeric time requires mapping")
    return pd.Timestamp(value)


def _metric_unit(metric: str, configured: dict[str, str]) -> str | None:
    if configured.get(metric):
        return configured[metric]
    lowered = metric.lower()
    return next((unit for suffix, unit in METRIC_UNIT_SUFFIXES if lowered.endswith(suffix)), None)


def _time_column(records: list[dict], configured: str | None = None, timezone_name: str | None = None):
    if not records:
        return None, None, 0.0, 0
    frame = pd.DataFrame([row["values"] for row in records])
    names = {str(column).lower(): str(column) for column in frame.columns}
    candidates = [configured] if configured else [names[key] for key in TIME_NAMES if key in names]
    for column in candidates:
        if not column or column not in frame:
            continue
        parsed, naive_count = [], 0
        for value in frame[column]:
            try:
                timestamp = _parse_time_value(value)
                if pd.isna(timestamp):
                    raise ValueError("invalid time")
                if timestamp.tzinfo is None:
                    naive_count += 1
                    timestamp = timestamp.tz_localize(ZoneInfo(timezone_name) if timezone_name else timezone.utc)
                parsed.append(timestamp.tz_convert(timezone.utc))
            except Exception:
                parsed.append(pd.NaT)
        confidence = sum(not pd.isna(value) for value in parsed) / max(1, len(parsed))
        if confidence >= 0.75:
            return column, parsed, confidence, naive_count
    return None, None, 0.0, 0


def _entity_columns(records: list[dict], configured: list[str]) -> list[str]:
    if not records:
        return []
    columns = {str(key).lower(): str(key) for key in records[0]["values"]}
    return [column for column in configured if column in columns.values()] or [columns[key] for key in ENTITY_NAMES if key in columns]


class SemanticOrganizer:
    version = ORGANIZER_VERSION

    def organize(self, asset: ParsedAsset, *, timezone_name: str | None = None,
                 time_column: str | None = None, entity_columns: list[str] | None = None,
                 duplicate_policy: str = "keep-last-by-source-row", interval_boundary: str = "[start,end)") -> list[SemanticUnit]:
        units: list[SemanticUnit] = []
        for sequence, item in enumerate(asset.items, start=1):
            unit_type = {"text": "TextUnit", "visual": "VisualUnit", "table": "TableUnit", "geometry": "GeometryUnit"}[item.kind]
            units.append(self._unit(asset, item, unit_type, sequence))
            if item.kind == "table":
                records = item.payload.get("records", [])
                summary = type(item)(
                    "text",
                    "字段: " + "、".join(str(column.get("name")) for column in item.payload.get("columns", []))
                    + "；样例: " + str([record.get("values", {}) for record in records[:3]]),
                    dict(item.locator),
                    units=item.units,
                    quality_flags=item.quality_flags,
                )
                units.append(self._unit(asset, summary, "TextUnit", sequence))
                column, parsed, confidence, naive_time_count = _time_column(records, time_column, timezone_name)
                if column and parsed is not None:
                    entity_keys = _entity_columns(records, entity_columns or [])
                    facts = []
                    for record, timestamp in zip(records, parsed):
                        if pd.isna(timestamp):
                            continue
                        values = record["values"]
                        # Tables without an explicit device column remain
                        # distinct series by using their immutable asset ID.
                        entity = "|".join(str(values.get(key, "")) for key in entity_keys) or asset.asset_id
                        for metric, value in values.items():
                            if metric == column or metric in entity_keys or not isinstance(value, (int, float)) or isinstance(value, bool):
                                continue
                            fact_unit = _metric_unit(str(metric), item.units)
                            facts.append({
                                "entity": entity, "metric": metric, "value": float(value),
                                "unit": fact_unit, "rawTime": str(values.get(column)),
                                "normalizedTime": timestamp.astimezone(timezone.utc).isoformat().replace("+00:00", "Z"),
                                "rawRow": record.get("rawRow"),
                                "qualityFlags": [] if fact_unit else ["UNIT_MAPPING_REQUIRED"],
                            })
                    flags = list(item.quality_flags)
                    invalid_time_count = sum(pd.isna(value) for value in parsed)
                    if invalid_time_count:
                        flags.append("INVALID_TIME_PRESERVED_IN_TABLE_UNIT")
                    if naive_time_count and not timezone_name:
                        flags.append("SOURCE_TIMEZONE_UNSPECIFIED")
                    temporal = type(item)("timeseries", {
                        "timeColumn": column, "timeDetectionConfidence": confidence,
                        "sourceTimezone": timezone_name, "facts": facts,
                        "invalidTimeCount": invalid_time_count,
                        "duplicatePolicy": duplicate_policy, "intervalBoundary": interval_boundary,
                        "missingPolicy": "preserve", "interpolation": "none", "outlierPolicy": "preserve",
                    }, dict(item.locator), quality_flags=sorted(set(flags)))
                    units.append(self._unit(asset, temporal, "TimeSeriesUnit", sequence))
        return units

    def _unit(self, asset: ParsedAsset, item, unit_type: str, sequence: int) -> SemanticUnit:
        locator_key = str(sorted(item.locator.items()))
        return SemanticUnit(
            unit_id=stable_id(asset.asset_id, unit_type, str(sequence), locator_key),
            unit_type=unit_type,
            asset_id=asset.asset_id,
            dataset_version_id=asset.dataset_version_id,
            logical_path=asset.logical_path,
            content_sha256=asset.content_sha256,
            locator=item.locator,
            entity_id=item.entity_id,
            units=item.units,
            quality_flags=sorted(set(asset.quality_flags + item.quality_flags)),
            parser_name=asset.parser_name,
            parser_version=asset.parser_version,
            parser_config_version=asset.parser_config_version,
            organizer_version=self.version,
            payload=item.payload,
        )
