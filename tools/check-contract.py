#!/usr/bin/env python3
"""Static intent and permission guard. Merged release manifest still requires review."""
from pathlib import Path
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parent.parent
manifest = ET.parse(root / 'app/src/main/AndroidManifest.xml').getroot()
ns = '{http://schemas.android.com/apk/res/android}'
expected = {'android.permission.INTERNET', 'android.permission.health.READ_SLEEP', 'android.permission.health.WRITE_SLEEP'}
actual = [node.attrib[ns + 'name'] for node in manifest.findall('uses-permission')]
assert set(actual) == expected and len(actual) == len(expected)
application = manifest.find('application')
assert application.attrib[ns + 'allowBackup'] == 'false'
assert application.attrib[ns + 'usesCleartextTraffic'] == 'false'
assert application.find('service') is None and application.find('receiver') is None
source = root / 'app/src/main/java/io/github/aimindseye/sablehealthbridge'
api = (source / 'SleepApi.kt').read_text()
assert 'requestMethod = "GET"' in api and 'instanceFollowRedirects = false' in api
assert 'https://health.googleapis.com/v4/users/me/dataTypes/sleep/dataPoints:reconcile' in api
activity = (source / 'BridgeController.kt').read_text()
assert 'if (fresh != candidate.session)' in activity
writer = (source / 'ConnectSleep.kt').read_text()
assert 'confirmedDecision(preview, fresh)' in writer
assert 'store.delete(requireNotNull(fresh.ownId))' in writer
assert 'deleteRecords(SleepSessionRecord::class, listOf(id), emptyList())' in writer
assert 'deleteRecords(' in writer and 'insertRecords(' in writer
print('RESULT=PASS_SBR1_SOURCE_CONTRACT')
