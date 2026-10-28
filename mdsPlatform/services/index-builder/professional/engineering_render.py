"""Registered engineering views; no free camera, path, program or network input."""
from __future__ import annotations
import hashlib,importlib.metadata,json,math,tempfile
from pathlib import Path

RECIPE='engineering-region-render-2026-10-22.1'
VERSION='engineering-native-tools-2026-10-22.1'


def engine_binding(source_format):
    result={'adapterVersion':VERSION,'renderRecipeVersion':RECIPE}
    if source_format=='SVG':
        from .svg_native import VERSION as parser_version,RENDER_VERSION,font_fingerprint
        result.update(svgParserVersion=parser_version,svgRenderVersion=RENDER_VERSION,
            CairoSVG=importlib.metadata.version('CairoSVG'),cairocffi=importlib.metadata.version('cairocffi'),
            defusedxml=importlib.metadata.version('defusedxml'),fontFileSha256=font_fingerprint())
    else:
        from .geometry_surface import VERSION as surface_version
        result.update(surfaceRenderVersion=surface_version,numpy=importlib.metadata.version('numpy'),Pillow=importlib.metadata.version('Pillow'))
        if source_format=='STEP':
            from .cad_geometry import VERSION as cad_version
            result.update(cadAdapterVersion=cad_version,OCP=importlib.metadata.version('cadquery-ocp'),
                linearDeflection=.15,angularDeflectionRadians=.35,parallelMeshing=False)
    return result


def render_source(raw,source_format,parameters):
    """Invoked only inside the bounded worker child whose parent owns the slot."""
    if source_format not in {'SVG','OBJ','STEP'}:raise ValueError('ENGINEERING_FORMAT_UNSUPPORTED')
    if parameters['engineBinding']!=engine_binding(source_format):raise ValueError('ENGINEERING_ENGINE_BINDING_MISMATCH')
    source_sha=hashlib.sha256(raw).hexdigest()
    with tempfile.TemporaryDirectory(prefix='mds-engineering-read-') as temporary:
        root=Path(temporary);source=root/('source.'+source_format.lower());source.write_bytes(raw);target=root/'render.png'
        if source_format=='SVG':
            from .svg_native import read_svg,render_svg
            if parameters['view']!='source-2d' or parameters['instanceIds'] or parameters['highlightInstanceIds']:raise ValueError('SVG_INSTANCE_OR_VIEW_UNSUPPORTED')
            parsed=read_svg(source);view=render_svg(parsed,target,bbox=parameters['bbox'],max_pixels=parameters['maxPixels'],_already_queued=True)
            box=parameters['bbox'];x,y,w,h=parsed['viewport']['viewBox']
            transform={'coordinateSystem':'svg-root-user-space','sourceBounds':[x,y,x+w,y+h],
                'requestedBBox':box,'pixelToSourceOffset':box[:2],
                'pixelToSourceScale':[(box[2]-box[0])/view['width'],(box[3]-box[1])/view['height']],
                'sourceViewport':parsed['viewport'],'fontResolution':view['engine']['fontResolution'],'sourceDeclarationsAreNot3DMeasurements':True}
        else:
            if source_format=='OBJ':
                from .geometry_surface import read_obj
                import numpy as np
                mesh=read_obj(source)
                if not len(mesh['triangles']):raise ValueError('OBJ_NO_SURFACE_FACES')
                used=np.unique(mesh['triangles']);inverse=np.full(len(mesh['vertices']),-1,dtype=np.int32);inverse[used]=np.arange(len(used))
                instances=[{'instanceId':'root','vertices':mesh['vertices'][used],'triangles':inverse[mesh['triangles']]}]
            else:
                from .cad_geometry import _extract
                _items,_attributes,_flags,instances=_extract(source,_already_queued=True)
            available={item['instanceId'] for item in instances};selected=parameters['instanceIds'];highlight=parameters['highlightInstanceIds']
            if not set(selected)<=available or not set(highlight)<=(set(selected) if selected else available):raise ValueError('ENGINEERING_INSTANCE_NOT_FOUND')
            if selected:instances=[item for item in instances if item['instanceId'] in selected]
            from .geometry_surface import render_view
            view=render_view(instances,target,source_sha,view=parameters['view'],normalized_bbox=parameters['bbox'],
                max_pixels=parameters['maxPixels'],highlight_ids=highlight,_already_queued=True)
            transform={key:value for key,value in view.items() if key not in {'path','outputSha256','outputBytes','sourceSha256'}}
            ordered=[item['instanceId'] for item in instances];legend=list(dict.fromkeys([*highlight,*ordered]))[:32]
            transform['instanceIds']=[value for value in ordered if value in legend]
            transform['visibleInstancePixels']={key:value for key,value in transform['visibleInstancePixels'].items() if key in legend}
            transform['displayColours']={key:value for key,value in transform['displayColours'].items() if key in legend}
            transform.update(coordinateSystem='registered-orthographic-reference-image-normalized',requestedBBox=parameters['bbox'],
                selectedInstanceIds=selected,availableInstanceCount=len(available),renderedInstanceCount=len(instances),instanceLegendPartial=len(legend)<len(instances),instanceIsolation=bool(selected),
                sourceWorldTransformsPreserved=True,explodedOrTransparent=False,notAnAssemblyUnionMeasurement=True)
        png=target.read_bytes()
        if hashlib.sha256(source.read_bytes()).hexdigest()!=source_sha:raise ValueError('ENGINEERING_SOURCE_MUTATED')
        return {'png':png,'width':view['width'],'height':view['height'],'transform':transform,'engine':engine_binding(source_format)}
