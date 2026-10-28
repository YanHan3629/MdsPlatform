from __future__ import annotations

import hashlib
import gzip
import json
import math
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any

import faiss
import numpy as np

from .contracts import (organizer_version, PARSER_CONFIG_VERSION, ParseContext, ParsedAsset,ParsedItem,
                        SCHEMA_VERSION, SemanticUnit, content_sha256, deterministic_asset_id,
                        normalized_path, stable_id, write_json)
from .organizer import SemanticOrganizer
from .parsers import default_parser_registry
from .text_embedding import MODEL_DIMENSION, MODEL_VERSION, QUERY_INSTRUCTION, encode_passages
from .derived_cache import DerivedCache
from .snapshot_catalog import SnapshotCatalog, CATALOG_VERSION
from .json_native import is_encoded_number,number_metadata


INDEX_BUILDER_VERSION = "professional-indexes-1.17"


def _tokens(text: str) -> list[str]:
    compact = "".join(text.lower().split())
    words = [value for value in __import__("re").findall(r"[a-z0-9_]+|[\u4e00-\u9fff]", text.lower()) if value]
    chars = [compact[index:index + size] for size in (2, 3) for index in range(max(0, len(compact) - size + 1))]
    return words + chars


def _file_entry(path: Path, output_dir: Path) -> dict[str, Any]:
    return {"path": "/" + path.relative_to(output_dir).as_posix(), "sha256": content_sha256(path), "sizeBytes": path.stat().st_size}


def _base_registration(space: str, index_type: str, algorithm: str, dimension: int | None,
                       files: list[dict], count: int, formats: list[str]) -> dict:
    return {
        "semanticSpace": space, "indexType": index_type, "algorithmVersion": algorithm,
        "modelVersion": algorithm, "dimension": dimension, "normalization": "l2" if dimension else "none",
        "distance": "inner_product" if dimension else "exact/typed", "buildParameters": {},
        "files": files, "contentChecksum": hashlib.sha256("".join(item["sha256"] for item in files).encode()).hexdigest(),
        "coverage": {"unitCount": count, "sourceFormats": formats}, "status": "READY",
        "builderVersion": INDEX_BUILDER_VERSION,
    }


def _source_formats(units: list[SemanticUnit], asset_formats: dict[str, str]) -> list[str]:
    """Report only formats that contributed semantic units to an index space."""
    return sorted({asset_formats[unit.asset_id] for unit in units if unit.asset_id in asset_formats})


def _load_previous_assets(previous_asset_dir: Path | None) -> dict[str, Path]:
    """Index frozen cache paths without retaining every decoded old snapshot."""
    cached: dict[str, Path] = {};ambiguous=set()
    if previous_asset_dir is None or not previous_asset_dir.is_dir():
        return cached
    for path in previous_asset_dir.glob("*.json"):
        try:
            item = json.loads(path.read_text(encoding="utf-8"))
            if not isinstance(item,dict) or not isinstance(item.get('asset'),dict):continue
            asset = item.get("asset") or {}
            logical_path = normalized_path(str(asset.get("logical_path") or asset.get("logicalPath") or ""))
            if logical_path != "/" and logical_path not in ambiguous:
                if logical_path in cached:
                    cached.pop(logical_path,None);ambiguous.add(logical_path)
                else:cached[logical_path] = path
        except (OSError, ValueError, TypeError):
            continue
    return cached


def _reuse_cached_asset(cached: dict[str, Any] | Path, path: Path, relative: Path,
                        dataset_version_id: str, parser, processing_config: dict[str, Any]) -> tuple[ParsedAsset, list[SemanticUnit]] | None:
    if isinstance(cached,Path):
        try:cached=json.loads(cached.read_text(encoding='utf-8'))
        except (OSError,ValueError,TypeError):return None
    if not isinstance(cached,dict):return None
    asset_data = cached.get("asset") or {}
    units_data = cached.get("units") or []
    if not isinstance(asset_data,dict) or not isinstance(units_data,list) or any(not isinstance(unit,dict) for unit in units_data):return None
    if not isinstance(asset_data.get('attributes',{}),dict):return None
    # Engineering render attributes contain paths local to their build job.
    # Reparse against the current output (the legacy conversion has already
    # materialized hash-checked views there), rather than copying stale paths
    # from a preceding immutable index. Embeddings retain their content cache.
    if asset_data.get('attributes',{}).get('engineeringAdapters'):
        return None
    source_hash = content_sha256(path)
    logical_path = normalized_path(relative.as_posix())
    expected_format='JPG' if path.suffix.lower()=='.jpeg' else path.suffix.lstrip('.').upper()
    cached_config = cached.get("processingConfig") or {
        "timezone": None, "unitMapping": {}, "timeColumn": None, "entityColumns": []
    }
    if not units_data or source_hash != asset_data.get("content_sha256") or cached_config != processing_config:
        return None
    if asset_data.get('logical_path')!=logical_path or asset_data.get('source_format')!=expected_format:return None
    if (asset_data.get("parser_name") != parser.name
            or asset_data.get("parser_version") != parser.version
            or asset_data.get("parser_config_version") != PARSER_CONFIG_VERSION
            or any(unit.get("organizer_version") != organizer_version(asset_data.get('source_format','')) for unit in units_data)):
        return None
    old_asset_id = str(asset_data.get("asset_id"))
    if any(unit.get('asset_id')!=old_asset_id or unit.get('dataset_version_id')!=asset_data.get('dataset_version_id')
        or unit.get('logical_path')!=logical_path or unit.get('content_sha256')!=source_hash
        or unit.get('parser_name')!=parser.name or unit.get('parser_version')!=parser.version
        or unit.get('parser_config_version')!=PARSER_CONFIG_VERSION for unit in units_data):return None
    by_id={unit.get('unit_id'):unit for unit in units_data}
    if len(by_id)!=len(units_data) or None in by_id:return None
    for unit in units_data:
        if not isinstance(unit.get('metadata',{}),dict):return None
        table_id=unit.get('metadata',{}).get('tableId')
        if table_id is not None and (table_id not in by_id or by_id[table_id].get('unit_type')!='TableUnit'):return None
    asset_id = deterministic_asset_id(dataset_version_id, logical_path)
    asset = ParsedAsset(
        asset_id=asset_id,
        dataset_version_id=dataset_version_id,
        logical_path=logical_path,
        source_format=str(asset_data.get("source_format") or path.suffix.lstrip(".").upper()),
        content_sha256=source_hash,
        parser_name=parser.name,
        parser_version=parser.version,
        parser_config_version=PARSER_CONFIG_VERSION,
        items=[],
        attributes=dict(asset_data.get("attributes") or {}),
        quality_flags=list(asset_data.get("quality_flags") or []),
    )
    if old_asset_id!=asset_id:
        # Changing UUIDs in existing units leaves table/row/entity references
        # pointing at the old scope. Reorganize verified parsed source items;
        # never substitute UUID-looking strings inside original business data.
        original_items=asset_data.get('items')
        if not original_items:return None
        try:
            asset.items=[ParsedItem(**item) for item in original_items]
            fresh_units=SemanticOrganizer().organize(asset,timezone_name=processing_config.get('timezone'),
                time_column=processing_config.get('timeColumn'),entity_columns=processing_config.get('entityColumns',[]))
        except (TypeError,ValueError,KeyError):return None
        return asset,fresh_units
    reused_units = []
    for raw in units_data:
        item = dict(raw)
        item["asset_id"] = asset_id
        item["dataset_version_id"] = dataset_version_id
        item["logical_path"] = logical_path
        item["content_sha256"] = source_hash
        reused_units.append(SemanticUnit(**item))
    return asset, reused_units


def _build_text(units: list[SemanticUnit], root: Path, output_dir: Path,
                asset_formats: dict[str, str], cache: DerivedCache | None = None) -> list[dict]:
    text_units = [unit for unit in units if unit.unit_type == "TextUnit" and str(unit.payload).strip()]
    if not text_units:
        return []
    root.mkdir(parents=True, exist_ok=True)
    documents = []
    postings: dict[str, dict[str, int]] = defaultdict(dict)
    lengths = []
    passages = []
    for index, unit in enumerate(text_units):
        text = str(unit.payload)
        tokens = _tokens(text)
        lengths.append(len(tokens))
        documents.append({"documentId": index, "unit": unit.to_dict(), "text": text})
        for token, count in Counter(tokens).items():
            postings[token][str(index)] = count
        passages.append(text)
    lexical_path = root / "bm25.json"
    write_json(lexical_path, {"schemaVersion": SCHEMA_VERSION, "documents": documents, "postings": postings,
                              "documentLengths": lengths, "averageDocumentLength": sum(lengths) / max(1, len(lengths)), "k1": 1.5, "b": 0.75})
    dense_path = root / "semantic.faiss"
    if cache:
        vectors = {text: cache.get("textEmbedding", [MODEL_VERSION, text]) for text in dict.fromkeys(passages)}
        missing = [text for text, vector in vectors.items() if vector is None]
        if missing:
            for text, vector in zip(missing, encode_passages(missing)):
                vectors[text] = cache.put("textEmbedding", [MODEL_VERSION, text], vector.tolist())
        matrix = np.asarray([vectors[text] for text in passages], dtype="float32")
    else:
        matrix = encode_passages(passages)
    index = faiss.IndexFlatIP(MODEL_DIMENSION)
    index.add(matrix)
    faiss.write_index(index, str(dense_path))
    meta_path = root / "semantic.json"
    write_json(meta_path, {"documents": documents})
    formats = _source_formats(text_units, asset_formats)
    return [
        _base_registration("text.lexical.zh", "INVERTED_BM25", "bm25-char-word-1.0", None, [_file_entry(lexical_path, output_dir)], len(documents), formats),
        {**_base_registration("text.semantic.zh", "FAISS_FLAT_IP", MODEL_VERSION, MODEL_DIMENSION,
                              [_file_entry(dense_path, output_dir), _file_entry(meta_path, output_dir)], len(documents), formats),
         "buildParameters": {"pooling": "CLS", "maxTokens": 512, "queryInstruction": QUERY_INSTRUCTION,
                             "device": "cpu", "batchSize": 8}},
    ]


def _build_structured(units: list[SemanticUnit], root: Path, output_dir: Path,
                      asset_formats: dict[str, str], cache: DerivedCache | None = None) -> list[dict]:
    table_units = [unit for unit in units if unit.unit_type == "TableUnit"]
    if not table_units:
        return []
    rows = []
    relations = []
    schemas = []
    for unit in table_units:
        schemas.append({"unitId": unit.unit_id, "assetId": unit.asset_id, "logicalPath": unit.logical_path,
                        "locator": unit.locator, "columns": unit.payload.get("columns", [])})
        for record in unit.payload.get("records", []):
            values = record.get("values", {})
            rows.append({"unitId": unit.unit_id, "assetId": unit.asset_id, "datasetVersionId": unit.dataset_version_id,
                         "logicalPath": unit.logical_path, "locator": {**unit.locator, **record.get('locator',{}), "rawRow": record.get("rawRow")},
                         "values": values, "units": unit.units, "qualityFlags": unit.quality_flags,
                         "sourceValues":record.get('sourceValues',values),
                         "numberLexemes":record.get('numberLexemes',{}),
                         "valueTransforms":record.get('valueTransforms',[]),
                         "formulas":record.get('formulas',{}),
                         "calculationEligibility":record.get('calculationEligibility',{}),
                         "metadata": unit.metadata})
            by_name = {str(key).lower(): value for key, value in values.items()}
            source = by_name.get("source_entity_id", by_name.get("sourceentityid"))
            relation = by_name.get("relation_type", by_name.get("relationtype"))
            target = by_name.get("target_entity_id", by_name.get("targetentityid"))
            if source is not None and relation is not None and target is not None:
                relations.append({"sourceEntityId": str(source), "relationType": str(relation),
                                  "targetEntityId": str(target), "assetId": unit.asset_id,
                                  "logicalPath": unit.logical_path,
                                  "locator": {**unit.locator, **record.get('locator',{}), "rawRow": record.get("rawRow")}})
    rows_path, schema_path = root / "records.jsonl", root / "schema.json"
    root.mkdir(parents=True, exist_ok=True)
    rows_path.write_text("\n".join(json.dumps(row, ensure_ascii=False) for row in rows), encoding="utf-8")
    categorical: dict[str, dict[str, list[int]]] = defaultdict(lambda: defaultdict(list))
    numeric: dict[str, list[list[float | int]]] = defaultdict(list)
    known_fields: set[str] = set()
    row_groups = defaultdict(list)
    for row_id, row in enumerate(rows): row_groups[row["assetId"]].append((row_id,row))
    for group in row_groups.values():
        values = [row["values"] for _,row in group]
        def create_postings():
            cats, nums, fields = defaultdict(lambda: defaultdict(list)), defaultdict(list), set()
            for local_id, (_,record) in enumerate(group):
                row=record['values']
                for field, value in row.items():
                    fields.add(field)
                    if is_encoded_number(value,number_metadata(record,field)):continue
                    if isinstance(value,bool) or (isinstance(value,str) and len(value)<=128):
                        cats[field][json.dumps(value,ensure_ascii=False)].append(local_id)
                    elif isinstance(value,(int,float)):
                        try:decoded=float(value)
                        except OverflowError:continue
                        if math.isfinite(decoded):nums[field].append([decoded,local_id])
            return {"categorical":dict(cats),"numeric":dict(nums),"fields":sorted(fields)}
        posting_inputs=[{'values':record['values'],'numberLexemes':record.get('numberLexemes',{})} for _,record in group]
        shard = cache.memo("structuredPostings-lexeme-aware-2",posting_inputs,create_postings) if cache else create_postings()
        known_fields.update(shard["fields"])
        for field, posting in shard["categorical"].items():
            for value, local_ids in posting.items(): categorical[field][value].extend(group[i][0] for i in local_ids)
        for field, pairs in shard["numeric"].items(): numeric[field].extend([value,group[i][0]] for value,i in pairs)
    for pairs in numeric.values():
        pairs.sort(key=lambda pair: (pair[0], pair[1]))
    field_index_path = root / "field-index.json.gz"
    with gzip.open(field_index_path, "wt", encoding="utf-8", compresslevel=6) as stream:
        json.dump({"schemaVersion": SCHEMA_VERSION, "rowCount": len(rows), "fields": sorted(known_fields),
                   "categorical": categorical, "numeric": numeric}, stream, ensure_ascii=False,
                  separators=(",", ":"))
    write_json(schema_path, {"tables": schemas, "fields": sorted(known_fields),
                             "allowedOperations": ["eq", "in", "gt", "gte", "lt", "lte", "between", "sort", "count", "sum", "min", "max", "average"]})
    files = [_file_entry(rows_path, output_dir), _file_entry(schema_path, output_dir),
             _file_entry(field_index_path, output_dir)]
    if relations:
        relations_path = root / "relations.jsonl"
        relations_path.write_text("\n".join(json.dumps(row, ensure_ascii=False) for row in relations), encoding="utf-8")
        files.append(_file_entry(relations_path, output_dir))
    formats = _source_formats(table_units, asset_formats)
    return [{**_base_registration("structured.typed", "TYPED_RECORDS_WITH_FIELD_INDEX", "typed-jsonl-field-index-1.1", None,
                                  files, len(rows), formats),
             "buildParameters": {"equality": "typed-postings", "range": "sorted-numeric-pairs",
                                 "indexedFieldCount": len(set(categorical) | set(numeric)),
                                 "unindexedLongStringPolicy": "scan-on-demand"}}]


def _segment_features(values: np.ndarray) -> np.ndarray:
    if not len(values):
        return np.zeros(12, dtype="float32")
    mean, std = float(values.mean()), float(values.std())
    normalized = (values - mean) / (std or 1.0)
    positions = np.linspace(0.0, 1.0, len(values))
    slope = float(np.polyfit(positions, normalized, 1)[0]) if len(values) > 1 else 0.0
    diffs = np.diff(normalized)
    quantiles = np.quantile(normalized, [0, .1, .25, .5, .75, .9, 1])
    vector = np.asarray([*quantiles, slope, float(np.mean(np.abs(diffs))) if len(diffs) else 0.0,
                         float(np.max(np.abs(diffs))) if len(diffs) else 0.0, math.log1p(len(values)) / 10.0, std / (abs(mean) + std + 1e-9)], dtype="float32")
    norm = float(np.linalg.norm(vector))
    return vector / norm if norm else vector


def _build_timeseries(units: list[SemanticUnit], root: Path, output_dir: Path,
                      asset_formats: dict[str, str], cache: DerivedCache | None = None) -> list[dict]:
    temporal_units = [unit for unit in units if unit.unit_type == "TimeSeriesUnit" and unit.payload.get("facts")]
    if not temporal_units:
        return []
    facts = []
    groups: dict[tuple, list[dict]] = defaultdict(list)
    for unit in temporal_units:
        for fact in unit.payload["facts"]:
            row = {**fact, "unitId": unit.unit_id, "assetId": unit.asset_id, "datasetVersionId": unit.dataset_version_id,
                   "logicalPath": unit.logical_path, "locator": {**unit.locator, **fact.get('locator',{}), "rawRow": fact.get("rawRow")},
                   "qualityFlags": sorted(set(unit.quality_flags + fact.get("qualityFlags", []))),
                   "calculationPolicy": {key: unit.payload[key] for key in ("duplicatePolicy", "intervalBoundary", "missingPolicy", "interpolation", "outlierPolicy")}}
            facts.append(row)
            groups[(unit.asset_id, fact["entity"], fact["metric"], fact.get("unit"))].append(row)
    root.mkdir(parents=True, exist_ok=True)
    facts_path = root / "facts.jsonl"
    facts_path.write_text("\n".join(json.dumps(row, ensure_ascii=False) for row in facts), encoding="utf-8")
    aggregates, segment_meta, vectors = [], [], []
    for key, rows in groups.items():
        ordered = sorted(rows, key=lambda row: (row["normalizedTime"], row.get("rawRow") or 0))
        # Retain unresolved samples in facts so reads/queries can explain them,
        # but never derive numeric summaries or shape vectors from their cache.
        if any(row.get('calculationEligible') is False for row in rows):
            continue
        identity_free = [[row["normalizedTime"],row["value"],row.get("unit"),row.get("rawRow"),row.get("calculationPolicy")] for row in ordered]
        cached = cache.get("timeSeries",identity_free) if cache else None
        if cached is not None:
            aggregates.extend({**item,"assetId":key[0],"entity":key[1],"metric":key[2],"unit":key[3]} for item in cached["aggregates"])
            segment_meta.extend({**item,"assetId":key[0],"entity":key[1],"metric":key[2],"unit":key[3]} for item in cached["segments"])
            vectors.extend(np.asarray(vector,dtype="float32") for vector in cached["vectors"])
            continue
        aggregate_start, segment_start, vector_start = len(aggregates),len(segment_meta),len(vectors)
        values = np.asarray([row["value"] for row in ordered], dtype=float)
        times = [row["normalizedTime"] for row in ordered]
        aggregates.append({"level": "full-series", "assetId": key[0], "entity": key[1], "metric": key[2], "unit": key[3],
                           "timeStart": times[0], "timeEnd": times[-1], "count": len(values), "sum": float(values.sum()),
                           "min": float(values.min()), "max": float(values.max()), "first": float(values[0]), "last": float(values[-1]),
                           "duplicateTimestampCount": len(times) - len(set(times)), "mergeable": ["count", "sum", "min", "max"]})
        frame = __import__("pandas").DataFrame({"time": __import__("pandas").to_datetime(times, utc=True, format="ISO8601"), "value": values}).set_index("time")
        for level, rule in (("hour", "1h"), ("day", "1D")):
            for bucket, group in frame.resample(rule):
                if group.empty:
                    continue
                bucket_values = group["value"].to_numpy(dtype=float)
                aggregates.append({"level": level, "assetId": key[0], "entity": key[1], "metric": key[2], "unit": key[3],
                                   "timeStart": bucket.isoformat().replace("+00:00", "Z"),
                                   "timeEnd": (bucket + __import__("pandas").tseries.frequencies.to_offset(rule)).isoformat().replace("+00:00", "Z"),
                                   "count": len(bucket_values), "sum": float(bucket_values.sum()), "min": float(bucket_values.min()),
                                   "max": float(bucket_values.max()), "first": float(bucket_values[0]), "last": float(bucket_values[-1]),
                                   "mergeable": ["count", "sum", "min", "max"]})
        # Overlapping windows preserve short events and events spanning a boundary.
        window = min(128, max(8, len(values)))
        stride = max(1, window // 2)
        starts = list(range(0, max(1, len(values) - window + 1), stride)) or [0]
        if starts[-1] + window < len(values):
            starts.append(max(0, len(values) - window))
        for start in starts:
            end = min(len(values), start + window)
            segment = values[start:end]
            segment_meta.append({"assetId": key[0], "entity": key[1], "metric": key[2], "unit": key[3],
                                 "timeStart": times[start], "timeEnd": times[end - 1], "values": segment.tolist(), "normalized": "zscore",
                                 "window": window, "stride": stride})
            vector = cache.memo("timeWindowFeature",segment.tolist(),lambda: _segment_features(segment).tolist()) if cache else _segment_features(segment)
            vectors.append(np.asarray(vector,dtype="float32"))
        if cache:
            cache.put("timeSeries",identity_free,{"aggregates":aggregates[aggregate_start:],"segments":segment_meta[segment_start:],
                                                 "vectors":[vector.tolist() for vector in vectors[vector_start:]]})
    aggregate_path = root / "aggregates.json"
    segment_path = root / "segments.json"
    feature_path = root / "shape.faiss"
    write_json(aggregate_path, {"levels": ["full-series", "hour", "day"], "aggregates": aggregates})
    write_json(segment_path, {"segments": segment_meta, "dtw": {"windowRatio": 0.15, "timeWarping": True, "amplitudeNormalization": "zscore", "scaleInvariant": False}})
    index = faiss.IndexFlatIP(12)
    if vectors:index.add(np.asarray(vectors, dtype="float32").reshape(-1,12))
    faiss.write_index(index, str(feature_path))
    formats = _source_formats(temporal_units, asset_formats)
    registrations = [
        _base_registration("timeseries.facts", "EXACT_FACTS_AGGREGATES", "timeseries-exact-1.0", None,
                           [_file_entry(facts_path, output_dir), _file_entry(aggregate_path, output_dir)], len(facts), formats),
        _base_registration("timeseries.shape", "FAISS_FEATURE_DTW", "shape12-dtw-1.0", 12,
                           [_file_entry(feature_path, output_dir), _file_entry(segment_path, output_dir)], len(segment_meta), formats),
    ]
    for registration in registrations:
        registration['coverage'].update(sourceSampleCount=len(facts),calculationEligibleSampleCount=sum(row.get('calculationEligible',True) for row in facts),
            uncalculableSampleCount=sum(row.get('calculationEligible') is False for row in facts),shapeVectorCount=len(vectors))
    return registrations


def _build_geometry(units: list[SemanticUnit], root: Path, output_dir: Path,
                    asset_formats: dict[str, str]) -> list[dict]:
    geometry_units = [unit for unit in units if unit.unit_type == "GeometryUnit"]
    eligible = [unit for unit in geometry_units if unit.payload.get("featureVector")]
    if not geometry_units:
        return []
    root.mkdir(parents=True, exist_ok=True)
    facts_path = root / "facts.json"
    write_json(facts_path, {"geometry": [unit.to_dict() for unit in geometry_units],
                            "invariance": {"translation": True, "rotation": "per-item; OBJ principal moments exact, STEP AABB approximate",
                                           "shapeScale": True, "absoluteDimensionVerification": "query-selectable"},
                            "unsupportedEngineeringFeatures": ["hole-tolerance", "interface-fit", "manufacturing-interchangeability"]})
    geometry_formats = _source_formats(geometry_units, asset_formats)
    registrations = [_base_registration("geometry.properties", "TYPED_GEOMETRY", "geometry-properties-1.0", None,
                                        [_file_entry(facts_path, output_dir)], len(geometry_units), geometry_formats)]
    # Equal dimensions do not make mesh moments and CAD AABB features comparable.
    partitions = defaultdict(list)
    for unit in eligible:
        feature_space = unit.payload.get("featureSpaceId") or ("cad-aabb-brep-v1" if unit.parser_name == "step-opencascade" else "mesh-principal-moments-v1")
        partitions[feature_space].append(unit)
    for feature_space, partition in sorted(partitions.items()):
        eligible = partition
        dimension = max(len(unit.payload["featureVector"]) for unit in eligible)
        matrix = np.zeros((len(eligible), dimension), dtype="float32")
        for row, unit in enumerate(eligible):
            vector = np.asarray(unit.payload["featureVector"], dtype="float32")
            matrix[row, :len(vector)] = vector
        norms = np.linalg.norm(matrix, axis=1, keepdims=True)
        matrix = matrix / np.maximum(norms, 1e-12)
        partition_root = root / feature_space
        partition_root.mkdir(parents=True, exist_ok=True)
        index_path, meta_path = partition_root / "shape.faiss", partition_root / "shape.json"
        index = faiss.IndexFlatIP(dimension)
        index.add(matrix)
        faiss.write_index(index, str(index_path))
        write_json(meta_path, {"items": [unit.to_dict() for unit in eligible]})
        eligible_formats = _source_formats(eligible, asset_formats)
        semantic_space = "geometry.shape.cad" if feature_space.startswith("cad-") else "geometry.shape"
        registrations.append(_base_registration(semantic_space, "FAISS_FEATURE_VERIFY", feature_space, dimension,
                                                [_file_entry(index_path, output_dir), _file_entry(meta_path, output_dir)], len(eligible), eligible_formats))
    return registrations


def build_professional_indexes(input_dir: Path, output_dir: Path, dataset_version_id: str,
                               *, timezone_name: str | None = None, unit_mapping: dict[str, str] | None = None,
                               time_column: str | None = None, entity_columns: list[str] | None = None,
                               asset_mappings: dict | None = None,
                               snapshot_identity: dict | None = None,
                               previous_asset_dir: Path | None = None) -> dict:
    if not snapshot_identity or not all(snapshot_identity.get(key) for key in ('datasetId','indexVersionId','rawCommitId')):
        raise ValueError('EVIDENCE_FROZEN_SNAPSHOT_IDENTITY_REQUIRED')
    registry = default_parser_registry()
    derived = DerivedCache(output_dir / "professional" / "derived", previous_asset_dir.parent / "derived" if previous_asset_dir else None)
    organizer = SemanticOrganizer()
    assets, units = [], []
    previous_assets = _load_previous_assets(previous_asset_dir)
    reused_asset_count = 0
    parsed_asset_count = 0
    current_paths: set[str] = set()
    processing_config = {"timezone": timezone_name, "unitMapping": unit_mapping or {},
                         "timeColumn": time_column, "entityColumns": entity_columns or []}
    ignored_names = {"manifest.json", "id_map.parquet", "files.json", "files.tsv"}
    for path in sorted(input_dir.rglob("*")):
        if not path.is_file() or path.name.lower() in ignored_names:
            continue
        relative = path.relative_to(input_dir)
        if any(part.lower() in {"indexes", "representations"} for part in relative.parts):
            continue
        parser = registry.resolve(path)
        if parser is None:
            continue
        logical_path = normalized_path(relative.as_posix())
        current_paths.add(logical_path)
        local_config = {**processing_config, **(asset_mappings or {}).get(logical_path, {})}
        context = ParseContext(dataset_version_id=dataset_version_id, logical_path=relative.as_posix(), output_dir=output_dir,
                               timezone=local_config.get("timezone"), unit_mapping=local_config.get("unitMapping", {}), time_column=local_config.get("timeColumn"),
                               entity_columns=local_config.get("entityColumns", []))
        cached_result = _reuse_cached_asset(previous_assets.get(logical_path, {}), path, relative,
                                             dataset_version_id, parser, local_config)
        if cached_result is not None:
            asset, asset_units = cached_result
            reused_asset_count += 1
        else:
            parsed_asset_count += 1
            try:
                asset = parser.parse(path, context)
            except Exception as exception:
                asset = ParsedAsset(
                    asset_id=deterministic_asset_id(dataset_version_id, logical_path),
                    dataset_version_id=dataset_version_id,
                    logical_path=logical_path,
                    source_format=path.suffix.lstrip(".").upper(),
                    content_sha256=content_sha256(path),
                    parser_name=parser.name,
                    parser_version=parser.version,
                    parser_config_version=PARSER_CONFIG_VERSION,
                    items=[],
                    attributes={"parseError": str(exception)},
                    quality_flags=["PARSE_FAILED", type(exception).__name__],
                )
            asset_units = organizer.organize(asset, timezone_name=context.timezone, time_column=context.time_column,
                                             entity_columns=context.entity_columns)
        assets.append(asset)
        units.extend(asset_units)
        write_json(output_dir / "professional" / "assets" / f"{asset.asset_id}.json", {
            "asset": asset, "units": [unit.to_dict() for unit in asset_units],
            "processingConfig": local_config,
        })
    catalog = SnapshotCatalog(input_dir, assets, units)
    from .native_evidence import NativeEvidenceWriter
    evidence_manifests={}
    # Persist enriched units after snapshot-local catalog validation.
    for asset in assets:
        target = output_dir / "professional" / "assets" / f"{asset.asset_id}.json"
        cached = json.loads(target.read_text(encoding="utf-8"))
        cached["units"] = [unit.to_dict() for unit in catalog.by_asset[asset.asset_id]]
        write_json(target, cached)
        evidence_manifests[asset.asset_id]=NativeEvidenceWriter(asset,catalog.by_asset[asset.asset_id],output_dir,snapshot_identity,
            processing_config=cached['processingConfig']).write()
        catalog.descriptions[asset.asset_id]['nativeEvidence']={
            'schemaVersion':evidence_manifests[asset.asset_id]['schemaVersion'],
            'manifestSha256':evidence_manifests[asset.asset_id]['sha256'],
            'representationCount':evidence_manifests[asset.asset_id]['representationCount']}
    asset_formats = {asset.asset_id: asset.source_format for asset in assets}
    root = output_dir / "indexes"
    registrations = []
    registrations.extend(_build_text(units, root / "professional-text", output_dir, asset_formats, derived))
    registrations.extend(_build_structured(units, root / "structured", output_dir, asset_formats, derived))
    registrations.extend(_build_timeseries(units, root / "timeseries", output_dir, asset_formats, derived))
    registrations.extend(_build_geometry(units, root / "geometry", output_dir, asset_formats))
    facets_path = root / "snapshot" / "facets.json"
    write_json(facets_path, catalog.describe())
    registrations.append(_base_registration("snapshot.facets", "SNAPSHOT_CAPABILITIES", CATALOG_VERSION, None,
        [_file_entry(facets_path, output_dir)], len(assets), sorted(set(asset_formats.values()))))
    derived.flush()
    catalog_path = output_dir / "professional" / "manifest.json"
    deleted_paths = sorted(set(previous_assets) - current_paths)
    incremental = {"candidateAssetCount": len(previous_assets), "reusedAssetCount": reused_asset_count,
                   "parsedAssetCount": parsed_asset_count, "deletedAssetCount": len(deleted_paths),
                   "deletedLogicalPaths": deleted_paths,
                   "reusePolicy": "content-sha256+parser-version+parser-config-version+organizer-version"}
    incremental["derivedArtifacts"] = dict(derived.stats)
    incremental["packagingPolicy"] = "reuse content-addressed embeddings, asset postings and series/window computations; assemble immutable global indexes"
    write_json(catalog_path, {
        "schemaVersion": SCHEMA_VERSION, "datasetVersionId": dataset_version_id,
        "parserRegistry": sorted(registry.supported_extensions), "assetCount": len(assets), "unitCount": len(units),
        "incremental": incremental,
        "assets": [{"assetId": asset.asset_id, "logicalPath": asset.logical_path, "contentSha256": asset.content_sha256,
                    "sourceFormat": asset.source_format, "parser": f"{asset.parser_name}:{asset.parser_version}",
                    "evidenceManifest":evidence_manifests[asset.asset_id],
                    "qualityFlags": asset.quality_flags} for asset in assets],
    })
    return {"indexes": registrations, "professionalManifestPath": "/professional/manifest.json",
            "professionalManifest": _file_entry(catalog_path, output_dir), "assetCount": len(assets), "unitCount": len(units),
            "incremental": incremental}
