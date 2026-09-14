"""XDE leaf-instance extraction. No assembly-wide properties relabelled as parts."""
from __future__ import annotations

import math
from pathlib import Path

import numpy as np

from .contracts import ParsedItem


def read_cad_asset(parser, path: Path, context):
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

    document = TDocStd_Document(TCollection_ExtendedString("BinXCAF"))
    reader = STEPCAFControl_Reader()
    reader.SetNameMode(True)
    try:
        if reader.ReadFile(str(path)) != IFSelect_RetDone:
            raise ValueError("STEP_READ_FAILED")
        # OCCT normalizes STEP's declared source units to this explicit target.
        reader.ChangeReader().SetSystemLengthUnit(1.0)
        if not reader.Transfer(document): raise ValueError("STEP_TRANSFER_FAILED")
        tool = XCAFDoc_DocumentTool.ShapeTool_s(document.Main())
        roots = TDF_LabelSequence()
        tool.GetFreeShapes(roots)
        items, instances, flags = [], [], []

        def entry(label):
            value = TCollection_AsciiString()
            TDF_Tool.Entry_s(label, value)
            return value.ToCString()

        def name(label):
            attribute = TDataStd_Name()
            return str(attribute.Get().ToExtString()) if label.FindAttribute(TDataStd_Name.GetID_s(), attribute) else entry(label)

        def walk(label, parent_location, ancestry, depth=0):
            if depth > 32 or len(instances) >= 4096: raise ValueError("CAD_ASSEMBLY_BUDGET_EXCEEDED")
            definition = TDF_Label()
            is_reference = tool.GetReferredShape_s(label, definition)
            if not is_reference: definition = label
            location = parent_location.Multiplied(tool.GetLocation_s(label))
            instance_id = "/".join(ancestry + [entry(label)])
            transform = location.Transformation()
            matrix = [[transform.Value(row, col) for col in range(1, 5)] for row in range(1, 4)] + [[0, 0, 0, 1]]
            record = {"instanceId": instance_id, "partId": entry(definition), "name": name(definition),
                      "parentInstanceId": "/".join(ancestry) or None, "worldTransform": matrix}
            instances.append(record)
            children = TDF_LabelSequence()
            if tool.GetComponents_s(definition, children, False) and children.Length():
                for index in range(1, children.Length() + 1):
                    walk(children.Value(index), location, ancestry + [entry(label)], depth + 1)
                return
            local_shape = tool.GetShape_s(definition).Located(TopLoc_Location())
            shape = local_shape.Moved(location)
            if shape.IsNull(): raise ValueError("CAD_NULL_LEAF")
            valid = BRepCheck_Analyzer(shape).IsValid()
            box = Bnd_Box(); BRepBndLib.Add_s(shape, box)
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
                       "faceCount": face_count, "solidCount": solid_count, "unitSource": "STEP declared units -> OCCT millimetres",
                       "featureSpaceId": "cad-aabb-brep-v2", "shapeFeatureRotationInvariant": False, "shapeFeatureScaleInvariant": True}
            if valid:
                payload["featureVector"] = [*np.sort(size/scale)[::-1].tolist(), surface.Mass()/scale**2,
                                            abs(volume.Mass())/scale**3 if solid_count else 0.0,
                                            math.log1p(face_count)/20, math.log1p(solid_count)/5, float(bool(solid_count))]
            part_flags = [] if valid else ["CAD_INVALID_BREP"]
            items.append(ParsedItem("geometry", payload, {"partId": entry(definition), "instanceId": instance_id},
                                    entity_id=instance_id, units={"length":"mm", "area":"mm2", "volume":"mm3"}, quality_flags=part_flags))
            items.append(ParsedItem("text", name(definition), {"partId": entry(definition), "instanceId": instance_id}, entity_id=instance_id))
            flags.extend(part_flags)

        for index in range(1, roots.Length()+1): walk(roots.Value(index), TopLoc_Location(), [])
        geometries = [item for item in items if item.kind == "geometry"]
        if not geometries: raise ValueError("CAD_NO_LEAF_GEOMETRY")
        attributes = {"assemblyInstances": instances, "partCount": len(geometries), "kernel": "OpenCASCADE-XDE",
                      "exactGeometry": all(item.payload["exactGeometry"] for item in geometries)}
        if len(geometries) == 1: attributes.update(geometries[0].payload)
        return parser._asset(path, context, items, attributes, sorted(set(flags)))
    except Exception as exc:
        return parser._asset(path, context, [], {"exactGeometry": False, "parseError": str(exc)},
                             ["CAD_EXACT_GEOMETRY_UNAVAILABLE", type(exc).__name__])
