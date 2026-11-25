"""Strict, temporary source-CI ratchet; never a binary security approval."""
from collections import Counter
import hashlib
import json
import os
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
PREFIX = 'file:///home/runner/work/sablehealth-bridge/sablehealth-bridge/'


def fingerprint(result):
    """Ignore only volatile rule order and the exact runner checkout prefix."""
    item = json.loads(json.dumps(result))
    item.pop('ruleIndex', None)
    for location in item['locations']:
        artifact = location['physicalLocation']['artifactLocation']
        artifact['uri'] = artifact['uri'].removeprefix(PREFIX)
    return json.dumps(item, sort_keys=True, separators=(',', ':'))


def verify(baseline, report, badging, root, outcome):
    if outcome != 'success':
        raise ValueError('scanner did not complete successfully')
    if baseline['schema'] != 1 or baseline['scope'] != 'SOURCE_CI_RATCHET_ONLY_RELEASE_HOLD':
        raise ValueError('unexpected policy')
    for name, expected in baseline['frozen'].items():
        if hashlib.sha256((root / name).read_bytes()).hexdigest() != expected:
            raise ValueError(f'source evidence changed: {name}')
    for pattern in (r"^package: name='io\.github\.aimindseye\.sablehealthbridge' ",
                    r"^sdkVersion:'30'$", r"^targetSdkVersion:'36'$"):
        if not re.search(pattern, badging, re.MULTILINE):
            raise ValueError('effective release APK identity or SDK level changed')
    if report['version'] != '2.1.0' or len(report['runs']) != 1:
        raise ValueError('unexpected SARIF format')
    run = report['runs'][0]
    driver = run['tool']['driver']
    if (driver['name'], driver['version']) != (baseline['scanner'], baseline['version']):
        raise ValueError('scanner identity changed')
    if not run['invocations'] or any(i['executionSuccessful'] is not True for i in run['invocations']):
        raise ValueError('SARIF invocation unsuccessful')
    expected = Counter(fingerprint(f['result']) for f in baseline['findings'])
    actual = Counter(fingerprint(f) for f in run['results'])
    if not expected or expected != actual:
        raise ValueError(f'finding drift: added/changed={sum((actual-expected).values())} '
                         f'missing/changed={sum((expected-actual).values())}')
    for entry in baseline['findings']:
        print(f"REVIEW_REQUIRED {entry['result']['ruleId']}: {entry['review']}")
    print(f'MOBSF_EXACT_FINDINGS={sum(actual.values())}')
    print('RESULT=PASS_MOBSF_SOURCE_RATCHET_RELEASE_HOLD')


def main(root=ROOT):
    try:
        verify(json.loads((root / 'config/mobsf-known-findings.json').read_text()),
               json.loads((root / 'mobsfscan-results.sarif').read_text()),
               (root / 'mobsf-apk-badging.txt').read_text(), root,
               os.environ.get('MOBSF_SCAN_OUTCOME'))
    except (OSError, ValueError, TypeError, KeyError, AttributeError) as error:
        print(f'HOLD_MOBSF: {error}', file=sys.stderr)
        return 2
    return 0


if __name__ == '__main__':
    sys.exit(main())
