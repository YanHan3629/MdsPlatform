import json
import os
import torch
from transformers import AutoModel, AutoProcessor, CLIPModel, CLIPProcessor


class ModelManager:
    _model = None
    _processor = None
    _device = "cuda" if torch.cuda.is_available() else "cpu"
    _loaded_model_path = None

    @classmethod
    def load(cls, model_path: str):
        if cls._model is not None and cls._processor is not None and cls._loaded_model_path == model_path:
            return cls._model, cls._processor, cls._device

        config_path = os.path.join(model_path, "config.json")
        if os.path.exists(config_path):
            with open(config_path, "r", encoding="utf-8") as f:
                config = json.load(f)
            if config.get("model_type") == "chinese_clip":
                cls._model = AutoModel.from_pretrained(model_path, local_files_only=True)
                cls._processor = AutoProcessor.from_pretrained(model_path, local_files_only=True)
            else:
                cls._model = CLIPModel.from_pretrained(model_path, local_files_only=True)
                cls._processor = CLIPProcessor.from_pretrained(model_path, local_files_only=True)
        else:
            cls._model = CLIPModel.from_pretrained(model_path)
            cls._processor = CLIPProcessor.from_pretrained(model_path)

        cls._model = cls._model.to(cls._device)
        cls._model.eval()
        cls._loaded_model_path = model_path
        return cls._model, cls._processor, cls._device
