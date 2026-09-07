import unittest

from fastapi.testclient import TestClient

from app import app
from core.config import settings


class QaApiTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.client = TestClient(app)

    def setUp(self):
        self.original_backend = settings.llm_backend
        object.__setattr__(settings, "llm_backend", "mock")

    def tearDown(self):
        object.__setattr__(settings, "llm_backend", self.original_backend)

    def test_json_question(self):
        response = self.client.post(
            "/api/v1/qa",
            json={
                "question": "概括数据",
                "inputMode": "user_data_only",
                "taskType": "data_summary",
                "texts": ["温度升高", "报警次数增加"],
            },
        )
        self.assertEqual(200, response.status_code)
        body = response.json()
        self.assertEqual("mock", body["backend"])
        self.assertEqual("data_summary", body["task_type"])
        self.assertEqual(2, len(body["sources"]))

    def test_multipart_uses_json_validation_rules(self):
        response = self.client.post(
            "/api/v1/qa",
            data={"question": "x", "inputMode": "user_data_only", "topK": "0"},
        )
        self.assertEqual(400, response.status_code)

        response = self.client.post(
            "/api/v1/qa",
            data={"question": "x" * 8001, "inputMode": "user_data_only"},
        )
        self.assertEqual(400, response.status_code)

    def test_rejects_images_over_local_limit(self):
        files = [
            ("images", (f"{i}.png", b"\x89PNG\r\n\x1a\n", "image/png"))
            for i in range(3)
        ]
        response = self.client.post(
            "/api/v1/qa",
            data={"question": "比较图片", "inputMode": "user_data_only"},
            files=files,
        )
        self.assertEqual(400, response.status_code)
        self.assertIn("最多上传 2 张图片", response.text)


if __name__ == "__main__":
    unittest.main()
