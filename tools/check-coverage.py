#!/usr/bin/env python3
"""Fail-closed first-release coverage gate; each module must reach 80%."""
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent
REPORTS = {
    'core': 'core/build/reports/jacoco/test/jacocoTestReport.xml',
    'app': 'app/build/reports/coverage/test/debug/report.xml',
}
MINIMUM = 80


def counts(counter):
    hit, missed = (int(counter.attrib[key]) for key in ('covered', 'missed'))
    if min(hit, missed) < 0:
        raise ValueError('negative counter')
    return hit, hit + missed


def measure(root, module, relative):
    report = ET.parse(root / relative).getroot()
    if report.tag != 'report':
        raise ValueError('not a JaCoCo report')
    # Verify all production Kotlin source files are represented, even those with zero hits.
    sources = {path.name for path in (root / module / 'src/main').rglob('*.kt')}
    mapped = {node.attrib['name'] for node in report.findall('./package/sourcefile')
              if node.find("counter[@type='LINE']") is not None}
    if not sources or not sources <= mapped:
        raise ValueError('missing production source mapping: ' + ', '.join(sorted(sources - mapped)))
    covered = total = 0
    for kind in ('LINE', 'BRANCH'):
        selected = report.findall(f"counter[@type='{kind}']")
        if len(selected) != 1:
            raise ValueError(f'missing or duplicate {kind} counter')
        hit, possible = counts(selected[0])
        if not possible:
            raise ValueError(f'empty {kind} counter')
        print(module, kind, f'{hit}/{possible}')
        covered += hit
        total += possible
    print(f'{module.upper()}_COMBINED_COVERAGE={100 * covered / total:.2f}%')
    return covered, total


def run(root):
    results = []
    for module, relative in REPORTS.items():
        try:
            results.append(measure(root, module, relative))
        except (OSError, ET.ParseError, ValueError, KeyError) as error:
            print(f'HOLD_COVERAGE_INVALID_{module.upper()}: {error}', file=sys.stderr)
            return 2
    covered = sum(hit for hit, _ in results)
    total = sum(possible for _, possible in results)
    print(f'LOCAL_COMBINED_COVERAGE={100 * covered / total:.2f}%')
    if any(hit * 100 < possible * MINIMUM for hit, possible in results):
        print('HOLD_LOCAL_COVERAGE_BELOW_80')
        return 2
    print('RESULT=PASS_LOCAL_COVERAGE')
    return 0


if __name__ == '__main__':
    sys.exit(run(ROOT))
