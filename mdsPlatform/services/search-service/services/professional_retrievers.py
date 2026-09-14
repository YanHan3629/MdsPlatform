from __future__ import annotations

import hashlib
import gzip
import json
import math
import time
import uuid
from bisect import bisect_left, bisect_right
from collections import Counter, defaultdict
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable

import faiss
import numpy as np

from schemas.professional import Evidence, EvidencePack, HardCondition, QueryPlan
from services.text_embedding import MODEL_DIMENSION, MODEL_VERSION, encode_query


def _tokens(text: str) -> list[str]:
    import re
    compact = "".join(text.lower().split())
    words = re.findall(r"[a-z0-9_]+|[\u4e00-\u9fff]", text.lower())
    return words + [compact[index:index + size] for size in (2, 3) for index in range(max(0, len(compact) - size + 1))]


def _hash_vector(text: str, dimension: int) -> np.ndarray:
    vector = np.zeros(dimension, dtype="float32")
    for token in _tokens(text):
        digest = hashlib.blake2b(token.encode("utf-8"), digest_size=8).digest()
        vector[int.from_bytes(digest[:4], "little") % dimension] += 1.0 if digest[4] & 1 else -1.0
    norm = float(np.linalg.norm(vector))
    return vector / norm if norm else vector


def _iso(value: str) -> datetime:
    normalized = str(value).strip().replace("Z", "+00:00")
    parsed = datetime.fromisoformat(normalized)
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    return parsed.astimezone(timezone.utc)


def _nested(value: Any, field: str):
    if isinstance(value, dict) and field in value:
        return value[field]
    current = value
    for part in field.split("."):
        if isinstance(current, dict):
            current = current.get(part)
        elif isinstance(current, (list, tuple)) and part.isdigit() and int(part) < len(current):
            current = current[int(part)]
        else:
            return None
    return current


def _detect_exact_conflicts(evidence: list[Evidence]) -> list[dict[str, Any]]:
    """Report only comparable, exact numeric facts; similarity scores are not facts."""
    groups: dict[tuple, list[Evidence]] = defaultdict(list)
    for item in evidence:
        value = item.facts.get("value")
        subject = item.entityId or item.assetId
        if (item.evidenceType != "calculation" or not subject or
                not isinstance(value, (int, float)) or isinstance(value, bool) or
                not math.isfinite(value)):
            continue
        key = (subject, item.facts.get("operation"), item.facts.get("metric") or item.facts.get("field"),
               json.dumps(item.timeRange, sort_keys=True), json.dumps(item.units, sort_keys=True),
               json.dumps(item.calculationPolicy, sort_keys=True))
        groups[key].append(item)
    conflicts = []
    for key, items in groups.items():
        if len(items) < 2:
            continue
        reference = float(items[0].facts["value"])
        if all(math.isclose(reference, float(item.facts["value"]), rel_tol=1e-6, abs_tol=1e-9)
               for item in items[1:]):
            continue
        conflicts.append({"kind": "EXACT_VALUE_DISAGREEMENT", "entityId": key[0],
                          "operation": key[1], "metricOrField": key[2],
                          "timeRange": items[0].timeRange,
                          "values": [{"evidenceId": item.evidenceId, "value": item.facts["value"]}
                                     for item in items]})
        if len(conflicts) >= 20:
            break
    return conflicts


UNIT_FACTORS = {
    "mm": ("length", 0.001), "cm": ("length", 0.01), "m": ("length", 1.0),
    "毫米": ("length", 0.001), "厘米": ("length", 0.01), "米": ("length", 1.0),
    "s": ("time", 1.0), "min": ("time", 60.0), "h": ("time", 3600.0),
    "mm2": ("area", 1e-6), "cm2": ("area", 1e-4), "m2": ("area", 1.0),
    "mm3": ("volume", 1e-9), "cm3": ("volume", 1e-6), "m3": ("volume", 1.0),
}


def _convert(value: Any, source_unit: str | None, target_unit: str | None):
    aliases = {"°C": "C", "℃": "C", "摄氏度": "C", "开尔文": "K", "mm²": "mm2", "cm²": "cm2", "m²": "m2",
               "mm³": "mm3", "cm³": "cm3", "m³": "m3"}
    source_unit, target_unit = aliases.get(source_unit, source_unit), aliases.get(target_unit, target_unit)
    if not target_unit or source_unit == target_unit:
        return value
    if not source_unit:
        raise ValueError("UNIT_UNKNOWN")
    if source_unit in {"°C", "C", "℃"} and target_unit in {"K", "开尔文"}:
        return float(value) + 273.15
    if source_unit in {"K", "开尔文"} and target_unit in {"°C", "C", "℃"}:
        return float(value) - 273.15
    source, target = UNIT_FACTORS.get(source_unit), UNIT_FACTORS.get(target_unit)
    if not source or not target or source[0] != target[0]:
        raise ValueError("UNIT_INCOMPATIBLE")
    return float(value) * source[1] / target[1]


def _matches(value: Any, condition: HardCondition, source_unit: str | None = None) -> bool:
    if value is None:
        return False
    try:
        value = _convert(value, source_unit, condition.unit)
        target = condition.value
        if condition.op == "eq": return value == target
        if condition.op == "in": return value in target
        if condition.op == "gt": return value > target
        if condition.op == "gte": return value >= target
        if condition.op == "lt": return value < target
        if condition.op == "lte": return value <= target
        if condition.op == "between": return target[0] <= value <= target[1]
    except (TypeError, ValueError, IndexError):
        return False
    return False


def _geometry_unit(units: dict, field: str) -> str | None:
    if field in units:
        return units[field]
    if field.startswith("bounds."):
        return units.get("length")
    if field == "surfaceArea":
        return units.get("area")
    if field == "volume":
        return units.get("volume")
    return None


def _evidence_id(space: str, asset_id: str | None, locator: dict, facts: dict) -> str:
    return str(uuid.uuid5(uuid.NAMESPACE_URL, json.dumps([space, asset_id, locator, facts], sort_keys=True, ensure_ascii=False, default=str)))


def _evidence(*, version: str, index_version: str, space: str, asset_id: str | None,
              locator: dict, facts: dict, score: float | None, score_type: str,
              rank=0, entity=None, time_range=None, units=None, policy=None, flags=None,
              preview=None, evidence_type="retrieval") -> Evidence:
    return Evidence(
        assetId=asset_id, dataVersion=version, evidenceId=_evidence_id(space, asset_id, locator, facts),
        semanticSpace=space, indexVersion=index_version, locator=locator, entityId=entity,
        timeRange=time_range, rank=rank, rawScore=score, scoreType=score_type, facts=facts,
        units=units or {}, calculationPolicy=policy or {},
        provenance={"dataVersion": version, "indexVersion": index_version},
        qualityFlags=sorted(set(flags or [])), preview=preview, evidenceType=evidence_type,
    )


class TextRetriever:
    def __init__(self, lexical_path: Path | None, dense_path: Path | None, dense_meta_path: Path | None,
                 dense_model_version: str | None = None):
        self.lexical = json.loads(lexical_path.read_text(encoding="utf-8")) if lexical_path else None
        self.dense = faiss.read_index(str(dense_path)) if dense_path else None
        self.dense_meta = json.loads(dense_meta_path.read_text(encoding="utf-8")) if dense_meta_path else None
        self.dense_model_version = dense_model_version
        if self.dense is not None and dense_model_version == MODEL_VERSION and self.dense.d != MODEL_DIMENSION:
            raise ValueError("Registered Chinese text model and FAISS dimension disagree")
        if self.dense is not None and dense_model_version not in {None, MODEL_VERSION, "cpu-hashed-char-ngram-1.0"}:
            raise ValueError("Unsupported Chinese text embedding model version")

    def search(self, plan: QueryPlan, version: str, index_version: str) -> list[Evidence]:
        ranks: dict[int, float] = defaultdict(float)
        raw: dict[int, dict[str, float]] = defaultdict(dict)
        if self.lexical:
            tokens = Counter(_tokens(plan.query_text))
            docs = self.lexical["documents"]
            avgdl = self.lexical.get("averageDocumentLength") or 1.0
            lengths = self.lexical["documentLengths"]
            postings = self.lexical["postings"]
            scores = defaultdict(float)
            for token, query_tf in tokens.items():
                posting = postings.get(token, {})
                idf = math.log(1 + (len(docs) - len(posting) + 0.5) / (len(posting) + 0.5))
                for doc_id, tf in posting.items():
                    doc_id = int(doc_id)
                    denominator = tf + 1.5 * (1 - .75 + .75 * lengths[doc_id] / avgdl)
                    scores[doc_id] += query_tf * idf * tf * 2.5 / denominator
            candidate_count = min(len(docs), max(plan.top_k * (10 if plan.target_entity else 3), 50 if plan.target_entity else 3))
            for rank, (doc_id, score) in enumerate(sorted(scores.items(), key=lambda item: item[1], reverse=True)[:candidate_count], 1):
                ranks[doc_id] += 1 / (60 + rank)
                raw[doc_id]["bm25"] = score
        if self.dense is not None and self.dense.ntotal:
            query = (encode_query(plan.query_text) if self.dense_model_version == MODEL_VERSION
                     else _hash_vector(plan.query_text, self.dense.d).reshape(1, -1))
            candidate_count = min(self.dense.ntotal, max(plan.top_k * (10 if plan.target_entity else 3),
                                                          50 if plan.target_entity else 3))
            scores, ids = self.dense.search(query, candidate_count)
            for rank, (doc_id, score) in enumerate(zip(ids[0], scores[0]), 1):
                if doc_id < 0: continue
                ranks[int(doc_id)] += 1 / (60 + rank)
                raw[int(doc_id)]["cosine"] = float(score)
        documents = (self.dense_meta or self.lexical or {}).get("documents", [])
        result = []
        for doc_id, fused in sorted(ranks.items(), key=lambda item: item[1], reverse=True):
            document = documents[doc_id]
            unit = document["unit"]
            if plan.target_entity and unit.get("entity_id") != plan.target_entity:
                continue
            rank = len(result) + 1
            result.append(_evidence(version=version, index_version=index_version, space="text.hybrid.zh", asset_id=unit["asset_id"],
                                    locator=unit["locator"], facts={"text": document["text"], "channelScores": raw[doc_id]},
                                    score=fused, score_type="weighted_rrf", rank=rank, entity=unit.get("entity_id"), units=unit.get("units"),
                                    flags=unit.get("quality_flags"), preview={"logicalPath": unit["logical_path"]}))
            if len(result) >= plan.top_k:
                break
        return result


class StructuredRetriever:
    def __init__(self, records_path: Path | None, field_index_path: Path | None = None):
        self.rows = [json.loads(line) for line in records_path.read_text(encoding="utf-8").splitlines() if line] if records_path else []
        self.field_index = None
        if field_index_path is not None:
            with gzip.open(field_index_path, "rt", encoding="utf-8") as stream:
                self.field_index = json.load(stream)
        if self.field_index and self.field_index.get("rowCount") != len(self.rows):
            raise ValueError("Structured field index row count does not match its records")
        self.known_fields = (set(self.field_index["fields"]) if self.field_index
                             else {field for row in self.rows for field in row["values"]})

    def unknown_fields(self, plan: QueryPlan) -> list[str]:
        fields = {condition.field for condition in plan.hard_conditions}
        if plan.sort_field: fields.add(plan.sort_field)
        if plan.aggregate_field: fields.add(plan.aggregate_field)
        return sorted(fields - self.known_fields)

    def _indexed_ids(self, condition: HardCondition) -> set[int] | None:
        if not self.field_index or condition.unit:
            return None
        categorical = self.field_index["categorical"].get(condition.field, {})
        pairs = self.field_index["numeric"].get(condition.field, [])
        if condition.op == "in" and not isinstance(condition.value, (list, tuple)):
            return None
        values = condition.value if condition.op == "in" else [condition.value]
        if condition.op in {"eq", "in"}:
            matches = set()
            for value in values:
                if isinstance(value, bool) or isinstance(value, str):
                    if isinstance(value, str) and len(value) > 128:
                        return None
                    matches.update(categorical.get(json.dumps(value, ensure_ascii=False), []))
                elif isinstance(value, (int, float)) and math.isfinite(value) and pairs:
                    left = bisect_left(pairs, [float(value), -1])
                    right = bisect_right(pairs, [float(value), len(self.rows)])
                    matches.update(pair[1] for pair in pairs[left:right])
                else:
                    return None
            return matches
        if not pairs or condition.op not in {"gt", "gte", "lt", "lte", "between"}:
            return None
        try:
            if condition.op == "between":
                low, high = map(float, condition.value)
                left = bisect_left(pairs, [low, -1])
                right = bisect_right(pairs, [high, len(self.rows)])
            else:
                value = float(condition.value)
                left = bisect_right(pairs, [value, len(self.rows)]) if condition.op == "gt" else 0
                if condition.op == "gte": left = bisect_left(pairs, [value, -1])
                right = bisect_left(pairs, [value, -1]) if condition.op == "lt" else len(pairs)
                if condition.op == "lte": right = bisect_right(pairs, [value, len(self.rows)])
            return {pair[1] for pair in pairs[left:right]}
        except (TypeError, ValueError, IndexError):
            return None

    def search(self, plan: QueryPlan, version: str, index_version: str) -> list[Evidence]:
        candidate_ids: set[int] | None = None
        for condition in plan.hard_conditions:
            indexed = self._indexed_ids(condition)
            if indexed is not None:
                candidate_ids = indexed if candidate_ids is None else candidate_ids & indexed
        matches = []
        for row_id in sorted(candidate_ids) if candidate_ids is not None else range(len(self.rows)):
            row = self.rows[row_id]
            if plan.target_entity and plan.target_entity not in json.dumps(row["values"], ensure_ascii=False):
                continue
            if all(_matches(_nested(row["values"], condition.field), condition, row.get("units", {}).get(condition.field)) for condition in plan.hard_conditions):
                matches.append(row)
        if plan.operation == "sort" and plan.sort_field:
            present = [row for row in matches if _nested(row["values"], plan.sort_field) is not None]
            missing = [row for row in matches if _nested(row["values"], plan.sort_field) is None]
            def sort_key(row):
                value = _nested(row["values"], plan.sort_field)
                return (0, float(value)) if isinstance(value, (int, float)) and not isinstance(value, bool) else (1, str(value).lower())
            matches = sorted(present, key=sort_key, reverse=plan.sort_descending) + missing
        if plan.operation in {"count", "sum", "min", "max", "average"}:
            field = plan.aggregate_field or (plan.hard_conditions[-1].field if plan.hard_conditions else None)
            values, mapping_required = [], False
            for row in matches:
                value = _nested(row["values"], field) if field else None
                if not isinstance(value, (int, float)) or isinstance(value, bool):
                    continue
                try:
                    values.append(float(_convert(value, row.get("units", {}).get(field), plan.unit)))
                except (TypeError, ValueError):
                    mapping_required = True
            if mapping_required and plan.operation != "count":
                facts = {"operation": plan.operation, "field": field, "value": None,
                         "matchedRowCount": len(matches), "status": "NEEDS_UNIT_MAPPING"}
                return [_evidence(version=version, index_version=index_version, space="structured.typed", asset_id=None,
                                  locator={}, facts=facts, score=None, score_type="exact", evidence_type="calculation",
                                  flags=["UNIT_MAPPING_REQUIRED"])]
            aggregate = {"sum": np.sum, "min": np.min, "max": np.max, "average": np.mean}
            result = len(matches) if plan.operation == "count" else (aggregate[plan.operation](values).item() if values else None)
            facts = {"operation": plan.operation, "field": field, "value": result, "matchedRowCount": len(matches), "completeFilteredSet": True}
            return [_evidence(version=version, index_version=index_version, space="structured.typed", asset_id=None, locator={}, facts=facts,
                              score=None, score_type="exact", evidence_type="calculation", units={"value": plan.unit} if plan.unit else {})]
        result = []
        for rank, row in enumerate(matches[:plan.top_k], 1):
            result.append(_evidence(version=version, index_version=index_version, space="structured.typed", asset_id=row["assetId"],
                                    locator=row["locator"], facts=row["values"], score=1.0, score_type="hard_conditions", rank=rank,
                                    units=row.get("units"), flags=row.get("qualityFlags"), preview={"logicalPath": row["logicalPath"]}, evidence_type="constraint"))
        return result


def _deduplicate(rows: list[dict]) -> list[dict]:
    selected = {}
    for row in rows:
        # A generated fallback entity is scoped to its source asset.  Even for
        # explicitly named entities, retaining the asset in the key prevents
        # one file from silently overwriting an observation from another file.
        key = (row.get("assetId"), row["entity"], row["metric"], row["normalizedTime"])
        if key not in selected or (row.get("rawRow") or 0) >= (selected[key].get("rawRow") or 0):
            selected[key] = row
    return sorted(selected.values(), key=lambda row: _iso(row["normalizedTime"]))


def _duration_above(rows: list[dict], threshold: float) -> tuple[float, dict, list[str]]:
    if len(rows) < 2:
        return 0.0, {"method": "insufficient-samples"}, ["CONTINUOUS_DURATION_UNDETERMINED"]
    times = [_iso(row["normalizedTime"]).timestamp() for row in rows]
    deltas = np.diff(times)
    positive = deltas[deltas > 0]
    # Lower quartile is robust when a very small series contains one large
    # outage; the median of two intervals would hide that gap.
    typical = float(np.percentile(positive, 25)) if len(positive) else 0.0
    duration, gaps = 0.0, []
    for index, delta in enumerate(deltas):
        if delta <= 0:
            continue
        if typical and delta > typical * 3:
            gaps.append({"start": rows[index]["normalizedTime"], "end": rows[index + 1]["normalizedTime"], "seconds": delta})
            continue
        if rows[index]["value"] > threshold:
            duration += delta
    flags = ["DURATION_ESTIMATED_FROM_DISCRETE_SAMPLES"]
    if gaps: flags.append("GAPS_EXCLUDED")
    return duration, {"method": "piecewise-constant-until-next-sample", "typicalIntervalSeconds": typical, "excludedGaps": gaps}, flags


def _dtw(a: list[float], b: list[float], window_ratio=.15) -> float:
    left, right = np.asarray(a, dtype=float), np.asarray(b, dtype=float)
    left = (left - left.mean()) / (left.std() or 1.0)
    right = (right - right.mean()) / (right.std() or 1.0)
    window = max(abs(len(left) - len(right)), int(max(len(left), len(right)) * window_ratio), 1)
    previous = np.full(len(right) + 1, np.inf); previous[0] = 0.0
    for i in range(1, len(left) + 1):
        current = np.full(len(right) + 1, np.inf)
        for j in range(max(1, i - window), min(len(right), i + window) + 1):
            current[j] = abs(left[i - 1] - right[j - 1]) + min(current[j - 1], previous[j], previous[j - 1])
        previous = current
    return float(previous[-1] / max(len(left), len(right)))


def _segment_features(values: list[float]) -> np.ndarray:
    values = np.asarray(values, dtype=float)
    if not len(values):
        return np.zeros(12, dtype="float32")
    mean, std = float(values.mean()), float(values.std())
    normalized = (values - mean) / (std or 1.0)
    positions = np.linspace(0.0, 1.0, len(values))
    slope = float(np.polyfit(positions, normalized, 1)[0]) if len(values) > 1 else 0.0
    diffs = np.diff(normalized)
    quantiles = np.quantile(normalized, [0, .1, .25, .5, .75, .9, 1])
    vector = np.asarray([*quantiles, slope, float(np.mean(np.abs(diffs))) if len(diffs) else 0.0,
                         float(np.max(np.abs(diffs))) if len(diffs) else 0.0,
                         math.log1p(len(values)) / 10.0, std / (abs(mean) + std + 1e-9)], dtype="float32")
    norm = float(np.linalg.norm(vector))
    return vector / norm if norm else vector


def _time_weighted_average(rows: list[dict]) -> tuple[float | None, dict, list[str]]:
    if not rows:
        return None, {"method": "no-samples"}, []
    if len(rows) == 1:
        return float(rows[0]["value"]), {"method": "single-observation"}, ["TIME_WEIGHTING_UNDETERMINED"]
    times = np.asarray([_iso(row["normalizedTime"]).timestamp() for row in rows])
    values = np.asarray([row["value"] for row in rows], dtype=float)
    deltas = np.diff(times)
    positive = deltas[deltas > 0]
    typical = float(np.percentile(positive, 25)) if len(positive) else 0.0
    area = duration = 0.0
    gaps = []
    for index, delta in enumerate(deltas):
        if delta <= 0:
            continue
        if typical and delta > typical * 3:
            gaps.append({"start": rows[index]["normalizedTime"], "end": rows[index + 1]["normalizedTime"], "seconds": float(delta)})
            continue
        area += (values[index] + values[index + 1]) * .5 * delta
        duration += delta
    flags = ["GAPS_EXCLUDED"] if gaps else []
    return (area / duration if duration else None), {
        "method": "linear-trapezoidal-time-weighted", "typicalIntervalSeconds": typical,
        "includedDurationSeconds": duration, "excludedGaps": gaps,
    }, flags


class TimeSeriesRetriever:
    def __init__(self, facts_path: Path | None, segments_path: Path | None, feature_path: Path | None = None):
        self.facts = [json.loads(line) for line in facts_path.read_text(encoding="utf-8").splitlines() if line] if facts_path else []
        self.segments = json.loads(segments_path.read_text(encoding="utf-8")).get("segments", []) if segments_path else []
        self.feature_index = faiss.read_index(str(feature_path)) if feature_path else None
        self.feature_partitions: dict[tuple[str, ...], tuple[Any, list[int]]] = {}
        if self.feature_index is not None and self.feature_index.ntotal == len(self.segments):
            vectors = np.asarray(self.feature_index.reconstruct_n(0, self.feature_index.ntotal), dtype="float32")
            partition_ids: dict[tuple[str, ...], list[int]] = defaultdict(list)
            for index, segment in enumerate(self.segments):
                partition_ids[("metric", str(segment.get("metric")))].append(index)
                partition_ids[("entity", str(segment.get("entity")))].append(index)
                partition_ids[("metric_entity", str(segment.get("metric")), str(segment.get("entity")))].append(index)
            for key, source_ids in partition_ids.items():
                partition = faiss.IndexFlatIP(self.feature_index.d)
                partition.add(np.ascontiguousarray(vectors[source_ids]))
                self.feature_partitions[key] = (partition, source_ids)

    @staticmethod
    def _segment_allowed(segment: dict, plan: QueryPlan) -> bool:
        if plan.metric and segment["metric"] != plan.metric:
            return False
        if plan.target_entity and segment["entity"] != plan.target_entity:
            return False
        if plan.time_range:
            start, end = _iso(plan.time_range.start), _iso(plan.time_range.end)
            segment_start, segment_end = _iso(segment["timeStart"]), _iso(segment["timeEnd"])
            if segment_start < start or (segment_end > end if plan.time_range.boundary == "[start,end]" else segment_end >= end):
                return False
        return True

    def _range(self, plan: QueryPlan, time_range=None) -> tuple[list[dict], list[str]]:
        selected_range = time_range or plan.time_range
        start, end = _iso(selected_range.start), _iso(selected_range.end)
        inclusive_end = selected_range.boundary == "[start,end]"
        rows = [row for row in self.facts if row["metric"] == plan.metric and
                (not plan.target_entity or row["entity"] == plan.target_entity) and
                _iso(row["normalizedTime"]) >= start and
                (_iso(row["normalizedTime"]) <= end if inclusive_end else _iso(row["normalizedTime"]) < end)]
        source_units = {row.get("unit") for row in rows}
        if not plan.unit and (None in source_units or len(source_units) > 1):
            return [], ["UNIT_MAPPING_REQUIRED"]
        converted = []
        conversion_flags = []
        for row in rows:
            try:
                item = dict(row)
                item["value"] = _convert(row["value"], row.get("unit"), plan.unit)
                item["unit"] = plan.unit or row.get("unit")
                converted.append(item)
            except ValueError as exception:
                conversion_flags.append(str(exception) + "_EXCLUDED")
        return _deduplicate(converted), sorted(set(conversion_flags))

    def search(self, plan: QueryPlan, version: str, index_version: str) -> list[Evidence]:
        if plan.operation == "search":
            selected = [segment for segment in self.segments if self._segment_allowed(segment, plan)]
            return [_evidence(version=version, index_version=index_version, space="timeseries.shape", asset_id=segment["assetId"], locator={},
                              facts={"metric": segment["metric"], "sampleCount": len(segment["values"]), "candidateOnly": True},
                              score=None, score_type="metadata_match", rank=rank, entity=segment["entity"],
                              time_range={"start": segment["timeStart"], "end": segment["timeEnd"]}, units={"value": segment.get("unit")})
                    for rank, segment in enumerate(selected[:plan.top_k], 1)]
        if plan.operation == "similar_shape":
            if self.feature_index is None or not self.feature_index.ntotal:
                return []
            query = _segment_features(plan.query_series).reshape(1, -1)
            partition_key = (("metric_entity", plan.metric, plan.target_entity) if plan.metric and plan.target_entity
                             else ("metric", plan.metric) if plan.metric
                             else ("entity", plan.target_entity) if plan.target_entity else None)
            selected_index, source_ids = ((self.feature_index, list(range(self.feature_index.ntotal)))
                                          if partition_key is None else self.feature_partitions.get(partition_key, (None, [])))
            if selected_index is None or not selected_index.ntotal:
                return []
            candidate_limit = min(selected_index.ntotal, max(plan.top_k * 10, 50), 200)
            _, ids = selected_index.search(query, candidate_limit)
            recalled = [self.segments[source_ids[int(index)]] for index in ids[0] if index >= 0 and
                        self._segment_allowed(self.segments[source_ids[int(index)]], plan)]
            candidates = sorted(((_dtw(plan.query_series, segment["values"]), segment) for segment in recalled), key=lambda item: item[0])[:plan.top_k]
            return [_evidence(version=version, index_version=index_version, space="timeseries.shape", asset_id=segment["assetId"], locator={},
                              facts={"metric": segment["metric"], "dtwDistance": distance, "amplitudeNormalization": "zscore", "timeWarping": True,
                                     "candidateRecallAlgorithm": "faiss-shape12-ip-prefiltered" if partition_key else "faiss-shape12-ip",
                                     "candidateCount": len(recalled)},
                              score=distance, score_type="dtw_distance_lower_is_better", rank=rank, entity=segment["entity"],
                              time_range={"start": segment["timeStart"], "end": segment["timeEnd"]}, units={"value": segment.get("unit")})
                    for rank, (distance, segment) in enumerate(candidates, 1)]
        rows, range_flags = self._range(plan)
        values = np.asarray([row["value"] for row in rows], dtype=float)
        series_keys = {(row.get("assetId"), row["entity"]) for row in rows}
        asset_ids = {row.get("assetId") for row in rows if row.get("assetId")}
        flags = sorted({flag for row in rows for flag in row.get("qualityFlags", [])} | set(range_flags))
        policy = rows[0].get("calculationPolicy", {}) if rows else {}
        facts: dict[str, Any] = {"operation": plan.operation, "metric": plan.metric, "sampleCount": len(rows),
                                 "sourceSeriesCount": len(series_keys), "completeAuthorizedRange": True}
        single_series_operations = {"time_weighted_average", "duration_above", "compare_periods",
                                    "trend_start_end", "trend_regression", "trend_monotonic"}
        if plan.operation in single_series_operations and len(series_keys) > 1:
            facts.update({"value": None, "status": "TARGET_ENTITY_REQUIRED", "completeAuthorizedRange": False})
            flags.append("MULTIPLE_SERIES_REQUIRE_EXPLICIT_TARGET")
        elif "SOURCE_TIMEZONE_UNSPECIFIED" in flags:
            facts.update({"value": None, "status": "NEEDS_TIMEZONE_MAPPING", "completeAuthorizedRange": False})
        elif range_flags:
            facts.update({"value": None, "status": "NEEDS_UNIT_MAPPING", "completeAuthorizedRange": False})
        elif not len(values):
            facts.update({"value": None, "status": "NO_DATA"})
        elif plan.operation == "count":
            facts["value"] = len(values)
        elif plan.operation in {"sum", "min", "max", "average"}:
            if plan.operation == "sum": value = float(values.sum())
            elif plan.operation == "min": value = float(values.min())
            elif plan.operation == "max": value = float(values.max())
            else: value = float(values.mean())
            facts["value"] = value
            if plan.operation in {"min", "max"}:
                position = int(np.argmin(values) if plan.operation == "min" else np.argmax(values))
                facts["occurredAt"] = rows[position]["normalizedTime"]
                facts["occurredInAssetId"] = rows[position].get("assetId")
        elif plan.operation == "time_weighted_average":
            value, average_policy, average_flags = _time_weighted_average(rows)
            facts["value"] = value
            policy = {**policy, **average_policy}; flags.extend(average_flags)
        elif plan.operation == "duration_above":
            duration, estimate_policy, estimate_flags = _duration_above(rows, plan.threshold)
            facts.update({"value": duration, "threshold": plan.threshold, "unit": "seconds"})
            policy = {**policy, **estimate_policy}; flags.extend(estimate_flags)
        elif plan.operation == "compare_periods":
            second, second_flags = self._range(plan, plan.compare_time_range)
            flags.extend(second_flags)
            flags.extend(flag for row in second for flag in row.get("qualityFlags", []))
            combined_series = series_keys | {(row.get("assetId"), row["entity"]) for row in second}
            facts["sourceSeriesCount"] = len(combined_series)
            if len(combined_series) > 1:
                facts.update({"value": None, "difference": None, "status": "TARGET_ENTITY_REQUIRED",
                              "completeAuthorizedRange": False})
                flags.append("MULTIPLE_SERIES_REQUIRE_EXPLICIT_TARGET")
            elif "SOURCE_TIMEZONE_UNSPECIFIED" in flags:
                facts.update({"value": None, "difference": None, "status": "NEEDS_TIMEZONE_MAPPING",
                              "completeAuthorizedRange": False})
            elif second_flags:
                facts.update({"value": None, "difference": None, "status": "NEEDS_UNIT_MAPPING",
                              "completeAuthorizedRange": False})
            elif not second:
                facts.update({"value": None, "difference": None, "status": "NO_DATA",
                              "completeAuthorizedRange": False})
            else:
                first_mean = float(values.mean())
                second_mean = float(np.mean([row["value"] for row in second]))
                facts.update({"firstPeriodAverage": first_mean, "secondPeriodAverage": second_mean,
                              "difference": second_mean - first_mean})
        elif plan.operation == "trend_start_end":
            facts.update({"value": float(values[-1] - values[0]), "definition": "last-minus-first"})
        elif plan.operation == "trend_regression":
            x = np.asarray([_iso(row["normalizedTime"]).timestamp() for row in rows]); x -= x[0]
            facts.update({"value": float(np.polyfit(x, values, 1)[0]) if len(values) > 1 else 0.0, "definition": "least-squares-slope-per-second"})
        elif plan.operation == "trend_monotonic":
            facts.update({"value": bool(np.all(np.diff(values) >= 0)), "definition": "every-successive-observation-nondecreasing"})
        return [_evidence(version=version, index_version=index_version, space="timeseries.facts",
                          asset_id=next(iter(asset_ids)) if len(asset_ids) == 1 else None,
                          locator={}, facts=facts, score=None, score_type="exact_programmatic", entity=plan.target_entity,
                          time_range=plan.time_range.model_dump() if plan.time_range else None,
                          units={"value": plan.unit or (rows[0].get("unit") if rows else None)}, policy=policy,
                          flags=flags, evidence_type="calculation")]


class GeometryRetriever:
    def __init__(self, facts_path: Path | None, shape_index_path: Path | None = None,
                 shape_meta_path: Path | None = None, partitions: dict | None = None):
        self.units = json.loads(facts_path.read_text(encoding="utf-8")).get("geometry", []) if facts_path else []
        self.shape_index = faiss.read_index(str(shape_index_path)) if shape_index_path else None
        self.shape_items = json.loads(shape_meta_path.read_text(encoding="utf-8")).get("items", []) if shape_meta_path else []
        self.partitions = partitions or {}

    @staticmethod
    def feature_space(unit):
        return unit["payload"].get("featureSpaceId") or ("cad-aabb-brep-v1" if unit.get("parser_name") == "step-opencascade" else "mesh-principal-moments-v1")

    def search(self, plan: QueryPlan, version: str, index_version: str) -> list[Evidence]:
        query = next((unit for unit in self.units if unit["asset_id"] == plan.query_asset_id), None) if plan.query_asset_id else None
        candidate_units = self.units
        recalled_scores: dict[str, float] = {}
        if plan.operation == "similar_shape":
            shape_index, shape_items = self.shape_index, self.shape_items
            if query and self.partitions:
                shape_index, shape_items = self.partitions.get(self.feature_space(query), (None, []))
            if not query or shape_index is None or not shape_index.ntotal or not shape_items:
                return []
            vector = np.asarray(query["payload"].get("featureVector", []), dtype="float32")
            if not len(vector):
                return []
            padded = np.zeros(shape_index.d, dtype="float32")
            padded[:min(len(vector), shape_index.d)] = vector[:shape_index.d]
            padded /= max(float(np.linalg.norm(padded)), 1e-12)
            candidate_limit = min(shape_index.ntotal, max(plan.top_k * 10, 50), 200)
            scores, ids = shape_index.search(padded.reshape(1, -1), candidate_limit)
            candidate_units = []
            for score, row_id in zip(scores[0], ids[0]):
                if row_id < 0:
                    continue
                unit = shape_items[int(row_id)]
                if self.feature_space(unit) != self.feature_space(query):
                    continue
                candidate_units.append(unit)
                recalled_scores[unit["unit_id"]] = float(score)
        candidates = []
        for unit in candidate_units:
            payload, units = unit["payload"], unit.get("units", {})
            if plan.target_entity and unit.get("entity_id") != plan.target_entity:
                continue
            if plan.rotation_invariant and not payload.get("shapeFeatureRotationInvariant", False):
                continue
            if not all(_matches(_nested(payload, condition.field), condition, _geometry_unit(units, condition.field)) for condition in plan.hard_conditions):
                continue
            score = 1.0
            facts = {"conditionsSatisfied": True, "algorithm": "geometry-properties-1.0", "tolerance": plan.tolerance}
            if plan.operation == "similar_shape":
                if not query or not payload.get("featureVector") or not query["payload"].get("featureVector"):
                    continue
                left, right = np.asarray(query["payload"]["featureVector"], dtype=float), np.asarray(payload["featureVector"], dtype=float)
                length = min(len(left), len(right)); left, right = left[:length], right[:length]
                score = float(np.dot(left, right) / ((np.linalg.norm(left) * np.linalg.norm(right)) or 1.0))
                facts.update({"shapeSimilarity": score, "shapeMatch": score >= 1.0 - plan.tolerance,
                              "scaleInvariant": True, "requestedScaleInvariant": plan.scale_invariant,
                              "rotationInvariant": bool(payload.get("shapeFeatureRotationInvariant")),
                              "candidateRecallAlgorithm": "faiss-geometry-feature-ip",
                              "candidateRecallScore": recalled_scores.get(unit["unit_id"]),
                              "candidateLimit": len(candidate_units),
                              "dimensionMatch": None if plan.scale_invariant else self._dimension_match(
                                  query["payload"], payload, query.get("units", {}), units, plan.tolerance)})
            candidates.append((score, unit, facts))
        candidates.sort(key=lambda item: item[0], reverse=True)
        return [_evidence(version=version, index_version=index_version, space="geometry.shape" if plan.operation == "similar_shape" else "geometry.properties",
                          asset_id=unit["asset_id"], locator=unit["locator"], facts=facts, score=score,
                          score_type="cosine_shape_feature" if plan.operation == "similar_shape" else "hard_conditions", rank=rank,
                          entity=unit.get("entity_id"), units=unit.get("units"), flags=unit.get("quality_flags"),
                          preview={"logicalPath": unit["logical_path"]}, evidence_type="retrieval" if plan.operation == "similar_shape" else "constraint")
                for rank, (score, unit, facts) in enumerate(candidates[:plan.top_k], 1)]

    @staticmethod
    def _dimension_match(left: dict, right: dict, left_units: dict, right_units: dict, tolerance: float):
        a, b = _nested(left, "bounds.size"), _nested(right, "bounds.size")
        if a is None or b is None:
            return None
        left_unit = _geometry_unit(left_units, "bounds.size")
        right_unit = _geometry_unit(right_units, "bounds.size")
        if not left_unit or not right_unit:
            return None
        try:
            a_m = sorted(_convert(value, left_unit, "m") for value in a)
            b_m = sorted(_convert(value, right_unit, "m") for value in b)
        except (TypeError, ValueError):
            return None
        if len(a_m) != len(b_m):
            return False
        return all(abs(float(x) - float(y)) <= tolerance * max(abs(float(x)), abs(float(y)), 1e-12)
                   for x, y in zip(a_m, b_m))


class ProfessionalRetrieverSuite:
    """Loads each semantic space independently and executes a validated plan."""

    def __init__(self, registrations: list[dict], files: dict[str, Path],
                 visual_search: Callable[[QueryPlan], list[dict]] | None = None,
                 cache: dict[str, Any] | None = None):
        self.registrations = {item["semanticSpace"]: item for item in registrations if item.get("status") == "READY"}
        self.files = files
        self.visual_search = visual_search
        self.cache = cache if cache is not None else {}

    def _path(self, space: str, suffix: str) -> Path | None:
        registration = self.registrations.get(space)
        if not registration: return None
        return next((self.files.get(item["path"]) for item in registration.get("files", []) if item["path"].endswith(suffix)), None)

    def _cached(self, key: str, factory: Callable[[], Any]) -> Any:
        if key not in self.cache:
            self.cache[key] = factory()
        return self.cache[key]

    def _prepare(self, name: str):
        if name == "text":
            lexical = self._path("text.lexical.zh", "bm25.json")
            dense = self._path("text.semantic.zh", "semantic.faiss")
            dense_meta = self._path("text.semantic.zh", "semantic.json")
            model_version = (self.registrations.get("text.semantic.zh") or {}).get("modelVersion")
            return self._cached("text", lambda: TextRetriever(lexical, dense, dense_meta, model_version)) if lexical or dense else None
        if name == "structured":
            records = self._path("structured.typed", "records.jsonl")
            field_index = self._path("structured.typed", "field-index.json.gz")
            return self._cached("structured", lambda: StructuredRetriever(records, field_index)) if records else None
        if name == "timeseries":
            facts = self._path("timeseries.facts", "facts.jsonl")
            segments = self._path("timeseries.shape", "segments.json")
            features = self._path("timeseries.shape", "shape.faiss")
            return self._cached("timeseries", lambda: TimeSeriesRetriever(facts, segments, features)) if facts or segments else None
        if name == "geometry":
            facts = self._path("geometry.properties", "facts.json")
            shape_index = self._path("geometry.shape", "shape.faiss")
            shape_meta = self._path("geometry.shape", "shape.json")
            def geometry_factory():
                partitions = {}
                for space in self.registrations:
                    if not space.startswith("geometry.shape"): continue
                    index_path, meta_path = self._path(space, "shape.faiss"), self._path(space, "shape.json")
                    if index_path and meta_path:
                        items = json.loads(meta_path.read_text(encoding="utf-8")).get("items", [])
                        if items and len({GeometryRetriever.feature_space(item) for item in items}) == 1:
                            partitions[GeometryRetriever.feature_space(items[0])] = (faiss.read_index(str(index_path)), items)
                return GeometryRetriever(facts, shape_index, shape_meta, partitions)
            return self._cached("geometry", geometry_factory) if facts else None
        return None

    def preload(self) -> dict[str, Any]:
        for name in ("text", "structured", "timeseries", "geometry"):
            self._prepare(name)
        return self.cache

    def _run(self, name: str, plan: QueryPlan, version: str, index_version: str) -> tuple[list[Evidence], list[str]]:
        if name == "text":
            retriever = self._prepare("text")
            return ((retriever.search(plan, version, index_version), [])
                    if retriever and (retriever.lexical or retriever.dense is not None)
                    else ([], ["TEXT_INDEX_NOT_READY"]))
        if name == "structured":
            retriever = self._prepare("structured")
            if retriever:
                unknown = retriever.unknown_fields(plan)
                if unknown:
                    return [], [f"STRUCTURED_FIELD_NOT_FOUND:{field}" for field in unknown]
                if plan.operation in {"sum", "min", "max", "average"} and not (plan.aggregate_field or plan.hard_conditions):
                    return [], ["STRUCTURED_AGGREGATE_FIELD_REQUIRED"]
            return (retriever.search(plan, version, index_version), []) if retriever else ([], ["STRUCTURED_INDEX_NOT_READY"])
        if name == "timeseries":
            retriever = self._prepare("timeseries")
            if plan.operation == "similar_shape" and (not retriever or not retriever.segments or retriever.feature_index is None):
                return [], ["TIMESERIES_SHAPE_INDEX_NOT_READY"]
            if plan.operation != "similar_shape" and (not retriever or not retriever.facts):
                return [], ["TIMESERIES_FACT_INDEX_NOT_READY"]
            return retriever.search(plan, version, index_version), []
        if name == "geometry":
            retriever = self._prepare("geometry")
            if not retriever:
                return [], ["GEOMETRY_INDEX_NOT_READY"]
            if plan.operation == "similar_shape" and not retriever.partitions and (retriever.shape_index is None or not retriever.shape_items):
                return [], ["GEOMETRY_SHAPE_INDEX_NOT_READY"]
            return retriever.search(plan, version, index_version), []
        if name == "visual":
            if not self.visual_search:
                return [], ["VISUAL_RETRIEVER_NOT_CONNECTED"]
            result = []
            for rank, item in enumerate(self.visual_search(plan), 1):
                raw_asset_id = item.get("assetId")
                result.append(_evidence(version=version, index_version=index_version, space="visual.clip-cn-v1",
                                        asset_id=str(raw_asset_id) if raw_asset_id is not None else None,
                                        locator=item.get("locator", {}),
                                        facts={"captions": item.get("captions") or item.get("texts") or []},
                                        score=item.get("score"), score_type="clip_cosine", rank=rank,
                                        preview=item.get("preview") or {"logicalPath": item.get("logicalPath")},
                                        flags=item.get("qualityFlags")))
            return result, []
        return [], [f"UNSUPPORTED_RETRIEVER:{name}"]

    def execute(self, plan: QueryPlan, version: str, index_version: str) -> EvidencePack:
        started = time.perf_counter(); evidence, limitations, timings = [], [], {}
        channel_evidence: dict[str, list[Evidence]] = {}
        ready_channels = []
        for name in plan.retrievers:
            channel_started = time.perf_counter()
            step_plan = plan.model_copy(update={"operation": plan.retriever_operations.get(name, plan.operation)})
            dependencies = plan.depends_on.get(name, [])
            step_plans = [step_plan]
            relation_rows = []
            if dependencies:
                if any(source not in channel_evidence for source in dependencies):
                    limitations.append(f"DEPENDENCY_ORDER_INVALID:{name}")
                    channel_evidence[name] = []
                    continue
                relation_path = self._path("structured.typed", "relations.jsonl")
                if not relation_path:
                    code = "RELATION_NOT_FOUND" if "structured.typed" in self.registrations else "RELATION_INDEX_NOT_READY"
                    limitations.append(f"{code}:{name}")
                    channel_evidence[name] = []
                    continue
                relations = [json.loads(line) for line in relation_path.read_text(encoding="utf-8").splitlines() if line]
                source_entities = {item.entityId for source in dependencies for item in channel_evidence[source] if item.entityId}
                relation_rows = [row for row in relations if row.get("sourceEntityId") in source_entities]
                targets = sorted({row["targetEntityId"] for row in relation_rows})
                if not targets:
                    limitations.append(f"RELATION_NOT_FOUND:{name}")
                    channel_evidence[name] = []
                    continue
                step_plans = [step_plan.model_copy(update={"target_entity": target}) for target in targets]
            hits = []
            step_limitations = []
            for scoped_plan in step_plans:
                scoped_hits, scoped_limitations = self._run(name, scoped_plan, version, index_version)
                if dependencies:
                    for item in scoped_hits:
                        item.provenance["entityRelations"] = [row for row in relation_rows if row.get("targetEntityId") == item.entityId]
                        item.provenance["dependsOn"] = dependencies
                hits.extend(scoped_hits); step_limitations.extend(scoped_limitations)
            deduplicated = {item.evidenceId: item for item in hits}
            channel_evidence[name] = list(deduplicated.values())
            evidence.extend(channel_evidence[name])
            limitations.extend(step_limitations)
            if not step_limitations:
                ready_channels.append(name)
            timings[name] = round((time.perf_counter() - channel_started) * 1000, 3)
        # Exact calculations/constraints are never discarded by similarity fusion.
        exact = [item for item in evidence if item.evidenceType != "retrieval"]
        retrieved = [item for item in evidence if item.evidenceType == "retrieval"]
        # Scores from CLIP, cosine features and DTW are not commensurate. Each
        # retriever's own rank/score is preserved instead of globally adding them.
        evidence = exact + retrieved
        coverage = {"requested": plan.retrievers, "ready": ready_channels,
                    "evidenceCount": len(evidence), "calculationCount": len(exact)}
        timings["total"] = round((time.perf_counter() - started) * 1000, 3)
        conflicts = _detect_exact_conflicts(evidence)
        if conflicts:
            limitations.append(f"EVIDENCE_CONFLICT:{len(conflicts)}")
        return EvidencePack(snapshot={"dataVersion": version, "indexVersion": index_version}, plan=plan, evidence=evidence,
                            coverage=coverage, conflicts=conflicts, limitations=limitations, timingsMs=timings)
