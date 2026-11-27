"""Execute the actual source contract during the measured Python suite."""
from contextlib import redirect_stdout
from io import StringIO
from pathlib import Path
import runpy
import unittest

ROOT = Path(__file__).resolve().parents[1]


class BackgroundProbeContractTests(unittest.TestCase):
    def test_actual_source_contract(self):
        output = StringIO()
        with redirect_stdout(output):
            runpy.run_path(str(ROOT / 'tools/check-contract.py'), run_name='__main__')
        self.assertIn('RESULT=PASS_SBR2A_SOURCE_CONTRACT', output.getvalue())
