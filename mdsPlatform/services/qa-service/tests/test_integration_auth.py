import unittest
from unittest.mock import patch

from fastapi.testclient import TestClient
from app import app
from adapters.search_client import SearchServiceClient
from core.config import settings


class RequestCredentialTest(unittest.TestCase):
    def test_multimodal_request_limits_visual_tokens_without_altering_original_image(self):
        from adapters.vllm_client import VllmClient
        client = VllmClient()
        messages = [{"role": "user", "content": [
            {"type": "image_url", "image_url": {"url": "data:image/jpeg;base64,original"}}
        ]}]
        with patch.object(client, "_request", return_value={}) as call:
            client.chat_completions(messages, max_tokens=96)
            payload = call.call_args.args[2]
            self.assertEqual(262144, payload["mm_processor_kwargs"]["max_pixels"])
            self.assertEqual(messages, payload["messages"])
            self.assertEqual(96, payload["max_tokens"])

    def test_consecutive_requests_do_not_share_user_credentials(self):
        with patch("api.routes.qa.QaService") as service:
            service.return_value.answer.side_effect = ValueError("test stops before generation")
            client = TestClient(app)
            for token in ("alice", "bob"):
                client.post("/api/v1/qa", json={"question": "test"},
                            headers={"Authorization": f"Bearer {token}"})
            self.assertEqual(["alice", "bob"],
                             [c.kwargs["bearer_token"] for c in service.call_args_list])

    def test_search_preserves_exact_index_and_request_token(self):
        with patch("adapters.search_client.requests.post") as post:
            post.return_value.json.return_value = {"items": []}
            client = SearchServiceClient("alice")
            client.backend_multimodal_search("dataset", "version", "chosen-index", "trend", 3)
            args = post.call_args.kwargs
            self.assertEqual("chosen-index", args["json"]["indexVersionId"])
            self.assertEqual("Bearer alice", args["headers"]["Authorization"])
            self.assertNotIn("Authorization", SearchServiceClient("").backend_headers)


if __name__ == "__main__":
    unittest.main()
