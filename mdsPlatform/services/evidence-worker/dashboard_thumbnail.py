"""Render small images from the exact source bytes selected by the dashboard."""
import csv
import itertools
import json
import tempfile
import zipfile
from io import BytesIO, StringIO
from pathlib import Path


RECIPE = 'dashboard-preview-2'
FORMATS = {'JPG', 'PNG', 'PDF', 'CSV', 'XLSX', 'JSON', 'JSONL', 'TXT', 'SVG', 'OBJ', 'STEP'}
FONT = '/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc'
SIZE = (640, 384)
MAX_BYTES = 200 * 1024


def _cell(value):
    if value is None:
        return ''
    if isinstance(value, (dict, list)):
        value = json.dumps(value, ensure_ascii=False, separators=(',', ':'))
    return ' '.join(str(value).strip().split())


def _shorten(draw, value, width, font):
    value = _cell(value)
    if draw.textlength(value[:80], font=font) <= width and len(value) <= 80:
        return value
    for count in range(min(len(value), 80), 0, -1):
        if draw.textlength(value[:count] + '…', font=font) <= width:
            return value[:count] + '…'
    return '…'


def _sheet(raw):
    from openpyxl import load_workbook

    with zipfile.ZipFile(BytesIO(raw)) as archive:
        entries = archive.infolist()
        if len(entries) > 1024 or sum(item.file_size for item in entries) > 80 * 1024 * 1024:
            raise ValueError('XLSX_ARCHIVE_LIMIT')
    book = load_workbook(BytesIO(raw), read_only=True, data_only=True, keep_links=False)
    try:
        sheet = book.worksheets[0]
        rows = list(sheet.iter_rows(min_row=1, max_row=8, max_col=4, values_only=True))
        while rows and not any(value is not None for value in rows[-1]):
            rows.pop()
        return rows, sheet.title + ' · 第 1–' + str(len(rows)) + ' 行'
    finally:
        book.close()


def _structured(raw, source_format):
    text = raw.decode('utf-8-sig')
    if source_format == 'CSV':
        rows = [row[:4] for row in itertools.islice(csv.reader(StringIO(text), strict=True), 8)]
        return rows, '第 1–' + str(len(rows)) + ' 行'
    if source_format == 'TXT':
        lines = list(itertools.islice((line for line in text.splitlines() if line.strip()), 11))
        return [('原文片段',)] + [(line,) for line in lines], '开头文本'
    if source_format == 'JSONL':
        records = [json.loads(line) for line in itertools.islice((s for s in text.splitlines() if s.strip()), 7)]
    else:
        value = json.loads(text)
        if isinstance(value, dict):
            return [('字段', '值')] + list(itertools.islice(value.items(), 8)), '根对象首批字段'
        records = value[:7] if isinstance(value, list) else [value]
    if records and all(isinstance(record, dict) for record in records):
        keys = list(dict.fromkeys(key for record in records for key in record))[:4]
        return [keys] + [tuple(record.get(key) for key in keys) for record in records], '开头记录'
    return [('记录',)] + [(record,) for record in records], '开头记录'


def _text_image(raw, source_format):
    from PIL import Image, ImageDraw, ImageFont

    rows, locator = _sheet(raw) if source_format == 'XLSX' else _structured(raw, source_format)
    if not rows or not any(_cell(value) for row in rows for value in row):
        raise ValueError('PREVIEW_CONTENT_EMPTY')
    image = Image.new('RGB', SIZE, '#ffffff')
    draw = ImageDraw.Draw(image)
    body = ImageFont.truetype(FONT, 16)
    heading = ImageFont.truetype(FONT, 18)
    draw.text((15, 11), _shorten(draw, locator, 608, heading), font=heading, fill='#48677a')
    columns = max(len(row) for row in rows)
    cell_width = (SIZE[0] - 28) // columns
    row_height = min(38, 330 // len(rows))
    for index, row in enumerate(rows):
        top = 40 + index * row_height
        draw.rectangle((14, top, SIZE[0] - 14, top + row_height),
                       fill='#e6eff6' if index == 0 else '#f4f7fb' if index % 2 else '#ffffff')
        for col, value in enumerate(row):
            left = 14 + cell_width * col
            draw.text((left + 7, top + 3), _shorten(draw, value, cell_width - 14, body), font=body, fill='#153043')
    return image


def _source_image(raw, source_format):
    from PIL import Image, ImageDraw, ImageFont, ImageOps

    if source_format in {'JPG', 'PNG'}:
        with Image.open(BytesIO(raw)) as original:
            expected = 'JPEG' if source_format == 'JPG' else 'PNG'
            if original.format != expected or original.width * original.height > 32_000_000:
                raise ValueError('PREVIEW_IMAGE_INVALID')
            return ImageOps.exif_transpose(original).convert('RGBA')
    if source_format == 'PDF':
        import fitz
        with fitz.open(stream=raw, filetype='pdf') as document:
            if document.needs_pass or not document.page_count:
                raise ValueError('PREVIEW_PDF_INVALID')
            page = document[0]
            if any(info['width'] * info['height'] > 32_000_000 for info in page.get_image_info()):
                raise ValueError('PREVIEW_PDF_PIXEL_LIMIT')
            scale = min(2, SIZE[0] / page.rect.width, SIZE[1] / page.rect.height)
            pixmap = page.get_pixmap(matrix=fitz.Matrix(scale, scale), alpha=False)
            return Image.frombytes('RGB', (pixmap.width, pixmap.height), pixmap.samples)
    if source_format == 'SVG':
        from professional.svg_native import read_svg, render_svg
        with tempfile.TemporaryDirectory(prefix='mds-dashboard-svg-') as directory:
            source, output = Path(directory) / 'source.svg', Path(directory) / 'thumbnail.png'
            source.write_bytes(raw)
            result = read_svg(source)
            x, y, width, height = result['viewport']['viewBox']
            render_svg(result, output, bbox=[x, y, x + width, y + height], max_pixels=SIZE[0]*SIZE[1],
                       _already_queued=True)
            with Image.open(output) as image:
                return image.copy()
    from professional.engineering_render import engine_binding, render_source
    parameters = {'view': 'isometric', 'bbox': [0, 0, 1, 1], 'maxPixels': SIZE[0]*SIZE[1],
                  'instanceIds': [], 'highlightInstanceIds': [], 'engineBinding': engine_binding(source_format)}
    rendered = render_source(raw, source_format, parameters)
    if rendered['transform'].get('occupiedPixels',0) <= 0:
        raise ValueError('PREVIEW_GEOMETRY_EMPTY')
    with Image.open(BytesIO(rendered['png'])) as image:
        canvas = image.convert('RGB')
        # The registered renderer reserves bands outside its projected mesh.
        # Localize those captions while keeping the rendered geometry intact.
        draw = ImageDraw.Draw(canvas)
        font = ImageFont.truetype(FONT, 13)
        background = canvas.getpixel((0, 0))
        draw.rectangle((0, 0, canvas.width, 29), fill=background)
        draw.rectangle((0, canvas.height-28, canvas.width, canvas.height), fill=background)
        draw.text((16, 8), '等轴测正投影视图', font=font, fill='#233044')
        draw.text((16, canvas.height-24), '按源坐标显示，颜色用于区分表面', font=font, fill='#414b5a')
        return canvas


def render(raw, source_format):
    from PIL import Image

    if source_format in {'CSV', 'XLSX', 'JSON', 'JSONL', 'TXT'}:
        canvas = _text_image(raw, source_format)
    else:
        image = _source_image(raw, source_format).convert('RGBA')
        image.thumbnail(SIZE, Image.Resampling.LANCZOS)
        canvas = Image.new('RGB', SIZE, '#ffffff')
        canvas.paste(image, ((SIZE[0]-image.width)//2, (SIZE[1]-image.height)//2), image)
    for quality in (84, 74, 62, 48):
        output = BytesIO()
        canvas.save(output, format='JPEG', quality=quality, optimize=True)
        if output.tell() <= MAX_BYTES:
            return {'jpeg': output.getvalue()}
    raise ValueError('PREVIEW_OUTPUT_LIMIT')
