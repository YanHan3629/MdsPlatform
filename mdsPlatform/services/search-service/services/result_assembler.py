from schemas.search_resp import TextToImageItem, ImageToTextItem


class ResultAssembler:
    @staticmethod
    def _normalize_texts(row):
        raw = None
        for key in ("texts", "text", "captions", "caption"):
            if key in row:
                raw = row[key]
                break

        if raw is None:
            return []

        if hasattr(raw, "tolist"):
            raw = raw.tolist()

        if isinstance(raw, str):
            return [raw]

        if isinstance(raw, (list, tuple, set)):
            out = []
            for v in raw:
                if v is None:
                    continue
                if hasattr(v, "tolist"):
                    v = v.tolist()
                if isinstance(v, (list, tuple, set)):
                    out.extend(str(x) for x in v if x is not None)
                else:
                    out.append(str(v))
            return out

        return [str(raw)]

    @staticmethod
    def assemble_text_to_image(scores, indices, metadata_df):
        items = []
        for score, idx in zip(scores, indices):
            if idx < 0:
                continue
            row = metadata_df.iloc[idx]
            texts = ResultAssembler._normalize_texts(row)
            items.append(TextToImageItem(
                assetId=row["asset_id"],
                score=float(score),
                logicalPath=row["logical_path"],
                texts=texts,
            ))
        return items

    @staticmethod
    def assemble_image_to_text(scores, indices, metadata_df):
        items = []
        for score, idx in zip(scores, indices):
            if idx < 0:
                continue
            row = metadata_df.iloc[idx]
            items.append(ImageToTextItem(
                assetId=row["asset_id"],
                score=float(score),
                text=row["text"],
                logicalPath=row["logical_path"],
            ))
        return items
