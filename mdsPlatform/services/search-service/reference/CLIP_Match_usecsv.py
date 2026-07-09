import os
import pandas as pd
import json
import torch
import numpy as np
import random
from PIL import Image
from transformers import CLIPModel, CLIPProcessor
from collections import defaultdict


def set_deterministic(seed=42):
    """设置确定性计算，确保结果可重复"""
    torch.manual_seed(seed)
    torch.cuda.manual_seed_all(seed)
    np.random.seed(seed)
    random.seed(seed)
    torch.backends.cudnn.deterministic = True
    torch.backends.cudnn.benchmark = False


class UnifiedTextToImageMatcher:
    def __init__(self, model_path="clip_model_chinese"):
        # 设置随机种子确保可重复性
        set_deterministic(42)

        self.model_path = model_path
        self.device = "cuda" if torch.cuda.is_available() else "cpu"
        self.model = None
        self.processor = None

        # 统一的数据结构：按图片组织
        self.image_data = {}  # {image_path: {"texts": [text1, text2, ...], "feature": tensor}
        self.image_paths = []

        self.load_model()

    def load_model(self):
        """加载CLIP模型 - 修复中文模型加载问题"""
        print("Loading CLIP model...")
        try:
            if os.path.exists(self.model_path):
                print(f"Loading model from local path: {self.model_path}")

                # 检查是否是中文CLIP模型
                config_path = os.path.join(self.model_path, "config.json")
                if os.path.exists(config_path):
                    with open(config_path, 'r') as f:
                        config = json.load(f)

                    # 如果是中文CLIP模型，使用正确的模型类
                    if config.get("model_type") == "chinese_clip":
                        from transformers import ChineseCLIPModel, ChineseCLIPProcessor
                        self.model = ChineseCLIPModel.from_pretrained(
                            self.model_path,
                            local_files_only=True
                        )
                        self.processor = ChineseCLIPProcessor.from_pretrained(
                            self.model_path,
                            local_files_only=True
                        )
                    else:
                        # 普通CLIP模型
                        self.model = CLIPModel.from_pretrained(
                            self.model_path,
                            local_files_only=True
                        )
                        self.processor = CLIPProcessor.from_pretrained(
                            self.model_path,
                            local_files_only=True
                        )
                else:
                    # 如果没有配置文件，默认使用普通CLIP
                    self.model = CLIPModel.from_pretrained(
                        self.model_path,
                        local_files_only=True
                    )
                    self.processor = CLIPProcessor.from_pretrained(
                        self.model_path,
                        local_files_only=True
                    )
            else:
                # 如果本地模型不存在，回退到英文模型
                self.model = CLIPModel.from_pretrained("openai/clip-vit-base-patch32")
                self.processor = CLIPProcessor.from_pretrained("openai/clip-vit-base-patch32")
                # 保存模型供下次使用
                self.model.save_pretrained(self.model_path)
                self.processor.save_pretrained(self.model_path)

            self.model = self.model.to(self.device)
            self.model.eval()
            print(f"Model loaded successfully on {self.device}")
        except Exception as e:
            print(f"Model loading failed: {e}")
            # 如果中文模型加载失败，尝试回退到英文模型
            print("Trying to load English model as fallback...")
            try:
                self.model = CLIPModel.from_pretrained("openai/clip-vit-base-patch32")
                self.processor = CLIPProcessor.from_pretrained("openai/clip-vit-base-patch32")
                self.model = self.model.to(self.device)
                self.model.eval()
                print(f"English model loaded successfully on {self.device}")
            except Exception as e2:
                print(f"Fallback model loading also failed: {e2}")

    # 以下所有其他函数保持不变...
    def load_dataset(self, data_path, image_dir=None):
        """加载数据集"""
        print(f"Loading dataset from: {data_path}")

        if not os.path.exists(data_path):
            print(f"Path does not exist: {data_path}")
            return False

        try:
            if data_path.endswith('.csv'):
                return self.load_csv_by_image(data_path, image_dir)
            elif data_path.endswith('.json'):
                return self.load_coco_json_by_image(data_path, image_dir)
            else:
                print("Unsupported format")
                return False
        except Exception as e:
            print(f"Dataset loading failed: {e}")
            return False

    def load_csv_by_image(self, csv_path, image_dir=None):
        """按图片组织CSV数据"""
        print(f"Loading CSV: {csv_path}")
        df = pd.read_csv(csv_path)

        # 确定图像目录
        if image_dir:
            base_dir = image_dir
        else:
            base_dir = os.path.dirname(csv_path)

        # 按图片路径分组文本
        image_text_map = defaultdict(list)

        for _, row in df.iterrows():
            # 查找图片路径列
            img_path = None
            for col in ['image_path', 'file_name', 'filename', 'image', 'img_path']:
                if col in row and pd.notna(row[col]):
                    img_path = row[col]
                    break

            if not img_path:
                continue

            # 处理路径
            if not os.path.isabs(img_path):
                img_path = os.path.join(base_dir, img_path)

            # 查找文本列
            text = "image"
            for col in ['text', 'caption', 'description']:
                if col in row and pd.notna(row[col]):
                    text = row[col]
                    break

            if os.path.exists(img_path):
                image_text_map[img_path].append(text)

        # 填充到image_data
        for img_path, texts in image_text_map.items():
            self.image_data[img_path] = {"texts": texts, "feature": None}

        self.image_paths = list(self.image_data.keys())
        print(f"Loaded {len(self.image_data)} unique images from CSV")
        return len(self.image_data) > 0

    def load_coco_json_by_image(self, json_path, image_dir=None):
        """按图片组织COCO JSON数据"""
        print(f"Loading COCO JSON: {json_path}")

        with open(json_path, 'r', encoding='utf-8') as f:
            data = json.load(f)

        # 确定图像目录
        if image_dir:
            base_dir = image_dir
        else:
            base_dir = os.path.dirname(json_path)

        # 按图片组织数据
        image_text_map = defaultdict(list)

        if 'images' in data and 'annotations' in data:
            # 创建图像ID到文件名的映射
            image_id_to_file = {}
            for image in data['images']:
                image_id_to_file[image['id']] = image['file_name']

            # 收集每张图片的所有描述
            for annotation in data['annotations']:
                image_id = annotation['image_id']
                if image_id in image_id_to_file:
                    image_file = image_id_to_file[image_id]
                    image_path = os.path.join(base_dir, image_file)

                    if os.path.exists(image_path):
                        caption = annotation.get('caption', 'COCO image')
                        image_text_map[image_path].append(caption)

        # 填充到image_data
        for img_path, texts in image_text_map.items():
            self.image_data[img_path] = {"texts": texts, "feature": None}

        self.image_paths = list(self.image_data.keys())
        print(f"Loaded {len(self.image_data)} unique images from COCO JSON")
        return len(self.image_data) > 0

    def encode_images(self, batch_size=32):
        """统一的图片编码方式"""
        if not self.image_data:
            print("No images to encode")
            return False

        print(f"Encoding {len(self.image_data)} unique images with batch_size={batch_size}...")

        # 批量处理图片
        for i in range(0, len(self.image_paths), batch_size):
            batch_paths = self.image_paths[i:i + batch_size]
            batch_images = []
            valid_batch_paths = []

            # 加载批处理图片
            for img_path in batch_paths:
                try:
                    image = Image.open(img_path).convert('RGB')
                    batch_images.append(image)
                    valid_batch_paths.append(img_path)
                except Exception as e:
                    print(f"Failed to load {img_path}: {e}")
                    continue

            if not batch_images:
                continue

            # 统一的批量编码
            try:
                inputs = self.processor(
                    images=batch_images,
                    return_tensors="pt",
                    padding=True
                )
                inputs = {k: v.to(self.device) for k, v in inputs.items()}

                with torch.no_grad():
                    batch_features = self.model.get_image_features(**inputs)
                    # 统一的归一化方式
                    batch_features = batch_features / batch_features.norm(dim=-1, keepdim=True)

                # 为每张图片存储特征
                for j, img_path in enumerate(valid_batch_paths):
                    if j < len(batch_features):
                        self.image_data[img_path]["feature"] = batch_features[j].unsqueeze(0)

                print(f"Encoded {min(i + batch_size, len(self.image_paths))}/{len(self.image_paths)} images")

            except Exception as e:
                print(f"Batch encoding failed: {e}")
                # 如果批量失败，尝试逐个处理
                for j, img_path in enumerate(valid_batch_paths):
                    try:
                        self.encode_single_image(img_path)
                    except Exception as e2:
                        print(f"Failed to encode single image {img_path}: {e2}")
                        continue

        # 检查编码结果
        encoded_count = sum(1 for data in self.image_data.values() if data["feature"] is not None)
        print(f"Successfully encoded {encoded_count} images")
        return encoded_count > 0

    def encode_single_image(self, img_path):
        """单个图片编码（备用方法）"""
        image = Image.open(img_path).convert('RGB')
        inputs = self.processor(images=image, return_tensors="pt")
        inputs = {k: v.to(self.device) for k, v in inputs.items()}

        with torch.no_grad():
            feature = self.model.get_image_features(**inputs)
            feature = feature / feature.norm(dim=-1, keepdim=True)

        self.image_data[img_path]["feature"] = feature

    def create_unified_prompts(self, text_query):
        """统一的提示生成策略"""
        # 基础提示模板
        base_prompts = [
            text_query,
            f"a photo of {text_query}",
            f"image of {text_query}",
            f"picture of {text_query}",
        ]

        # 判断是否为中文查询
        chinese_chars = set('冰箱冰柜空调洗衣机热水器电视油烟机洗碗机')
        is_chinese = any(char in chinese_chars for char in text_query)

        if is_chinese:
            # 中文增强提示
            chinese_prompts = [
                f"{text_query} 图片",
                f"{text_query} 照片",
                f"{text_query} 图像",
                f"{text_query} 产品",
            ]
            base_prompts.extend(chinese_prompts)
        else:
            # 英文增强提示
            english_prompts = [
                f"product image of {text_query}",
                f"household {text_query}",
                f"electronic {text_query}",
                f"appliance {text_query}",
            ]
            base_prompts.extend(english_prompts)

        # 去重并返回
        return list(dict.fromkeys(base_prompts))

    def encode_text_unified(self, text_query):
        """统一的文本编码方式"""
        prompts = self.create_unified_prompts(text_query)

        text_features_list = []
        for prompt in prompts:
            try:
                inputs = self.processor(
                    text=prompt,
                    return_tensors="pt",
                    padding=True,
                    truncation=True,
                    max_length=77  # 固定长度
                )
                inputs = {k: v.to(self.device) for k, v in inputs.items()}

                with torch.no_grad():
                    features = self.model.get_text_features(**inputs)
                    # 统一的归一化方式
                    features = features / features.norm(dim=-1, keepdim=True)
                    text_features_list.append(features)
            except Exception as e:
                print(f"Prompt encoding failed: {e}")
                continue

        if not text_features_list:
            # 回退到基本编码
            inputs = self.processor(
                text=text_query,
                return_tensors="pt",
                padding=True,
                truncation=True,
                max_length=77
            )
            inputs = {k: v.to(self.device) for k, v in inputs.items()}
            with torch.no_grad():
                features = self.model.get_text_features(**inputs)
                features = features / features.norm(dim=-1, keepdim=True)
                return features

        # 平均所有提示的特征
        text_features = torch.mean(torch.cat(text_features_list, dim=0), dim=0, keepdim=True)
        text_features = text_features / text_features.norm(dim=-1, keepdim=True)

        return text_features

    def search_images(self, text_query, top_k=5):
        """搜索匹配的图片 - 统一编码方式"""
        if not self.image_data:
            print("No images available")
            return None

        # 检查是否有编码的特征
        valid_images = [(path, data) for path, data in self.image_data.items() if data["feature"] is not None]
        if not valid_images:
            print("No encoded images available")
            return None

        try:
            # 统一的文本编码
            text_features = self.encode_text_unified(text_query)

            # 构建图片特征矩阵
            image_features_list = []
            image_paths_list = []

            for img_path, data in valid_images:
                image_features_list.append(data["feature"])
                image_paths_list.append(img_path)

            image_feature_matrix = torch.cat(image_features_list, dim=0).T.to(self.device)

            # 计算相似度
            similarities = (text_features @ image_feature_matrix).squeeze()

            # 获取top-k结果
            top_k = min(top_k, len(similarities))
            top_scores, top_indices = torch.topk(similarities, top_k)

            results = []
            for score, idx in zip(top_scores, top_indices):
                img_path = image_paths_list[idx]
                data = self.image_data[img_path]
                results.append({
                    'image_path': img_path,
                    'score': score.item(),
                    'all_texts': data["texts"]
                })

            return results

        except Exception as e:
            print(f"Search failed: {e}")
            return None

    def format_results(self, results):
        """格式化输出结果"""
        if not results:
            return "No results found"

        output = []
        for i, result in enumerate(results, 1):
            output.append(f"{i}. Similarity: {result['score']:.4f}")
            output.append(f"   Image: {os.path.basename(result['image_path'])}")
            output.append(f"   All descriptions:")

            # 显示所有相关文本
            for j, text in enumerate(result['all_texts'], 1):
                # 截断长文本
                if len(text) > 100:
                    text = text[:100] + "..."
                output.append(f"      {j}. {text}")
            output.append("")  # 空行分隔

        return "\n".join(output)


def main():
    """主函数"""
    print("=== Unified Text-to-Image Search System ===")

    # 初始化匹配器
    matcher = UnifiedTextToImageMatcher()

    # 输入数据集路径
    data_path = input("Enter dataset path (CSV or JSON): ").strip()

    # 对于JSON文件，需要指定图片目录
    image_dir = None
    if data_path.endswith('.json'):
        image_dir = input("Enter image directory: ").strip()

    # 加载数据集
    if not matcher.load_dataset(data_path, image_dir):
        print("Failed to load dataset")
        return

    # 编码图片
    if not matcher.encode_images(batch_size=32):
        print("Failed to encode images")
        return

    # 开始搜索
    print("\nEnter text queries to search for images")
    print("Enter 'quit' to exit")

    while True:
        query = input("\nSearch query: ").strip()

        if query.lower() == 'quit':
            break

        if not query:
            continue

        print(f"Searching for: '{query}'")
        results = matcher.search_images(query, top_k=5)

        if results:
            print(f"\nFound {len(results)} matching images:")
            print(matcher.format_results(results))
        else:
            print("No matching images found")


if __name__ == "__main__":
    main()