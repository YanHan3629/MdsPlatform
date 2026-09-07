import unittest

from services.intent_analyzer import RetrievalIntent
from services.retrieval_planner import RetrievalPlanner


class FakeAnalyzer:
    def __init__(self, intent=None, error=None):
        self.intent = intent
        self.error = error
        self.calls = 0

    def analyze(self, **kwargs):
        self.calls += 1
        if self.error:
            raise self.error
        return self.intent


def plan(planner, **overrides):
    values = {
        "question": "处理这张图片",
        "input_mode": "hybrid",
        "retrieval_mode": "auto",
        "retrieval_type": "auto",
        "use_search_service": True,
        "has_scope": True,
        "image_count": 1,
        "text_count": 0,
        "has_context": False,
    }
    values.update(overrides)
    return planner.plan(**values)


class RetrievalPlannerTest(unittest.TestCase):
    def test_explicit_retrieval_type_skips_model(self):
        analyzer = FakeAnalyzer()
        result = plan(RetrievalPlanner(analyzer), retrieval_type="image_to_text")
        self.assertEqual("image_to_text", result.retrieval_type)
        self.assertFalse(result.intent_analysis_used)
        self.assertEqual(0, analyzer.calls)

    def test_input_shape_skips_model(self):
        analyzer = FakeAnalyzer()
        result = plan(
            RetrievalPlanner(analyzer), input_mode="question_only", image_count=0
        )
        self.assertEqual("text_to_image", result.retrieval_type)
        self.assertEqual(0, analyzer.calls)

    def test_explicit_natural_language_skips_model(self):
        analyzer = FakeAnalyzer()
        result = plan(
            RetrievalPlanner(analyzer), question="根据这张图片查找相关资料"
        )
        self.assertEqual("image_to_text", result.retrieval_type)
        self.assertEqual(0, analyzer.calls)

        result = plan(RetrievalPlanner(analyzer), question="帮我找到相关资料")
        self.assertEqual("image_to_text", result.retrieval_type)
        self.assertEqual(0, analyzer.calls)

    def test_ambiguous_image_request_uses_model(self):
        analyzer = FakeAnalyzer(
            RetrievalIntent("none", "描述图片", False, "只需视觉问答", 0.92)
        )
        result = plan(RetrievalPlanner(analyzer))
        self.assertEqual("none", result.retrieval_type)
        self.assertTrue(result.intent_analysis_used)
        self.assertEqual(1, analyzer.calls)

    def test_model_cannot_disable_image_required_by_selected_route(self):
        analyzer = FakeAnalyzer(
            RetrievalIntent("image_to_text", "相关资料", False, "", 0.8)
        )
        result = plan(RetrievalPlanner(analyzer), question="帮我找到相关资料")
        self.assertEqual("image_to_text", result.retrieval_type)
        self.assertTrue(result.use_uploaded_images)

    def test_model_failure_falls_back_to_existing_text_search(self):
        analyzer = FakeAnalyzer(error=RuntimeError("model unavailable"))
        result = plan(RetrievalPlanner(analyzer))
        self.assertEqual("text_to_image", result.retrieval_type)
        self.assertTrue(result.intent_analysis_used)


if __name__ == "__main__":
    unittest.main()
