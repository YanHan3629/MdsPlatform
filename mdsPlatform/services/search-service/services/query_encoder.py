import io
import torch
import torch.nn.functional as F
from PIL import Image

from core.config import settings
from services.model_manager import ModelManager


class QueryEncoder:
    def __init__(self):
        self.model, self.processor, self.device = ModelManager.load(settings.model_path)

    def _prompt_variants(self, text_query: str) -> list[str]:
        base_prompts = [
            text_query,
            f"a photo of {text_query}",
            f"image of {text_query}",
            f"picture of {text_query}",
        ]
        is_non_ascii = any(ord(ch) > 127 for ch in text_query)
        if is_non_ascii:
            base_prompts.extend([
                f"{text_query} 图片",
                f"{text_query} 照片",
                f"{text_query} 图像",
                f"{text_query} 产品",
            ])
        else:
            base_prompts.extend([
                f"product image of {text_query}",
                f"household {text_query}",
                f"electronic {text_query}",
                f"appliance {text_query}",
            ])
        deduped = []
        seen = set()
        for prompt in base_prompts:
            if prompt not in seen:
                seen.add(prompt)
                deduped.append(prompt)
        return deduped

    def _normalize(self, features):
        return F.normalize(features, dim=-1)

    def encode_text(self, text: str):
        prompt_features = []
        for prompt in self._prompt_variants(text):
            inputs = self.processor(
                text=prompt,
                return_tensors="pt",
                padding=True,
                truncation=True,
                max_length=77,
            )
            inputs = {k: v.to(self.device) for k, v in inputs.items() if hasattr(v, "to")}
            with torch.no_grad():
                features = self.model.get_text_features(**inputs)
                prompt_features.append(self._normalize(features))
        features = torch.mean(torch.cat(prompt_features, dim=0), dim=0, keepdim=True)
        features = self._normalize(features)
        return features.detach().cpu().numpy()

    def encode_image_bytes(self, content: bytes):
        image = Image.open(io.BytesIO(content)).convert("RGB")
        inputs = self.processor(images=image, return_tensors="pt")
        inputs = {k: v.to(self.device) for k, v in inputs.items() if hasattr(v, "to")}
        with torch.no_grad():
            features = self.model.get_image_features(**inputs)
            features = self._normalize(features)
        return features.detach().cpu().numpy()
