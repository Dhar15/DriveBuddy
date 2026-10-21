import json, os, tempfile, unittest
from summarize_latency import percentile, load, summarise


class SummaryTests(unittest.TestCase):
    def test_percentile_nearest_rank(self):
        v = [10, 20, 30, 40, 50, 60, 70, 80, 90, 100]
        self.assertEqual(percentile(v, 50), 50)
        self.assertEqual(percentile(v, 90), 90)
        self.assertEqual(percentile([7], 90), 7)

    def test_summarise_ignores_nulls_and_bad_lines(self):
        lines = [
            {"end_to_ack": 20, "end_to_voice": 1200, "command": "Echo"},
            {"end_to_ack": None, "end_to_voice": None, "command": "error: no match"},
            {"end_to_ack": 60, "end_to_voice": 1800, "command": "Echo"},
        ]
        with tempfile.NamedTemporaryFile("w", suffix=".jsonl", delete=False) as f:
            for l in lines:
                f.write(json.dumps(l) + "\n")
            f.write("not json\n\n")
            path = f.name
        try:
            rows = load(path)
        finally:
            os.unlink(path)
        self.assertEqual(len(rows), 3)
        s = summarise(rows)
        self.assertEqual(s["end_to_ack"]["n"], 2)
        self.assertEqual(s["end_to_voice"]["p50"], 1200)


if __name__ == "__main__":
    unittest.main()
