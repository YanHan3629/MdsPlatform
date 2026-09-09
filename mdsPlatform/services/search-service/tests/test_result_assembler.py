import unittest
import uuid

import pandas as pd

from services.result_assembler import ResultAssembler


class UnifiedResultAssemblerTest(unittest.TestCase):
    def test_representation_hits_are_grouped_by_original_asset(self):
        first = str(uuid.uuid4())
        second = str(uuid.uuid4())
        metadata = pd.DataFrame([
            {"asset_id": first, "logical_path": "/table.csv", "text": "first time window"},
            {"asset_id": first, "logical_path": "/table.csv", "text": "second time window"},
            {"asset_id": second, "logical_path": "/manual.pdf", "text": "matched PDF page"},
        ])

        items = ResultAssembler.assemble_unified_as_text_to_image(
            [0.92, 0.88, 0.81], [0, 1, 2], metadata, 2
        )

        self.assertEqual(2, len(items))
        self.assertEqual(first, str(items[0].assetId))
        self.assertEqual(["first time window", "second time window"], items[0].texts)
        self.assertEqual("/manual.pdf", items[1].logicalPath)


if __name__ == "__main__":
    unittest.main()
