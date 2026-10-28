"""Locatable native PDF layout and bounded CPU OCR. Never executes PDF actions."""
from __future__ import annotations
import hashlib,math,os,re,shutil,subprocess,tempfile,time
from decimal import Decimal
import csv
from functools import lru_cache
from pathlib import Path
from PIL import Image
from .contracts import ParsedItem,stable_id
from .cpu_queue import cpu_evidence_slot

VERSION='document-native-2026-10-22.2'
REGION_RENDER_RECIPE='native-region-render-2026-10-22.1'
MAX_SOURCE_BYTES=64*1024*1024
MAX_SOURCE_PIXELS=32_000_000
MAX_RENDER_PIXELS=8_000_000
MAX_PAGES=256
MAX_WORDS=20000
OCR_LANGUAGES='chi_sim+eng'

def checked_source(path):
    if path.stat().st_size>MAX_SOURCE_BYTES:raise ValueError('DOCUMENT_SOURCE_BYTE_LIMIT')

def bbox_union(boxes):
    boxes=list(boxes)
    return [min(b[0] for b in boxes),min(b[1] for b in boxes),max(b[2] for b in boxes),max(b[3] for b in boxes)] if boxes else None

def overlap(left,right):
    return max(0,min(left[2],right[2])-max(left[0],right[0]))*max(0,min(left[3],right[3])-max(left[1],right[1]))

@lru_cache(maxsize=1)
def ocr_engine():
    executable=shutil.which('tesseract')
    if not executable:return {'available':False,'status':'OCR_ENGINE_UNAVAILABLE','adapterVersion':VERSION}
    result=subprocess.run([executable,'--version'],capture_output=True,text=True,timeout=10,check=True)
    directory=Path('/usr/share/tesseract-ocr/5/tessdata')
    data={language:hashlib.sha256((directory/(language+'.traineddata')).read_bytes()).hexdigest() for language in OCR_LANGUAGES.split('+') if (directory/(language+'.traineddata')).is_file()}
    if set(data)!=set(OCR_LANGUAGES.split('+')):return {'available':False,'status':'OCR_LANGUAGE_DATA_UNAVAILABLE','adapterVersion':VERSION}
    return {'available':True,'adapterVersion':VERSION,'engineVersion':result.stdout.splitlines()[0],
        'languages':OCR_LANGUAGES,'languageDataSha256':data,'pageSegmentationMode':3,'threadLimit':1,
        'timeoutSeconds':60,'maxRenderPixels':MAX_RENDER_PIXELS,'confidenceScale':'tesseract-0-to-100-not-calibrated-probability'}

def recognize(image,*,to_source=None):
    engine=ocr_engine()
    if not engine['available']:return {'status':engine['status'],'words':[],'engine':engine}
    if image.width*image.height>MAX_RENDER_PIXELS:raise ValueError('OCR_PIXEL_LIMIT')
    started=time.perf_counter()
    with tempfile.TemporaryDirectory(prefix='mds-ocr-') as temporary:
        root=Path(temporary);image.save(root/'input.png')
        try:
            result=subprocess.run(['tesseract',str(root/'input.png'),str(root/'result'),'-l',OCR_LANGUAGES,'--psm','3','tsv'],
                stdout=subprocess.DEVNULL,stderr=subprocess.PIPE,timeout=60,env={**os.environ,'OMP_THREAD_LIMIT':'1'})
        except subprocess.TimeoutExpired:return {'status':'OCR_TIMEOUT','words':[],'engine':engine,'elapsedMs':round((time.perf_counter()-started)*1000,3)}
        if result.returncode:return {'status':'OCR_PROCESS_FAILED','words':[],'engine':engine,'exitCode':result.returncode}
        output=root/'result.tsv'
        if not output.is_file() or output.stat().st_size>8*1024*1024:return {'status':'OCR_OUTPUT_LIMIT','words':[],'engine':engine}
        words=[]
        with output.open(encoding='utf-8',newline='') as stream:
            for record in csv.DictReader(stream,delimiter='\t'):
                if record.get('level')!='5' or not record.get('text','').strip():continue
                if len(words)>=MAX_WORDS:return {'status':'OCR_WORD_LIMIT','words':[],'engine':engine}
                x,y,w,h=(int(record[key]) for key in ('left','top','width','height'));box=[x,y,x+w,y+h]
                if min(x,y,w,h)<0 or box[2]>image.width or box[3]>image.height:raise ValueError('OCR_COORDINATE_INVALID')
                confidence=float(record['conf']);mapped=to_source(box) if to_source else box
                words.append({'wordIndex':len(words),'text':record['text'],'bbox':mapped,'ocrPixelBBox':box,
                    'confidence':confidence,'block':int(record['block_num']),'paragraph':int(record['par_num']),
                    'line':int(record['line_num']),'word':int(record['word_num']),'extractionMethod':'tesseract-ocr',
                    'qualityFlags':['OCR_LOW_CONFIDENCE'] if confidence<60 else []})
    return {'status':'COMPLETE' if words else 'COMPLETE_NO_TEXT','words':words,'engine':engine,
        'elapsedMs':round((time.perf_counter()-started)*1000,3),'confidencePolicy':'Recognition observation, not verified source values or probability.'}

def ocr_lines(words,locator):
    groups={}
    for word in words:
        if word.get('exclusionReason'):continue
        groups.setdefault((word['block'],word['paragraph'],word['line']),[]).append(word)
    return [ParsedItem('text',' '.join(word['text'] for word in line),
        {**locator,'bbox':bbox_union(word['bbox'] for word in line),'ocrBlock':key[0],'ocrParagraph':key[1],
            'ocrLine':key[2],'extractionMethod':'tesseract-ocr','ocrConfidenceMinimum':min(word['confidence'] for word in line),
            'wordIndices':[word['wordIndex'] for word in line]},quality_flags=sorted({flag for word in line for flag in word['qualityFlags']})) for key,line in groups.items()]

def image_content(path):
    checked_source(path)
    with Image.open(path) as source:
        width,height=source.size
        if width*height>MAX_SOURCE_PIXELS:raise ValueError('IMAGE_SOURCE_PIXEL_LIMIT')
        if getattr(source,'n_frames',1)!=1:raise ValueError('IMAGE_MULTIFRAME_UNSUPPORTED')
        orientation=source.getexif().get(274,1)
        image=source.convert('RGB');factor=min(1,math.sqrt(MAX_RENDER_PIXELS/(width*height)))
        if factor<1:image=image.resize((max(1,int(width*factor)),max(1,int(height*factor))))
        sx,sy=width/image.width,height/image.height
        with cpu_evidence_slot() as queue:
            ocr=recognize(image,to_source=lambda b:[b[0]*sx,b[1]*sy,b[2]*sx,b[3]*sy])
        page={'page':1,'bbox':[0,0,width,height],'coordinateSystem':'source-image-pixels-top-left',
            'rotation':0,'sourceExifOrientation':orientation,'nativeBlocks':[],'nativeWords':[],
            'ocr':ocr,'render':{'width':image.width,'height':image.height,'ocrPixelToSourceScale':[sx,sy],
                'sourceOrientationPolicy':'Raw decoded pixels; EXIF orientation is recorded, not silently applied.'}}
        attrs={'width':width,'height':height,'mode':source.mode,'format':source.format,'documentLayout':[page],
            'documentAdapters':{'layoutVersion':VERSION,'regionRenderVersion':REGION_RENDER_RECIPE,'ocr':ocr_engine()},'conversionTimings':queue,
            'textExtraction':'ocr','ocrStatus':ocr['status']}
        flags=[] if ocr['status'].startswith('COMPLETE') else [ocr['status']]
        if orientation!=1:flags.append('EXIF_ORIENTATION_RECORDED_RAW_PIXELS')
        return ocr_lines(ocr['words'],{'page':1,'coordinateSystem':page['coordinateSystem']}),attrs,flags

def _native_blocks(page,page_number):
    import fitz
    layout=page.get_text('dict',flags=fitz.TEXTFLAGS_DICT & ~fitz.TEXT_PRESERVE_IMAGES,sort=False)
    blocks=[];items=[]
    for index,block in enumerate(layout['blocks']):
        if block.get('type')!=0:continue
        block={**block,'blockIndex':index};blocks.append(block)
        text='\n'.join(''.join(span['text'] for span in line['spans']) for line in block['lines'])+'\n'
        # Chunk within the exact extracted block; layout retains individual spans.
        for start in range(0,len(text),900):
            value=text[start:start+900]
            if value:items.append(ParsedItem('text',value,{'page':page_number,'blockIndex':index,'bbox':list(block['bbox']),
                'characterStart':start,'characterEnd':start+len(value),'characterIndexUnit':'unicode-codepoint',
                'coordinateSystem':'pymupdf-unrotated-top-left-pt','extractionMethod':'pdf-native'}))
    words=[{'wordIndex':index,'bbox':list(word[:4]),'text':word[4],'block':word[5],'line':word[6],'word':word[7],
        'extractionMethod':'pdf-native'} for index,word in enumerate(page.get_text('words',sort=False))]
    if len(words)>MAX_WORDS:raise ValueError('PDF_NATIVE_WORD_LIMIT')
    return blocks,words,items

def _pdf_tables(page,page_number,units):
    from .table_native import _names,_numeric_column
    from .json_native import records_payload
    output=[];native=[]
    for number,table in enumerate(page.find_tables().tables,start=1):
        matrix=table.extract()
        if not matrix:continue
        external=bool(table.header.external);headers=table.header.names if external else matrix[0]
        names=_names(headers);start=1 if external else 2;data=matrix if external else matrix[1:]
        numeric={name for position,name in enumerate(names) if _numeric_column(name,[row[position] for row in data if position<len(row) and row[position] is not None])}
        records=[]
        for index,cells in enumerate(data,start=start):
            record={'rawRow':index,'values':{},'sourceValues':{},'numberLexemes':{},'calculationEligibility':{},'locator':{
                'page':page_number,'table':number,'tableRow':index,'bbox':list(table.rows[index-1].bbox),
                'rowNumbering':'one-based-pdf-table-row','cells':{}}}
            for position,(name,value) in enumerate(zip(names,cells)):
                record['sourceValues'][name]=value;record['values'][name]=value
                if name in numeric and value is not None and value.strip():
                    token=value.strip();record['values'][name]=int(token) if re.fullmatch(r'[+-]?\d+',token) else float(token)
                    record['numberLexemes'][name]={'lexeme':value,'decimalValue':str(Decimal(token)),'sourceType':'native-pdf-table-cell'}
                record['calculationEligibility'][name]=True
                cell=table.rows[index-1].cells[position]
                record['locator']['cells'][name]={'row':index,'column':position+1,'bbox':list(cell) if cell else None}
            records.append(record)
        payload=records_payload(records);column_map={column['name']:column for column in payload['columns']}
        payload['columns']=[{**column_map.get(name,{'name':name,'dtype':'unknown','nullable':True,'sample':[]}),
            'sourceColumn':position+1,'sourceHeader':headers[position],
            'typeProvenance':'conservative-lexical-inference' if name in numeric else 'native-pdf-cell-string'} for position,name in enumerate(names)]
        payload['nullSemantics']='None means no text detected in that source PDF cell; it is not zero or a verified business null.'
        item=ParsedItem('table',payload,{'page':page_number,'table':number,'bbox':list(table.bbox),'rawRowStart':start,
            'rowNumbering':'one-based-pdf-table-row','coordinateSystem':'pymupdf-unrotated-top-left-pt','extractionMethod':'pdf-native-table'},units=dict(units))
        native.append({'table':number,'bbox':list(table.bbox),'matrix':matrix,'cells':[list(cell) if cell else None for cell in table.cells],
            'header':{'names':table.header.names,'external':table.header.external,'bbox':list(table.header.bbox)},
            'rowCount':table.row_count,'columnCount':table.col_count,'extractionMethod':'pdf-native-table'})
        output.append(item)
    return native,output

def pdf_content(path,context):
    import fitz
    checked_source(path);items=[];pages=[];flags=[];started=time.monotonic()
    with fitz.open(path) as document,cpu_evidence_slot() as queue:
        if document.needs_pass:raise ValueError('PDF_PASSWORD_REQUIRED')
        if not 0<document.page_count<=MAX_PAGES:raise ValueError('PDF_PAGE_LIMIT')
        for number,page in enumerate(document,start=1):
            if time.monotonic()-started>600:raise TimeoutError('PDF_DOCUMENT_TIME_LIMIT')
            original_rotation=page.rotation;rotation_matrix=list(page.rotation_matrix);derotation_matrix=list(page.derotation_matrix)
            # Canonicalize only this in-memory page. OCR sees upright raster
            # pixels; every source locator remains in unrotated page points.
            if original_rotation:page.set_rotation(0)
            blocks,words,native_items=_native_blocks(page,number);items.extend(native_items)
            scale=min(200/72,math.sqrt(MAX_RENDER_PIXELS/(page.rect.width*page.rect.height)))
            pix=page.get_pixmap(matrix=fitz.Matrix(scale,scale),alpha=False)
            while pix.width*pix.height>MAX_RENDER_PIXELS:
                scale*=.99;pix=page.get_pixmap(matrix=fitz.Matrix(scale,scale),alpha=False)
            preview=context.output_dir/'representations'/stable_id(context.dataset_version_id,context.logical_path)/'visual'/f'page-{number:04d}.png'
            preview.parent.mkdir(parents=True,exist_ok=True);pix.save(preview)
            coordinate='pymupdf-unrotated-top-left-pt'
            items.append(ParsedItem('visual',{'path':str(preview),'caption':f'{path.name} 第{number}页'},
                {'page':number,'bbox':[0,0,page.cropbox.width,page.cropbox.height],'coordinateSystem':coordinate,'renderScale':scale,
                    'originalRotation':original_rotation,'outputOrientation':'canonical-unrotated'}))
            images=page.get_image_info()
            if images or not words:
                def to_source(box):
                    displayed=fitz.Rect((box[0]+pix.x)/scale,(box[1]+pix.y)/scale,(box[2]+pix.x)/scale,(box[3]+pix.y)/scale)
                    return list(displayed*page.derotation_matrix)
                ocr=recognize(Image.frombytes('RGB',(pix.width,pix.height),pix.samples),to_source=to_source)
                for word in ocr['words']:
                    text=word['text'].casefold().strip();area=max(1,(word['bbox'][2]-word['bbox'][0])*(word['bbox'][3]-word['bbox'][1]))
                    matches=[native for native in words if overlap(word['bbox'],native['bbox'])/area>.5]
                    if any(native['text'].casefold().strip()==text for native in matches):word['exclusionReason']='DUPLICATE_NATIVE_WORD'
                    elif matches:word['qualityFlags'].append('OCR_NATIVE_TEXT_CONFLICT')
                items.extend(ocr_lines(ocr['words'],{'page':number,'coordinateSystem':coordinate}))
            else:ocr={'status':'NOT_NEEDED_NATIVE_TEXT_ONLY','words':[],'engine':ocr_engine()}
            if ocr['status'] not in {'COMPLETE','COMPLETE_NO_TEXT','NOT_NEEDED_NATIVE_TEXT_ONLY'}:flags.append(f'PAGE_{number}_{ocr["status"]}')
            try:tables,table_items=_pdf_tables(page,number,context.unit_mapping);items.extend(table_items);table_status='COMPLETE'
            except Exception as error:tables=[];table_status='FAILED_'+type(error).__name__;flags.append(f'PAGE_{number}_TABLE_EXTRACTION_UNAVAILABLE')
            drawings=page.get_drawings()
            pages.append({'page':number,'bbox':[0,0,page.cropbox.width,page.cropbox.height],'coordinateSystem':coordinate,
                'mediaBox':list(page.mediabox),'cropBox':list(page.cropbox),'rotation':original_rotation,
                'pdfToPageTransform':list(page.transformation_matrix),'rotationMatrix':rotation_matrix,
                'derotationMatrix':derotation_matrix,'nativeBlocks':blocks,'nativeWords':words,
                'ocr':ocr,'tables':tables,'tableStatus':table_status,
                'imageRegions':[{key:image[key] for key in ('number','bbox','transform','width','height') if key in image} for image in images],
                'vectorRegions':[{'drawingIndex':index,'bbox':list(drawing['rect']),'type':drawing['type']} for index,drawing in enumerate(drawings)],
                'readingOrder':'PDF text-block order and OCR block/paragraph/line order; semantic layout order is unverified.',
                'render':{'width':pix.width,'height':pix.height,'scale':scale,'pixelOrigin':[pix.x,pix.y],'dpiTarget':200,'outputOrientation':'canonical-unrotated',
                    'pageToPixelTransform':list(page.rotation_matrix*fitz.Matrix(scale,scale))}})
    attrs={'pageCount':len(pages),'renderedPages':len(pages),'textExtraction':'native-plus-per-page-ocr',
        'documentLayout':pages,'documentAdapters':{'layoutVersion':VERSION,'regionRenderVersion':REGION_RENDER_RECIPE,'pymupdfVersion':fitz.VersionBind,'ocr':ocr_engine()},
        'conversionTimings':queue,'nativeTextPageCount':sum(bool(page['nativeWords']) for page in pages),
        'ocrPageCount':sum(page['ocr']['status'].startswith('COMPLETE') for page in pages),
        'layoutSemanticStatus':'UNVERIFIED_READING_ORDER_AND_CHART_SEMANTICS'}
    return items,attrs,sorted(set(flags))
