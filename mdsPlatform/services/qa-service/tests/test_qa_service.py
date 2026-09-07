import math
import unittest
from uuid import uuid4

from core.config import settings
from services.qa_service import QaService


class FakeClient:
    def __init__(self):
        self.messages = None
        self.calls = 0

    def chat_completions(self, messages, **kwargs):
        self.calls += 1
        self.messages = messages
        return {
            "id": "test-request",
            "model": "test-vlm",
            "choices": [
                {
                    "message": {"content": "测试回答[来源1]"},
                    "logprobs": {"content": [{"logprob": -1.0}]},
                }
            ],
            "usage": {"prompt_tokens": 10, "completion_tokens": 2, "total_tokens": 12},
        }


class FakeSearchClient:
    def __init__(self, shared_asset=False):
        self.text_calls = 0
        self.image_calls = 0
        self.shared_asset = shared_asset
        self.asset_id = str(uuid4())

    def text_to_image(self, *args, **kwargs):
        self.text_calls += 1
        return [{
            "assetId": self.asset_id,
            "score": 0.8,
            "logicalPath": "/image.png",
            "texts": ["冰箱部件"],
        }]

    def image_to_text(self, *args, **kwargs):
        self.image_calls += 1
        return [{
            "assetId": self.asset_id if self.shared_asset else str(uuid4()),
            "score": 0.7,
            "logicalPath": "/text.txt",
            "text": "部件说明",
        }]


class QaServiceTest(unittest.TestCase):
    def setUp(self):
        self.original_backend = settings.llm_backend
        object.__setattr__(settings, "llm_backend", "vllm")

    def tearDown(self):
        object.__setattr__(settings, "llm_backend", self.original_backend)

    def test_vllm_response_and_confidence(self):
        client = FakeClient()
        response = QaService(client).answer(
            question="测试问题",
            input_mode="user_data_only",
        )
        self.assertEqual("测试回答", response.answer)
        self.assertEqual("test-vlm", response.model)
        self.assertEqual("test-vlm", response.meta.model_name)
        self.assertAlmostEqual(math.exp(-1.0), response.confidence, places=4)

    def test_prompt_does_not_invent_sources_when_evidence_is_empty(self):
        client = FakeClient()
        QaService(client).answer(question="测试问题", input_mode="user_data_only")
        system_prompt = client.messages[0]["content"]
        self.assertIn("不得虚构[来源n]", system_prompt)

    def test_rejects_images_over_prompt_limit(self):
        images = [(f"{i}.jpg", b"image") for i in range(3)]
        with self.assertRaisesRegex(ValueError, "最多上传 2 张图片"):
            QaService(FakeClient()).answer(
                question="比较图片",
                input_mode="user_data_only",
                images=images,
            )

    def test_explicit_image_to_text_skips_intent_model(self):
        client = FakeClient()
        service = QaService(client)
        search = FakeSearchClient()
        service._search_client = search
        response = service.answer(
            question="查找关联说明",
            input_mode="hybrid",
            dataset_id=uuid4(),
            version_id=uuid4(),
            index_version_id=uuid4(),
            images=[("query.jpg", b"image")],
            retrieval_type="image_to_text",
        )
        self.assertEqual(1, search.image_calls)
        self.assertEqual(0, search.text_calls)
        self.assertEqual("image_to_text", response.meta.retrieval_type)
        self.assertFalse(response.meta.intent_analysis_used)
        # 只有最终回答调用一次模型，未产生额外意图分析调用。
        self.assertEqual(1, client.calls)

    def test_dual_search_deduplicates_same_asset(self):
        service = QaService(FakeClient())
        search = FakeSearchClient(shared_asset=True)
        service._search_client = search
        response = service.answer(
            question="结合图片和文字检索",
            input_mode="hybrid",
            dataset_id=uuid4(),
            version_id=uuid4(),
            index_version_id=uuid4(),
            images=[("query.jpg", b"image")],
            retrieval_type="dual",
        )
        self.assertEqual(1, search.text_calls)
        self.assertEqual(1, search.image_calls)
        self.assertEqual(1, len(response.retrieved))
        self.assertEqual("search_dual_match", response.retrieved[0].source_type)


if __name__ == "__main__":
    unittest.main()
