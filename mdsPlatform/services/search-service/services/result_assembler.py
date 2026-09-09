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

    @staticmethod
    def assemble_unified_as_text_to_image(scores, indices, metadata_df, top_k):
        """Collapse representation hits into stable, file-level results."""
        grouped = {}
        order = []
        for score, idx in zip(scores, indices):
            if idx < 0:
                continue
            row = metadata_df.iloc[idx]
            asset_id = str(row["asset_id"])
            if asset_id not in grouped:
                grouped[asset_id] = {
                    "score": float(score),
                    "logical_path": row["logical_path"],
                    "texts": [],
                }
                order.append(asset_id)
            item = grouped[asset_id]
            item["score"] = max(item["score"], float(score))
            for text in ResultAssembler._normalize_texts(row):
                if text and text not in item["texts"] and len(item["texts"]) < 3:
                    item["texts"].append(text)
        return [
            TextToImageItem(
                assetId=asset_id,
                score=grouped[asset_id]["score"],
                logicalPath=grouped[asset_id]["logical_path"],
                texts=grouped[asset_id]["texts"],
            )
            for asset_id in order[:top_k]
        ]

    @staticmethod
    def assemble_unified_as_image_to_text(scores, indices, metadata_df, top_k):
        grouped = {}
        order = []
        for score, idx in zip(scores, indices):
            if idx < 0:
                continue
            row = metadata_df.iloc[idx]
            asset_id = str(row["asset_id"])
            if asset_id in grouped:
                continue
            texts = ResultAssembler._normalize_texts(row)
            grouped[asset_id] = ImageToTextItem(
                assetId=asset_id,
                score=float(score),
                text=texts[0] if texts else "",
                logicalPath=row["logical_path"],
            )
            order.append(asset_id)
        return [grouped[asset_id] for asset_id in order[:top_k]]
