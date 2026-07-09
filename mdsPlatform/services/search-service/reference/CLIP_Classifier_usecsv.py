import os
import pandas as pd
import json
import torch
import numpy as np
import torch.nn.functional as F
from PIL import Image
from transformers import AutoModel, AutoProcessor  # 使用Auto类支持中文模型


class CLIPImageTextMatcher:
    def __init__(self, model_path="clip_model_chinese"):
        # 相对脚本目录解析，避免受启动目录影响
        if not os.path.isabs(model_path):
            model_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), model_path)

        self.model_path = model_path
        self.device = "cuda" if torch.cuda.is_available() else "cpu"
        self.model = None
        self.processor = None
        self.image_paths = []
        self.text_descriptions = []
        self.categories = []
        self.text_features = None

        self.load_model()

    def _extract_feature_tensor(self, outputs, mode="text"):
        """兼容不同模型输出格式，统一转成 [B, D] Tensor"""
        if isinstance(outputs, torch.Tensor):
            return outputs

        if mode == "text":
            candidate_attrs = ["text_embeds", "pooler_output", "last_hidden_state"]
        else:
            candidate_attrs = ["image_embeds", "pooler_output", "last_hidden_state"]

        for attr in candidate_attrs:
            if hasattr(outputs, attr):
                value = getattr(outputs, attr)
                if isinstance(value, torch.Tensor):
                    if value.dim() == 3:
                        return value[:, 0, :]
                    return value

        raise TypeError(f"Unsupported output type for {mode}: {type(outputs)}")

    def load_model(self):
        """加载CLIP模型 - 修改为使用AutoModel支持中文模型"""
        print("Loading CLIP model...")
        try:
            if os.path.exists(self.model_path):
                # 使用AutoModel和AutoProcessor自动选择正确类别
                self.model = AutoModel.from_pretrained(self.model_path, local_files_only=True)
                self.processor = AutoProcessor.from_pretrained(self.model_path, local_files_only=True)
                self.model = self.model.to(self.device)
                self.model.eval()
                print("Model loaded successfully")
                return True
            else:
                print("Model path does not exist")
                return False
        except Exception as e:
            print(f"Model loading failed: {e}")
            return False

    def load_dataset(self, data_path, image_dir=None):
        """加载数据集，支持多种格式"""
        if not data_path or not str(data_path).strip():
            print("Dataset path is empty. Please provide a CSV/JSON file path or a folder path.")
            return False

        data_path = os.path.normpath(data_path)

        if not os.path.exists(data_path):
            print(f"Path does not exist: {data_path}")
            return False

        try:
            if data_path.endswith('.csv'):
                return self.load_csv(data_path, image_dir)
            elif data_path.endswith('.json'):
                return self.load_coco_json(data_path, image_dir)
            elif os.path.isdir(data_path):
                return self.load_image_text_pairs(data_path)
            else:
                print("Unsupported format. Please use CSV, JSON, or a directory of image-text pairs.")
                return False
        except Exception as e:
            print(f"Dataset loading failed: {e}")
            return False

    def load_csv(self, csv_path, image_dir=None):
        """加载CSV文件"""
        df = pd.read_csv(csv_path)

        # 确定图像目录
        if image_dir:
            base_dir = image_dir
        else:
            base_dir = os.path.dirname(csv_path)

        self.image_paths = []
        self.text_descriptions = []
        self.categories = []

        for _, row in df.iterrows():
            # 处理图像路径
            img_path = row['image_path']
            if not os.path.isabs(img_path):
                img_path = os.path.join(base_dir, img_path)

            if os.path.exists(img_path):
                self.image_paths.append(img_path)
                self.text_descriptions.append(row['text'])
                self.categories.append(row.get('category', 'Unknown'))

        print(f"Loaded {len(self.image_paths)} samples from CSV")
        return len(self.image_paths) > 0

    def load_coco_json(self, json_path, image_dir=None):
        """加载COCO格式的JSON文件"""
        with open(json_path, 'r', encoding='utf-8') as f:
            data = json.load(f)

        # 确定图像目录
        if image_dir:
            base_dir = image_dir
        else:
            # 尝试从JSON路径推断图像目录
            json_dir = os.path.dirname(json_path)
            # 常见COCO目录结构
            possible_dirs = [
                os.path.join(json_dir, '..', 'train2017'),
                os.path.join(json_dir, '..', 'val2017'),
                os.path.join(json_dir, '..', 'images'),
                json_dir
            ]

            base_dir = None
            for dir_path in possible_dirs:
                dir_path = os.path.abspath(dir_path)
                if os.path.exists(dir_path):
                    base_dir = dir_path
                    break

            if not base_dir:
                print("Could not find image directory. Please specify with --image_dir")
                return False

        self.image_paths = []
        self.text_descriptions = []
        self.categories = []

        # 处理COCO格式
        if 'images' in data and 'annotations' in data:
            # 创建图像ID到文件名的映射
            image_id_to_file = {}
            for image in data['images']:
                image_id_to_file[image['id']] = image['file_name']

            # 处理标注
            for annotation in data['annotations']:
                image_id = annotation['image_id']
                if image_id in image_id_to_file:
                    image_file = image_id_to_file[image_id]
                    image_path = os.path.join(base_dir, image_file)

                    if os.path.exists(image_path):
                        self.image_paths.append(image_path)
                        self.text_descriptions.append(annotation['caption'])
                        self.categories.append('COCO')

        print(f"Loaded {len(self.image_paths)} samples from COCO JSON")
        return len(self.image_paths) > 0

    def load_image_text_pairs(self, data_dir):
        """加载图片和文本对文件夹"""
        self.image_paths = []
        self.text_descriptions = []
        self.categories = []

        # 支持的文件扩展名
        image_exts = {'.jpg', '.jpeg', '.png', '.bmp'}
        text_exts = {'.txt', '.json'}

        # 收集所有图像文件
        image_files = []
        for file in os.listdir(data_dir):
            file_ext = os.path.splitext(file)[1].lower()
            if file_ext in image_exts:
                image_files.append(file)

        # 为每个图像文件查找对应的文本文件
        for image_file in image_files:
            image_path = os.path.join(data_dir, image_file)
            base_name = os.path.splitext(image_file)[0]

            # 查找对应的文本文件
            text_content = ""
            for ext in text_exts:
                text_file = os.path.join(data_dir, base_name + ext)
                if os.path.exists(text_file):
                    try:
                        if ext == '.txt':
                            with open(text_file, 'r', encoding='utf-8') as f:
                                text_content = f.read().strip()
                        else:  # JSON
                            with open(text_file, 'r', encoding='utf-8') as f:
                                text_data = json.load(f)
                                text_content = text_data.get('text', text_data.get('caption', ''))
                        break
                    except Exception as e:
                        print(f"Error reading text file {text_file}: {e}")
                        continue

            if text_content:
                self.image_paths.append(image_path)
                self.text_descriptions.append(text_content)
                self.categories.append('Custom')

        print(f"Loaded {len(self.image_paths)} image-text pairs from directory")
        return len(self.image_paths) > 0

    def encode_texts(self):
        """编码文本描述 - 修改为支持中文模型"""
        if not self.text_descriptions:
            print("No text descriptions to encode")
            return False

        print("Encoding text descriptions...")

        # 过滤空文本
        valid_texts = []
        valid_indices = []

        for i, text in enumerate(self.text_descriptions):
            if text and str(text).strip():
                valid_texts.append(str(text))
                valid_indices.append(i)

        if not valid_texts:
            print("All text descriptions are empty")
            return False

        # 批量编码
        batch_size = 32
        all_features = []

        for i in range(0, len(valid_texts), batch_size):
            batch_texts = valid_texts[i:i + batch_size]

            try:
                inputs = self.processor(
                    text=batch_texts,
                    return_tensors="pt",
                    padding=True,
                    truncation=True,
                    max_length=77
                )
                # 过滤掉中文CLIP模型不支持的参数
                inputs = {k: v for k, v in inputs.items() if k in ['input_ids', 'attention_mask']}
                inputs = {k: v.to(self.device) for k, v in inputs.items()}

                with torch.no_grad():
                    text_outputs = self.model.get_text_features(**inputs)
                    features = self._extract_feature_tensor(text_outputs, mode="text")
                    features = F.normalize(features, dim=-1)
                    all_features.append(features.cpu())

                print(f"Encoded {min(i + batch_size, len(valid_texts))}/{len(valid_texts)}")

            except Exception as e:
                print(f"Batch encoding failed: {e}")
                continue

        if all_features:
            text_features = torch.cat(all_features, dim=0)
            self.text_features = text_features.T.to(self.device)

            # 更新有效数据
            self.text_descriptions = [self.text_descriptions[i] for i in valid_indices]
            self.categories = [self.categories[i] for i in valid_indices]
            self.image_paths = [self.image_paths[i] for i in valid_indices]

            print(f"Text encoding completed. Valid descriptions: {len(valid_texts)}")
            return True
        else:
            print("Text encoding failed")
            return False

    def match_image(self, image_path, top_k=3):
        """匹配图像到文本"""
        try:
            if not os.path.exists(image_path):
                print(f"Image does not exist: {image_path}")
                return None

            # 处理图像
            image = Image.open(image_path).convert('RGB')
            inputs = self.processor(images=image, return_tensors="pt")
            inputs = {k: v.to(self.device) for k, v in inputs.items()}

            # 提取图像特征
            with torch.no_grad():
                image_outputs = self.model.get_image_features(**inputs)
                image_features = self._extract_feature_tensor(image_outputs, mode="image")
                image_features = F.normalize(image_features, dim=-1)

            # 计算相似度
            similarities = (image_features @ self.text_features).squeeze()

            # 获取top-k结果
            top_k = min(top_k, len(similarities))
            top_scores, top_indices = torch.topk(similarities, top_k)

            results = []
            for score, idx in zip(top_scores, top_indices):
                idx = idx.item()
                results.append({
                    'category': self.categories[idx],
                    'similarity_score': score.item(),
                    'text_description': self.text_descriptions[idx],
                    'matched_image': os.path.basename(self.image_paths[idx])
                })

            return results

        except Exception as e:
            print(f"Matching failed: {e}")
            return None

    def interactive_test(self):
        """交互式测试"""
        print("\nImage-Text Matching Test")
        print("Enter image path to match, or 'quit' to exit")

        while True:
            image_path = input("\nEnter image path: ").strip()

            if image_path.lower() == 'quit':
                print("Goodbye!")
                break

            if not image_path:
                continue

            if not os.path.exists(image_path):
                print(f"Image does not exist: {image_path}")
                continue

            print(f"Testing image: {os.path.basename(image_path)}")
            results = self.match_image(image_path, top_k=3)

            if results:
                print("\nMatching results:")
                for i, result in enumerate(results, 1):
                    print(f"{i}. Category: {result['category']}")
                    print(f"   Similarity: {result['similarity_score']:.4f}")
                    print(f"   Description: {result['text_description']}")
                    print(f"   Matched image: {result['matched_image']}")
                    print()
            else:
                print("No matching results")


def main():
    """主函数"""
    print("CLIP Image-Text Matching System")

    # 初始化匹配器
    matcher = CLIPImageTextMatcher(model_path="clip_model_chinese")

    # 模型未加载成功时直接退出，避免后续 NoneType 错误
    if matcher.model is None or matcher.processor is None:
        print("Model/Processor not loaded. Please check model directory.")
        return

    # 输入数据集路径
    default_json = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "datasets", "captions_val2017.json")
    print(f"Example dataset path: {default_json}")
    data_path = input("Enter dataset path (CSV,json or directory): ").strip()

    if not data_path:
        print("Dataset path cannot be empty.")
        return

    # 对于JSON文件，可能需要指定图像目录
    image_dir = None
    if data_path.endswith('.json'):
        image_dir = input("Enter image directory (or press Enter to auto-detect): ").strip()
        if not image_dir:
            image_dir = None

    if not matcher.load_dataset(data_path, image_dir):
        return

    # 编码文本
    if not matcher.encode_texts():
        return

    # 开始测试
    matcher.interactive_test()


if __name__ == "__main__":
    main()