from __future__ import annotations

import json

from datetime import timezone
from typing import Any
from zoneinfo import ZoneInfo

import numpy as np
import pandas as pd

from .contracts import ORGANIZER_VERSION, ParsedAsset, SemanticUnit, stable_id,organizer_version
from .json_native import is_record_number,is_encoded_number,number_metadata


TIME_NAMES = ("time", "timestamp", "datetime", "date", "日期", "时间", "采集时间", "记录时间")
ENTITY_NAMES = ("device", "device_id", "equipment", "entity", "sensor", "part_id", "设备", "测点", "零件")
METRIC_UNIT_SUFFIXES = (
    ("_kwh", "kWh"), ("_wh", "Wh"), ("_percent", "%"), ("_pct", "%"),
    ("_celsius", "°C"), ("_degc", "°C"), ("_mm", "mm"), ("_cm", "cm"),
    ("_km", "km"), ("_min", "min"), ("_ms", "ms"), ("_hour", "h"),
    ("_hours", "h"), ("_sec", "s"), ("_seconds", "s"), ("_c", "°C"), ("_w", "W"),
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
                 duplicate_policy: str = "deduplicate-identical-preserve-conflicts", interval_boundary: str = "[start,end)") -> list[SemanticUnit]:
        timezone_name = asset.attributes.get("sourceTimezone") or timezone_name
        units: list[SemanticUnit] = []
        for sequence, item in enumerate(asset.items, start=1):
            unit_type = {"text": "TextUnit", "visual": "VisualUnit", "table": "TableUnit", "geometry": "GeometryUnit"}[item.kind]
            base_unit=self._unit(asset, item, unit_type, sequence)
            units.append(base_unit)
            if item.kind == "table":
                records = item.payload.get("records", [])
                # Each business row remains independently locatable. A file-wide
                # sample of its first three rows is not evidence for other rows.
                measurement = bool(records and "series_id" in records[0]["values"] and "timestamp" in records[0]["values"])
                if not measurement and not asset.logical_path.startswith("/catalog/"):
                    for record in records:
                        row = type(item)("text", json.dumps(record["values"], ensure_ascii=False),
                            {**item.locator, **record.get("locator", {}), "rawRow": record.get("rawRow")}, units=dict(item.units),
                            quality_flags=item.quality_flags)
                        row_unit=self._unit(asset, row, "TextUnit", sequence)
                        row_unit.metadata['tableId']=base_unit.unit_id
                        row_unit.metadata['sourceRecord']={key:record[key] for key in
                            ('sourceValues','numberLexemes','formulas','calculationEligibility','valueTransforms') if key in record}
                        units.append(row_unit)
                from .source_timezone import resolve as resolve_timezone
                time_policy=item.payload.get('sourceTimePolicy',{})
                item_timezone,time_flags,time_usable=resolve_timezone(time_policy,timezone_name)
                base_unit.quality_flags=sorted(set([*base_unit.quality_flags,*time_flags]))
                if time_policy:base_unit.metadata['sourceTimePolicy']=time_policy
                if time_policy:base_unit.metadata['timeInterpretation']={'status':'POLICY_RESOLVED' if item_timezone else 'NOT_DECLARED',
                    'sourceTimezone':item_timezone,'qualityFlags':time_flags,'rawRecordsPreserved':True}
                if not time_usable:
                    base_unit.metadata['timeInterpretation']={'status':'UNAVAILABLE','qualityFlags':time_flags,'rawRecordsPreserved':True}
                column, parsed, confidence, naive_time_count = _time_column(records,time_column,item_timezone) if time_usable else (None,None,0.0,0)
                if time_usable and time_policy and column is None and records and any(
                        str(key).lower() in TIME_NAMES or key==time_column for key in records[0]['values']):
                    base_unit.quality_flags=sorted(set([*base_unit.quality_flags,'TIME_COLUMN_INTERPRETATION_UNAVAILABLE']))
                    base_unit.metadata['timeInterpretation']={'status':'UNAVAILABLE','qualityFlags':['TIME_COLUMN_INTERPRETATION_UNAVAILABLE'],'rawRecordsPreserved':True}
                if column and parsed is not None:
                    invalid_time_count = sum(pd.isna(value) for value in parsed)
                    blocked=[]
                    if time_policy and naive_time_count and not item_timezone:blocked.append('SOURCE_TIMEZONE_UNSPECIFIED')
                    if time_policy and invalid_time_count:blocked.append('INVALID_TIME_PRESERVED_IN_TABLE_UNIT')
                    if blocked:
                        base_unit.quality_flags=sorted(set([*base_unit.quality_flags,*blocked]))
                        base_unit.metadata['timeInterpretation']={'status':'UNAVAILABLE','qualityFlags':blocked,
                            'naiveTimeCount':naive_time_count,'invalidTimeCount':invalid_time_count,'rawRecordsPreserved':True}
                        continue
                    entity_keys = (["series_id"] if records and "series_id" in records[0]["values"]
                                   else _entity_columns(records, entity_columns or []))
                    facts = []
                    for record, timestamp in zip(records, parsed):
                        if pd.isna(timestamp):
                            continue
                        values = record["values"]
                        # Tables without an explicit device column remain
                        # distinct series by using their immutable asset ID.
                        entity = "|".join(str(values.get(key, "")) for key in entity_keys) or asset.asset_id
                        for metric, value in values.items():
                            numeric=is_record_number(record,metric)
                            source_eligible=record.get('calculationEligibility',{}).get(metric,True)
                            if metric == column or metric in entity_keys or (not numeric and source_eligible):
                                continue
                            fact_unit = _metric_unit(str(metric), item.units)
                            decoded=None;binary_range_problem=False
                            if numeric:
                                try:decoded=None if is_encoded_number(value,number_metadata(record,metric)) else float(value)
                                except OverflowError:decoded=None
                                binary_range_problem=decoded is None or not np.isfinite(decoded)
                                if binary_range_problem:decoded=None
                            facts.append({
                                "entity": entity, "metric": metric, "value": decoded,
                                "unit": fact_unit, "rawTime": str(values.get(column)),
                                "normalizedTime": timestamp.astimezone(timezone.utc).isoformat().replace("+00:00", "Z"),
                                "rawRow": record.get("rawRow"),
                                "locator": {**item.locator, **record.get("locator", {}), "rawRow": record.get("rawRow")},
                                "calculationEligible": not binary_range_problem and record.get('calculationEligibility',{}).get(metric,True) and record.get('calculationEligibility',{}).get(column,True),
                                "numberLexemes":record.get('numberLexemes',{}),
                                "recordValues": values,
                                "qualityFlags": ([] if fact_unit else ["UNIT_MAPPING_REQUIRED"])+(['SOURCE_NUMBER_OUTSIDE_TIMESERIES_BINARY_RANGE'] if binary_range_problem else []),
                            })
                    flags = list(item.quality_flags)
                    flags.extend(time_flags)
                    if invalid_time_count:
                        flags.append("INVALID_TIME_PRESERVED_IN_TABLE_UNIT")
                    if naive_time_count and not item_timezone:
                        flags.append("SOURCE_TIMEZONE_UNSPECIFIED")
                    temporal = type(item)("timeseries", {
                        "timeColumn": column, "timeDetectionConfidence": confidence,
                        "sourceTimezone": item_timezone, "facts": facts,
                        "sourceTimePolicy":time_policy,
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
            organizer_version=organizer_version(asset.source_format),
            payload=item.payload,
        )
