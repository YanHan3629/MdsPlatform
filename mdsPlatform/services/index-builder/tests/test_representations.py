import json
import tempfile
import unittest
from pathlib import Path

import pandas as pd
from PIL import Image

from representations import build_representations, describe_table, detect_temporal_column


class TemporalRepresentationTest(unittest.TestCase):
    def test_time_series_metadata_and_chunks_keep_time_order_and_range(self):
        frame = pd.DataFrame({
            "device_id": ["A", "A", "A", "B", "B"],
            "timestamp": [
                "2026-01-03T00:00:00Z",
                "2026-01-01T00:00:00Z",
                "2026-01-02T00:00:00Z",
                "2026-01-01T00:00:00Z",
                "2026-01-02T00:00:00Z",
            ],
            "temperature": [30.0, 10.0, 20.0, 8.0, 6.0],
        })

        attributes, units = describe_table(frame, "temperature.csv")

        temporal = attributes["temporal"]
        self.assertTrue(temporal["detected"])
        self.assertEqual("timestamp", temporal["timeColumn"])
        self.assertEqual("mixed-by-series", temporal["ordering"])
        self.assertEqual("device_id", temporal["seriesKey"])
        self.assertEqual(0, temporal["duplicateTimestampCount"])
        self.assertEqual("2026-01-01T00:00:00Z", temporal["timeStart"])
        self.assertEqual("2026-01-03T00:00:00Z", temporal["timeEnd"])
        metric_units = [unit for unit in units if unit["kind"].startswith("temporal-") and unit["kind"] != "temporal-overview"]
        self.assertEqual(4, len(metric_units))
        self.assertTrue(all(unit["locator"]["sortedByTime"] for unit in metric_units))
        self.assertTrue(any("上升" in unit["text"] and "device_id=A" in unit["text"] for unit in metric_units))
        self.assertTrue(any("下降" in unit["text"] and "device_id=B" in unit["text"] for unit in metric_units))

    def test_epoch_milliseconds_are_detected_only_for_semantic_time_column(self):
        frame = pd.DataFrame({
            "timestamp": [1767225600000, 1767225660000, 1767225720000],
            "serial_number": [1000000000000, 2000000000000, 3000000000000],
            "value": [1.0, 2.0, 3.0],
        })

        column, parsed, confidence = detect_temporal_column(frame)

        self.assertEqual("timestamp", column)
        self.assertGreaterEqual(confidence, 0.9)
        self.assertEqual(3, int(parsed.notna().sum()))

    def test_csv_and_jsonl_generate_temporal_manifests_and_skip_internal_indexes(self):
        with tempfile.TemporaryDirectory() as input_name, tempfile.TemporaryDirectory() as output_name:
            input_dir = Path(input_name)
            output_dir = Path(output_name)
            (input_dir / "series.csv").write_text(
                "time,value\n2026-01-01T00:00:00Z,1\n2026-01-01T01:00:00Z,4\n",
                encoding="utf-8",
            )
            (input_dir / "events.jsonl").write_text(
                "\n".join([
                    json.dumps({"recorded_time": "2026-01-01T00:00:00Z", "pressure": 5}),
                    json.dumps({"recorded_time": "2026-01-01T00:01:00Z", "pressure": 7}),
                ]),
                encoding="utf-8",
            )
            internal = input_dir / "indexes"
            internal.mkdir()
            (internal / "faiss.index").write_bytes(b"not-business-data")

            bundle = build_representations(input_dir, output_dir, "11111111-1111-1111-1111-111111111111")

            self.assertEqual(2, len(bundle.manifests))
            self.assertEqual({"CSV", "JSONL"}, {item["sourceFormat"] for item in bundle.manifests})
            self.assertTrue(all(item["attributes"]["sheets"]["root"]["temporal"]["detected"] for item in bundle.manifests))
            self.assertTrue(any(row["kind"] == "temporal-trend" for row in bundle.text_units))
            self.assertTrue(any(row["kind"] == "temporal-extrema" for row in bundle.text_units))
            self.assertEqual(2, len(bundle.visual_units))
            self.assertTrue(all(Path(row["actual_path"]).exists() for row in bundle.visual_units))


class CurrentFormatRepresentationTest(unittest.TestCase):
    def test_current_business_formats_generate_traceable_representations(self):
        import fitz
        from openpyxl import Workbook

        with tempfile.TemporaryDirectory() as input_name, tempfile.TemporaryDirectory() as output_name:
            input_dir = Path(input_name)
            output_dir = Path(output_name)
            Image.new("RGB", (32, 32), "red").save(input_dir / "photo.png")
            (input_dir / "vector.svg").write_text(
                '<svg xmlns="http://www.w3.org/2000/svg" width="40" height="40"><rect width="40" height="40" fill="blue"/></svg>',
                encoding="utf-8",
            )
            (input_dir / "notes.txt").write_text("冷藏室温度记录", encoding="utf-8")
            (input_dir / "records.json").write_text(
                json.dumps([{"date": "2026-01-01", "value": 1}, {"date": "2026-01-02", "value": 2}]),
                encoding="utf-8",
            )
            workbook = Workbook()
            sheet = workbook.active
            sheet.title = "temperature"
            sheet.append(["time", "value"])
            sheet.append(["2026-01-01T00:00:00Z", 1])
            sheet.append(["2026-01-01T01:00:00Z", 2])
            workbook.save(input_dir / "series.xlsx")

            document = fitz.open()
            page = document.new_page()
            page.insert_text((72, 72), "Refrigerator maintenance manual")
            document.save(input_dir / "manual.pdf")
            document.close()

            (input_dir / "mesh.obj").write_text(
                "v 0 0 0\nv 1 0 0\nv 0 1 0\nf 1 2 3\n",
                encoding="utf-8",
            )
            (input_dir / "assembly.step").write_text(
                "ISO-10303-21;\n#1=PRODUCT('door','',(),());\n#2=CARTESIAN_POINT('',(0.,0.,0.));\nEND-ISO-10303-21;",
                encoding="utf-8",
            )

            bundle = build_representations(input_dir, output_dir, "11111111-1111-1111-1111-111111111111")

            self.assertEqual(
                {"PNG", "SVG", "TXT", "JSON", "XLSX", "PDF", "OBJ", "STEP"},
                {item["sourceFormat"] for item in bundle.manifests},
            )
            self.assertTrue(all(item["textUnits"] for item in bundle.manifests))
            pdf = next(item for item in bundle.manifests if item["sourceFormat"] == "PDF")
            self.assertEqual(1, pdf["attributes"]["pageCount"])
            obj = next(item for item in bundle.manifests if item["sourceFormat"] == "OBJ")
            self.assertEqual(3, obj["attributes"]["vertexCount"])
            self.assertTrue(any(unit["kind"] == "pdf-page" for unit in pdf["visualUnits"]))
            svg = next(item for item in bundle.manifests if item["sourceFormat"] == "SVG")
            self.assertTrue(any(unit["kind"] == "source-image" for unit in svg["visualUnits"]))


if __name__ == "__main__":
    unittest.main()
