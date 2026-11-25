"""Synthetic report tests validate the gate, not the Android coverage."""
import contextlib
import importlib.util
import io
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET

spec = importlib.util.spec_from_file_location(
    'coverage_gate', Path(__file__).resolve().parents[1] / 'tools/check-coverage.py')
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


class GateTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        for module in gate.REPORTS:
            source = self.root / module / 'src/main/Fixture.kt'
            source.parent.mkdir(parents=True)
            source.write_text('fun fixture() = true')
            self.report(module)

    def report(self, module, hit=8, missed=2, mapped=True, branch=True):
        path = self.root / gate.REPORTS[module]
        path.parent.mkdir(parents=True, exist_ok=True)
        report = ET.Element('report')
        package = ET.SubElement(report, 'package', name='fixture')
        if mapped:
            source = ET.SubElement(package, 'sourcefile', name='Fixture.kt')
            ET.SubElement(source, 'counter', type='LINE', covered='8', missed='2')
        for kind in ('LINE', 'BRANCH') if branch else ('LINE',):
            ET.SubElement(report, 'counter', type=kind, covered=str(hit), missed=str(missed))
        ET.ElementTree(report).write(path)
        return path

    def result(self):
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            return gate.run(self.root)

    def test_boundary_passes(self):
        self.assertEqual(self.result(), 0)

    def test_high_core_cannot_mask_low_app(self):
        self.report('core', 100, 0)
        self.report('app', 7, 3)
        self.assertEqual(self.result(), 2)

    def test_low_core(self):
        self.report('core', 79, 21)
        self.assertEqual(self.result(), 2)

    def test_missing_report(self):
        (self.root / gate.REPORTS['app']).unlink()
        self.assertEqual(self.result(), 2)

    def test_missing_source(self):
        self.report('app', mapped=False)
        self.assertEqual(self.result(), 2)

    def test_missing_branch(self):
        self.report('app', branch=False)
        self.assertEqual(self.result(), 2)

    def test_empty(self):
        self.report('app', 0, 0)
        self.assertEqual(self.result(), 2)

    def test_negative(self):
        self.report('app', 100, -1)
        self.assertEqual(self.result(), 2)

    def test_wrong_xml(self):
        self.report('app').write_text('<wrong/>')
        self.assertEqual(self.result(), 2)

    def test_malformed_xml(self):
        self.report('app').write_text('<report')
        self.assertEqual(self.result(), 2)

    def test_duplicate_counter(self):
        path = self.report('app')
        tree = ET.parse(path)
        ET.SubElement(tree.getroot(), 'counter', type='LINE', covered='10', missed='0')
        tree.write(path)
        self.assertEqual(self.result(), 2)
