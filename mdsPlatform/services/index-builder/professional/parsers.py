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
from .json_native import decode_document, document_items, records_payload,record_calculation_eligibility,NUMBER_ENCODING_VERSION

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
    raw = path.read_bytes()
    for encoding in ("utf-8-sig", "utf-8", "gb18030"):
        try:
            return raw.decode(encoding)
        except UnicodeDecodeError:
            continue
    # Replacing undecodable bytes would manufacture source text. The pipeline
    # records PARSE_FAILED instead of silently treating replacement glyphs as facts.
    return raw.decode("utf-8")


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
    version = "2.1"
    extensions = (".jpg", ".jpeg", ".png")

    def parse(self, path: Path, context: ParseContext) -> ParsedAsset:
        from .document_native import image_content
        text_items,attrs,flags=image_content(path)
        items = [
            ParsedItem("visual", {"path": str(path), "caption": path.name}, {"region": "full-image"}),
            ParsedItem("text", path.stem.replace("_", " "), {"source": "filename"}),
        ]
        return self._asset(path, context, [*items,*text_items], attrs,flags)


class TextParser(AssetParser):
    name = "text"
    version = "2.0"
    extensions = (".txt", ".md")

    def parse(self, path: Path, context: ParseContext) -> ParsedAsset:
        text = _read_text(path)
        # Measurement TXT is a description + JSON metadata + TSV table. Preserve
        # the source lines and parse the actual samples instead of indexing a caption.
        lines = text.splitlines()
        if len(lines) >= 3 and lines[1].lstrip().startswith("{") and "\t" in lines[2]:
            metadata = json.loads(lines[1])
            if "timestamp" in lines[2].split("\t") and metadata.get("series_id"):
                from .table_native import parse_csv
                source_lines=text.splitlines(keepends=True);prefix=''.join(source_lines[:2])
                tables,_,flags=parse_csv(''.join(source_lines[2:]),{**context.unit_mapping,**metadata.get('units',{})},delimiter='\t',line_offset=2)
                tables[0].locator['sheet']='samples'
                description=ParsedItem('text',prefix,{'characterStart':0,'characterEnd':len(prefix),'characterIndexUnit':'unicode-codepoint','lineStart':1,'lineEnd':2,'contentRole':'measurement-description-and-metadata'})
                return self._asset(path, context, [description,*tables], {"measurementMetadata": metadata,'characterCount':len(text),
                    'measurementDescriptionPreserved':True,'sampleHeaderPhysicalLine':3,
                    "sourceTimezone": metadata.get("timezone") or ("Asia/Shanghai" if "Asia/Shanghai" in lines[0] else context.timezone)},flags)
        items = [ParsedItem("text", chunk, {"characterStart": start, "characterEnd": end,'characterIndexUnit':'unicode-codepoint'})
                 for start, end, chunk in _chunks(text)]
        return self._asset(path, context, items, {"characterCount": len(text)})


class DocumentParser(AssetParser):
    name = "pdf"
    version = "2.1"
    extensions = (".pdf",)

    def parse(self, path: Path, context: ParseContext) -> ParsedAsset:
        from .document_native import pdf_content
        items,attributes,flags=pdf_content(path,context)
        return self._asset(path,context,items,attributes,flags)


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


def _frame_records(frame: pd.DataFrame, raw_row_start: int = 2) -> dict:
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
    for position, (_, row) in enumerate(frame.iterrows(), start=raw_row_start):
        rows.append({"rawRow": position, "values": {str(key): _scalar(value) for key, value in row.items()}})
    return {"columns": columns, "records": rows}


def read_workbook_tables(path):
    """Return frames and one-based source data row starts after title rows."""
    sheets = {}
    for name, raw in pd.read_excel(path, sheet_name=None, header=None).items():
        nonempty=[(index,row.dropna().tolist()) for index,row in raw.head(30).iterrows()]
        header=next((index for index,values in nonempty if len(values)>=max(2,math.ceil(len(raw.columns)*.6)) and
                     all(isinstance(value,str) and value.strip() for value in values) and
                     len(set(values))==len(values)),None)
        if header is None:
            header=next((index for index,values in nonempty if values),0)
        frame=pd.read_excel(path,sheet_name=name,header=int(header))
        frame.attrs['rawRowStart']=int(header)+2
        sheets[name]=(frame,int(header)+2)
    return sheets


class TableParser(AssetParser):
    name = "table"
    version = "3.2"
    extensions = (".csv", ".xlsx")

    def parse(self, path: Path, context: ParseContext) -> ParsedAsset:
        from .table_native import parse_csv, parse_xlsx
        if path.suffix.lower() == ".csv":
            items, attributes, flags = parse_csv(_read_text(path), context.unit_mapping)
        else:
            items, attributes, flags = parse_xlsx(path, context.unit_mapping)
        return self._asset(path, context, items, attributes, flags)


class JsonParser(AssetParser):
    name = "json"
    version = "3.0"
    extensions = (".json", ".jsonl")

    def parse(self, path: Path, context: ParseContext) -> ParsedAsset:
        text = _read_text(path)
        items = []
        attributes = {"structured": True, "nativeTreeComplete": True,
                      "jsonPointerStandard": "RFC6901", "arrayIndexBase": 0,
                      "numericPolicy": "original-number-types-and-source-lexemes; finite-decoded-values-are-conveniences",
                      'numberEncodingVersion':NUMBER_ENCODING_VERSION,'numericCalculationDigitLimit':4096,
                      'numericTokenByteLimit':32768,'nativeDecodedIntegerSafeLimit':9007199254740991}
        if path.suffix.lower() == ".jsonl":
            documents, records = [], []
            lines = text.splitlines()
            for line_number, line in enumerate(lines, start=1):
                if not line.strip():
                    continue
                value, numbers = decode_document(line)
                documents.append({"physicalLine": line_number, "value": value, "numberLexemes": numbers})
                if isinstance(value, dict):
                    records.append({"rawRow": line_number, "values": value, "numberLexemes": numbers,
                                    'calculationEligibility':record_calculation_eligibility(value,numbers,''),
                                    "locator": {"jsonlLine": line_number, "jsonPointer": ""}})
                items.extend(document_items(value, numbers, context.unit_mapping,
                                            physical_line=line_number, root_is_record=isinstance(value, dict)))
            if records:
                items.insert(0, ParsedItem("table", records_payload(records), {
                    "jsonPath": "$", "jsonPointer": "", "rawRowStart": 1,
                    "rowNumbering": "physical-line", "blankLinesPreserved": True,
                }, units=dict(context.unit_mapping)))
            attributes.update(nativeDocuments=documents, physicalLineCount=len(lines),
                              documentCount=len(documents), blankLineCount=len(lines) - len(documents))
        else:
            value, numbers = decode_document(text)
            attributes.update(nativeDocument=value, numberLexemes=numbers)
            items = document_items(value, numbers, context.unit_mapping)
        attributes["tableCount"] = sum(item.kind == "table" for item in items)
        return self._asset(path, context, items, attributes)


class SvgParser(AssetParser):
    name = "svg"
    version = "2.2"
    extensions = (".svg",)

    def parse(self, path: Path, context: ParseContext) -> ParsedAsset:
        from .svg_native import read_svg,render_svg,VERSION,RENDER_VERSION
        import cairosvg,defusedxml
        parsed=read_svg(path)
        preview=context.output_dir/'representations'/deterministic_asset_id(context.dataset_version_id,context.logical_path)/'visual'/RENDER_VERSION/'source.png'
        view=render_svg(parsed,preview)
        items=[ParsedItem('text',row['text'],{'svgElementId':row['elementId'],'elementPath':row['elementPath'],
            'coordinateSystem':'root-user-space','worldTransform':row['worldTransform']}) for row in parsed['texts']]
        items.append(ParsedItem('visual',{'path':str(preview),'caption':path.name+' static SVG; source declarations, not 3D measurement'},
            {'region':'full-svg','bbox':view['bbox'],'coordinateSystem':'root-user-space','renderRecipeVersion':RENDER_VERSION,'outputSha256':view['outputSha256']}))
        attrs={**parsed['viewport'],'svgNative':{key:value for key,value in parsed.items() if key not in {'root','sourceSha256'}},
            'svgViews':[view],'metadataNature':'source_declaration','unsupportedOperations':parsed['unsupportedOperations'],
            'engineeringAdapters':{'svgNativeVersion':VERSION,'svgRenderVersion':RENDER_VERSION,'CairoSVG':cairosvg.__version__,
                'defusedxml':defusedxml.__version__,'renderEngine':view['engine']}}
        return self._asset(path,context,items,attrs,[])


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
    version = "3.1"
    extensions = (".obj",)

    def parse(self, path: Path, context: ParseContext) -> ParsedAsset:
        from .geometry_surface import read_obj,render_views,VERSION
        mesh=read_obj(path)
        if not len(mesh['triangles']):raise ValueError('OBJ_NO_SURFACE_FACES')
        # Unused vertices are retained as source records, but cannot enlarge the
        # measured surface or the camera framing.
        referenced=np.unique(mesh['triangles']);surface_vertices=mesh['vertices'][referenced]
        inverse=np.full(len(mesh['vertices']),-1,dtype=np.int32);inverse[referenced]=np.arange(len(referenced))
        triangles=inverse[mesh['triangles']]
        attrs,_=_mesh_features(surface_vertices,triangles.tolist())
        attrs.update(sourceVertexCount=len(mesh['vertices']),unusedVertexCount=len(mesh['vertices'])-len(referenced),
            sourceFaceCount=len(mesh['faceRecords']),triangleCount=len(triangles),measurementAlgorithm='source-surface-triangulation-float64',
            coordinateSystem='unmodified-source-XYZ',featureSpaceId='mesh-principal-moments-v3',
            materialDeclarations=mesh['externalMaterialReferences'],sourceMaterialsLoaded=False,
            unsupportedOperations=['hole_diameter','wall_thickness','clearance','interference','manufacturing_approval'])
        length=context.unit_mapping.get('length')
        if length and length not in {'mm','cm','m','um','in','ft'}:raise ValueError('OBJ_UNSUPPORTED_LENGTH_UNIT_MAPPING')
        units={'length':length,'area':length+'2' if length else None,'volume':length+'3' if length else None}
        attrs.update(unitSource='explicit-processing-config.length' if length else 'unknown',units=units,
            physicalUnitVerified=bool(length),unitInference='none; filenames and unrelated mappings are not unit declarations')
        flags=[]
        if not length:flags.append('UNIT_UNKNOWN')
        if not attrs["closed"]:
            flags.append("NON_CLOSED_MESH")
        if attrs["closed"] and not attrs["orientationConsistent"]:
            flags.append("INCONSISTENT_FACE_ORIENTATION")
        if attrs["degenerateFaces"]:
            flags.append("DEGENERATE_MESH_FACE")
        flags.append("MESH_SELF_INTERSECTION_NOT_CERTIFIED")
        target=context.output_dir/'representations'/deterministic_asset_id(context.dataset_version_id,context.logical_path)/'visual'/VERSION
        views=render_views([{'instanceId':'root','vertices':surface_vertices,'triangles':triangles}],target,mesh['sourceSha256'],
            engine={'parser':self.name,'parserVersion':self.version,'triangulation':'simple-planar-polygons-ear-clipping'})
        attrs['surfaceViews']=views
        properties={key:value for key,value in attrs.items() if key!='surfaceViews'}
        attrs['meshSource']={key:mesh[key] for key in ('vertexRecords','faceRecords','triangleSourceFaceIds')}
        attrs['engineeringAdapters']={'surfaceRenderVersion':VERSION,'objParserVersion':self.version,'numpyVersion':np.__version__}
        items=[ParsedItem('geometry',properties,{'partId':'root','instanceId':'root'},entity_id='root',units=units,quality_flags=flags)]
        items.extend(ParsedItem('visual',{'path':view['path'],'caption':f"{path.name} {view['view']} surface; display colours; unit {length or 'unknown'}"},
            {'partId':'root','instanceIds':['root'],'view':view['view'],'renderRecipeVersion':VERSION,'sourceSha256':mesh['sourceSha256'],
             'outputSha256':view['outputSha256'],'camera':view['worldToImageDepthMatrix']}) for view in views)
        return self._asset(path,context,items,attrs,flags)


class CadParser(AssetParser):
    name = "step-opencascade"
    version = "3.1"
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
