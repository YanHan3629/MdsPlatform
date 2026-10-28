"""XDE leaf-instance extraction. No assembly-wide properties relabelled as parts."""
from __future__ import annotations

import math
import time
import re
import importlib.metadata
from contextlib import nullcontext
from pathlib import Path

import numpy as np

from .contracts import ParsedItem,content_sha256,deterministic_asset_id
from .cpu_queue import cpu_evidence_slot
from .geometry_surface import render_views,VERSION as SURFACE_VERSION,MAX_TRIANGLES,MAX_VERTICES,MAX_SOURCE_BYTES

VERSION='occt-xde-surface-2026-10-22.2'


def _triangulate(shape,instance_id):
    from OCP.BRepMesh import BRepMesh_IncrementalMesh
    from OCP.BRep import BRep_Tool
    from OCP.TopAbs import TopAbs_FACE,TopAbs_REVERSED
    from OCP.TopExp import TopExp_Explorer
    from OCP.TopLoc import TopLoc_Location
    from OCP.TopoDS import TopoDS
    mesher=BRepMesh_IncrementalMesh(shape,.15,False,.35,False)
    if not mesher.IsDone():raise ValueError('CAD_TESSELLATION_FAILED')
    vertices=[];triangles=[];face_ranges=[];explorer=TopExp_Explorer(shape,TopAbs_FACE);face_index=0
    while explorer.More():
        face_index+=1;face=TopoDS.Face_s(explorer.Current());location=TopLoc_Location()
        mesh=BRep_Tool.Triangulation_s(face,location)
        if mesh is None:raise ValueError('CAD_FACE_TRIANGULATION_MISSING')
        offset=len(vertices);start=len(triangles);transform=location.Transformation()
        if offset+mesh.NbNodes()>MAX_VERTICES or start+mesh.NbTriangles()>MAX_TRIANGLES:raise ValueError('CAD_TESSELLATION_SIZE_LIMIT')
        for index in range(1,mesh.NbNodes()+1):
            point=mesh.Node(index).Transformed(transform);vertices.append([point.X(),point.Y(),point.Z()])
        for index in range(1,mesh.NbTriangles()+1):
            a,b,c=mesh.Triangle(index).Get()
            if face.Orientation()==TopAbs_REVERSED:b,c=c,b
            triangles.append([offset+a-1,offset+b-1,offset+c-1])
        face_ranges.append({'faceIndex':face_index,'faceIndexBase':1,'triangleStart':start,'triangleEndExclusive':len(triangles)})
        explorer.Next()
    if not triangles:raise ValueError('CAD_NO_DISPLAY_SURFACE')
    return {'instanceId':instance_id,'vertices':np.asarray(vertices,dtype=float),'triangles':np.asarray(triangles,dtype=np.int32)},face_ranges


def _extract(path,*,_already_queued=False):
    from OCP.BRepBndLib import BRepBndLib
    from OCP.BRepCheck import BRepCheck_Analyzer
    from OCP.BRepGProp import BRepGProp
    from OCP.Bnd import Bnd_Box
    from OCP.GProp import GProp_GProps
    from OCP.IFSelect import IFSelect_RetDone
    from OCP.STEPCAFControl import STEPCAFControl_Reader
    from OCP.TCollection import TCollection_ExtendedString, TCollection_AsciiString
    from OCP.TDataStd import TDataStd_Name
    from OCP.TDF import TDF_Label, TDF_LabelSequence, TDF_Tool
    from OCP.TDocStd import TDocStd_Document
    from OCP.TopAbs import TopAbs_FACE, TopAbs_SOLID
    from OCP.TopExp import TopExp_Explorer
    from OCP.TopLoc import TopLoc_Location
    from OCP.XCAFDoc import XCAFDoc_DocumentTool
    from OCP.TColStd import TColStd_SequenceOfAsciiString

    document = TDocStd_Document(TCollection_ExtendedString("BinXCAF"))
    reader = STEPCAFControl_Reader()
    reader.SetNameMode(True)
    with (nullcontext() if _already_queued else cpu_evidence_slot()):
        started=time.monotonic()
        if path.stat().st_size>MAX_SOURCE_BYTES:raise ValueError('STEP_SOURCE_BYTE_LIMIT')
        # XDE can resolve STEP external-reference documents. These are outside
        # the frozen single-asset read contract and must never be followed.
        if re.search(rb'\b(?:EXTERNALLY_DEFINED_ITEM|DOCUMENT_FILE|EXTERNAL_SOURCE|APPLIED_EXTERNAL_IDENTIFICATION_ASSIGNMENT)\s*\(',path.read_bytes(),re.I):
            raise ValueError('STEP_EXTERNAL_DOCUMENT_REFERENCE_UNSUPPORTED')
        if reader.ReadFile(str(path)) != IFSelect_RetDone:
            raise ValueError("STEP_READ_FAILED")
        length_names,angle_names,solid_angle_names=(TColStd_SequenceOfAsciiString() for _ in range(3))
        reader.Reader().FileUnits(length_names,angle_names,solid_angle_names)
        source_units={key:[sequence.Value(i).ToCString() for i in range(1,sequence.Length()+1)]
            for key,sequence in [('length',length_names),('angle',angle_names),('solidAngle',solid_angle_names)]}
        # OCCT normalizes STEP's declared source units to this explicit target.
        reader.ChangeReader().SetSystemLengthUnit(1.0)
        if not reader.Transfer(document): raise ValueError("STEP_TRANSFER_FAILED")
        tool = XCAFDoc_DocumentTool.ShapeTool_s(document.Main())
        roots = TDF_LabelSequence()
        tool.GetFreeShapes(roots)
        items, instances, flags,display_meshes = [], [], [],[]
        if not source_units['length']:flags.append('STEP_LENGTH_UNIT_UNDECLARED')

        def entry(label):
            value = TCollection_AsciiString()
            TDF_Tool.Entry_s(label, value)
            return value.ToCString()

        def name(label):
            attribute = TDataStd_Name()
            return str(attribute.Get().ToExtString()) if label.FindAttribute(TDataStd_Name.GetID_s(), attribute) else entry(label)

        def walk(label, parent_location, ancestry, depth=0):
            if depth > 32 or len(instances) >= 4096: raise ValueError("CAD_ASSEMBLY_BUDGET_EXCEEDED")
            if time.monotonic()-started>120:raise TimeoutError('CAD_EXTRACTION_TIME_LIMIT')
            definition = TDF_Label()
            is_reference = tool.GetReferredShape_s(label, definition)
            if not is_reference: definition = label
            local_location=tool.GetLocation_s(label)
            location = parent_location.Multiplied(local_location)
            instance_id = "/".join(ancestry + [entry(label)])
            transform = location.Transformation()
            matrix = [[transform.Value(row, col) for col in range(1, 5)] for row in range(1, 4)] + [[0, 0, 0, 1]]
            record = {"instanceId": instance_id, "partId": entry(definition), "name": name(definition),
                      "parentInstanceId": "/".join(ancestry) or None, "worldTransform": matrix,
                      'localTransform':[[local_location.Transformation().Value(row,col) for col in range(1,5)] for row in range(1,4)]+[[0,0,0,1]],
                      'assemblyPath':ancestry+[entry(label)],'isReference':is_reference}
            instances.append(record)
            children = TDF_LabelSequence()
            if tool.GetComponents_s(definition, children, False) and children.Length():
                record['nodeType']='assembly'
                for index in range(1, children.Length() + 1):
                    walk(children.Value(index), location, ancestry + [entry(label)], depth + 1)
                return
            record['nodeType']='leaf-instance'
            local_shape = tool.GetShape_s(definition).Located(TopLoc_Location())
            shape = local_shape.Moved(location)
            if shape.IsNull(): raise ValueError("CAD_NULL_LEAF")
            valid = BRepCheck_Analyzer(shape).IsValid()
            box = Bnd_Box(); BRepBndLib.AddOptimal_s(shape, box,False,False)
            xmin, ymin, zmin, xmax, ymax, zmax = box.Get()
            size = np.asarray([xmax-xmin, ymax-ymin, zmax-zmin])
            surface, volume = GProp_GProps(), GProp_GProps()
            BRepGProp.SurfaceProperties_s(shape, surface)
            BRepGProp.VolumeProperties_s(shape, volume)
            counts = []
            for kind in (TopAbs_FACE, TopAbs_SOLID):
                explorer, count = TopExp_Explorer(shape, kind), 0
                while explorer.More(): count += 1; explorer.Next()
                counts.append(count)
            face_count, solid_count = counts
            scale = float(np.linalg.norm(size)) or 1.0
            payload = {**record, "kernel": "OpenCASCADE-XDE", "exactGeometry": valid,
                       "bounds": {"minimum": [xmin,ymin,zmin], "maximum": [xmax,ymax,zmax], "size": size.tolist()},
                       "surfaceArea": surface.Mass(), "volume": abs(volume.Mass()) if valid and solid_count else None,
                       "faceCount": face_count, "solidCount": solid_count, "unitSource": "STEP declared units -> OCCT millimetres" if source_units['length'] else 'OCCT default; source unit undeclared',
                       'sourceUnits':source_units,'targetLengthUnit':'mm','targetSystemLengthUnitInMillimetres':1.0,
                       'physicalUnitVerified':bool(source_units['length']),
                       'volumeValidation':'valid BRep with solids; instance property, not assembly union' if valid and solid_count else 'unavailable',
                       'measurementAlgorithm':'OCCT BRepGProp; independent of display triangulation',
                       'unsupportedOperations':['hole_diameter','wall_thickness','clearance','interference','manufacturing_approval'],
                       "featureSpaceId": "cad-aabb-brep-v2", "shapeFeatureRotationInvariant": False, "shapeFeatureScaleInvariant": True}
            if valid:
                payload["featureVector"] = [*np.sort(size/scale)[::-1].tolist(), surface.Mass()/scale**2,
                                            abs(volume.Mass())/scale**3 if solid_count else 0.0,
                                            math.log1p(face_count)/20, math.log1p(solid_count)/5, float(bool(solid_count))]
            part_flags = [] if valid else ["CAD_INVALID_BREP"]
            display,ranges=_triangulate(shape,instance_id);display_meshes.append(display)
            payload['displayMesh']={'recipeVersion':VERSION,'linearDeflection':.15,'linearDeflectionUnit':'mm','relativeDeflection':False,
                'angularDeflectionRadians':.35,'triangleCount':len(display['triangles']),'vertexCount':len(display['vertices']),
                'faceTriangleRanges':ranges,'approximateDisplayOnly':True,'sourceFaceIndicesStableWithinFrozenParser':True}
            items.append(ParsedItem("geometry", payload, {"partId": entry(definition), "instanceId": instance_id},
                                    entity_id=instance_id, units={"length":"mm" if source_units['length'] else None,
                                        "area":"mm2" if source_units['length'] else None,"volume":"mm3" if source_units['length'] else None}, quality_flags=part_flags))
            items.append(ParsedItem("text", name(definition), {"partId": entry(definition), "instanceId": instance_id}, entity_id=instance_id))
            flags.extend(part_flags)

        for index in range(1, roots.Length()+1): walk(roots.Value(index), TopLoc_Location(), [])
        geometries = [item for item in items if item.kind == "geometry"]
        if not geometries: raise ValueError("CAD_NO_LEAF_GEOMETRY")
        attributes = {"assemblyInstances": instances, "partCount": len(geometries),'leafInstanceCount':len(geometries),
                      'uniquePartDefinitionCount':len({item.payload['partId'] for item in geometries}),
                      'assemblyNodeCount':sum(row['nodeType']=='assembly' for row in instances),"kernel": "OpenCASCADE-XDE",
                      'sourceUnits':source_units,'targetLengthUnit':'mm','physicalUnitVerified':bool(source_units['length']),
                      'assemblyUnionVolumeAvailable':False,
                      "exactGeometry": all(item.payload["exactGeometry"] for item in geometries)}
        if len(geometries) == 1: attributes.update(geometries[0].payload)
        return items,attributes,sorted(set(flags)),display_meshes


def read_cad_asset(parser,path:Path,context):
    try:
        items,attributes,flags,display_meshes=_extract(path)
        engine={'kernelDistribution':'cadquery-ocp','kernelVersion':importlib.metadata.version('cadquery-ocp'),
            'adapterVersion':VERSION,'linearDeflection':.15,'angularDeflectionRadians':.35,'parallelMeshing':False}
        target=context.output_dir/'representations'/deterministic_asset_id(context.dataset_version_id,context.logical_path)/'visual'/SURFACE_VERSION
        views=render_views(display_meshes,target,content_sha256(path),engine=engine)
        attributes.update(surfaceViews=views,engineeringAdapters={**engine,'surfaceRenderVersion':SURFACE_VERSION})
        items.extend(ParsedItem('visual',{'path':view['path'],'caption':f"{path.name} {view['view']} OCCT surface; display colours"},
            {'instanceIds':view['instanceIds'],'view':view['view'],'renderRecipeVersion':SURFACE_VERSION,'sourceSha256':view['sourceSha256'],
             'outputSha256':view['outputSha256'],'camera':view['worldToImageDepthMatrix']}) for view in views)
        return parser._asset(path,context,items,attributes,flags)
    except Exception as exc:
        return parser._asset(path, context, [], {"exactGeometry": False, "parseError": str(exc)},
                             ["CAD_EXACT_GEOMETRY_UNAVAILABLE", type(exc).__name__])
