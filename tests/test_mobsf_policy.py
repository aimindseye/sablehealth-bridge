"""Regression cases for actual SARIF and fail-closed source qualification."""
import copy
import hashlib
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from contextlib import redirect_stdout, redirect_stderr
from io import StringIO
from tools import check_mobsf_findings as gate

ROOT = Path(__file__).resolve().parents[1]
BADGING = "package: name='io.github.aimindseye.sablehealthbridge' versionCode='1'\nsdkVersion:'30'\ntargetSdkVersion:'36'\n"


class PolicyTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.baseline = json.loads((ROOT / 'config/mobsf-known-findings.json').read_text())
        self.report = json.loads((ROOT / 'tests/fixtures/sbr1-mobsf-pr1.sarif').read_text())
        for name in self.baseline['frozen']:
            p = self.root / name
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_bytes(b'synthetic pinned source')
            self.baseline['frozen'][name] = hashlib.sha256(p.read_bytes()).hexdigest()
        self.out = StringIO()

    def verify(self, *, outcome='success', badging=BADGING):
        with redirect_stdout(self.out):
            gate.verify(self.baseline, self.report, badging, self.root, outcome)

    def test_exact_and_order(self):
        self.verify()
        self.report['runs'][0]['results'].reverse()
        self.report['runs'][0]['results'][0]['ruleIndex'] = 99
        self.verify()
        self.assertIn('RELEASE_HOLD', self.out.getvalue())

    def test_changed_findings(self):
        original = copy.deepcopy(self.report)
        for mode in ('duplicate', 'removed', 'snippet', 'severity', 'path', 'message'):
            self.report = copy.deepcopy(original)
            rows = self.report['runs'][0]['results']
            if mode == 'duplicate':
                rows.append(copy.deepcopy(rows[0]))
            elif mode == 'removed':
                rows.pop()
            elif mode == 'snippet':
                rows[0]['locations'][0]['physicalLocation']['region']['snippet']['text'] = 'changed'
            elif mode == 'severity':
                rows[0]['level'] = 'error'
            elif mode == 'path':
                rows[0]['locations'][0]['physicalLocation']['artifactLocation']['uri'] = 'other.kt'
            else:
                rows[0]['message']['text'] = 'changed'
            with self.subTest(mode=mode), self.assertRaises(ValueError):
                self.verify()

    def test_badging_and_outcome(self):
        for bad in (BADGING.replace("'30'", "'28'"), BADGING.replace("'36'", "'28'"), 'wrong package'):
            with self.assertRaises(ValueError):
                self.verify(badging=bad)
        with self.assertRaises(ValueError):
            self.verify(outcome='failure')

    def test_invalid_policy_and_report(self):
        for key, val in [('schema', 2), ('scope', 'release')]:
            prev = self.baseline[key]
            self.baseline[key] = val
            with self.assertRaises(ValueError):
                self.verify()
            self.baseline[key] = prev
        original = copy.deepcopy(self.report)
        for mode in ('format', 'runs', 'version', 'name', 'empty', 'failed'):
            self.report = copy.deepcopy(original)
            run = self.report['runs'][0]
            if mode == 'format':
                self.report['version'] = '1.0'
            elif mode == 'runs':
                self.report['runs'].append(run)
            elif mode == 'version':
                run['tool']['driver']['version'] = 'other'
            elif mode == 'name':
                run['tool']['driver']['name'] = 'other'
            elif mode == 'empty':
                run['invocations'] = []
            else:
                run['invocations'][0]['executionSuccessful'] = False
            with self.subTest(mode=mode), self.assertRaises(ValueError):
                self.verify()

    def test_source_drift(self):
        (self.root / next(iter(self.baseline['frozen']))).write_text('modified')
        with self.assertRaises(ValueError):
            self.verify()

    def test_cli_success_and_failure(self):
        (self.root / 'config').mkdir()
        (self.root / 'config/mobsf-known-findings.json').write_text(json.dumps(self.baseline))
        (self.root / 'mobsfscan-results.sarif').write_text(json.dumps(self.report))
        (self.root / 'mobsf-apk-badging.txt').write_text(BADGING)
        with patch.dict(os.environ, {'MOBSF_SCAN_OUTCOME': 'success'}), redirect_stdout(self.out), redirect_stderr(self.out):
            self.assertEqual(gate.main(self.root), 0)
            (self.root / 'mobsfscan-results.sarif').write_text('{}')
            self.assertEqual(gate.main(self.root), 2)
            (self.root / 'mobsfscan-results.sarif').unlink()
            self.assertEqual(gate.main(self.root), 2)


if __name__ == '__main__':
    unittest.main()
