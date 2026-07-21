import os
import unittest
from unittest.mock import patch

from reactor_tool.tool.report import _resolve_report_model


class ReportModelConfigTest(unittest.TestCase):
    def test_should_prefer_explicit_model(self):
        with patch.dict(os.environ, {"REPORT_MODEL": "report-env", "DEFAULT_MODEL": "default-env"}, clear=False):
            self.assertEqual("explicit-model", _resolve_report_model(" explicit-model "))

    def test_should_fall_back_from_report_model_to_default_model(self):
        with patch.dict(os.environ, {"REPORT_MODEL": "report-env", "DEFAULT_MODEL": "default-env"}, clear=False):
            self.assertEqual("report-env", _resolve_report_model())

        with patch.dict(os.environ, {"REPORT_MODEL": "", "DEFAULT_MODEL": "default-env"}, clear=False):
            self.assertEqual("default-env", _resolve_report_model())

    def test_should_fail_when_no_report_model_is_configured(self):
        with patch.dict(os.environ, {}, clear=True):
            with self.assertRaisesRegex(RuntimeError, "REPORT_MODEL or DEFAULT_MODEL"):
                _resolve_report_model()


if __name__ == "__main__":
    unittest.main()
