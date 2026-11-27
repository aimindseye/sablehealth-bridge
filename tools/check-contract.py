#!/usr/bin/env python3
"""Static intent and permission guard. Merged release manifest still requires review."""
from pathlib import Path
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parent.parent
manifest = ET.parse(root / 'app/src/main/AndroidManifest.xml').getroot()
ns = '{http://schemas.android.com/apk/res/android}'
expected = {'android.permission.INTERNET', 'android.permission.health.READ_SLEEP',
            'android.permission.health.WRITE_SLEEP', 'android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND'}
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
probe = (source / 'BackgroundProbeWorker.kt').read_text()
assert 'ConnectSleep(applicationContext).inspect(session)' in probe
for name in ('BackgroundProbeWorker.kt', 'BackgroundProbeActivity.kt', 'AndroidProbePlatform.kt', 'ProbePolicy.kt'):
    text = (source / name).read_text()
    assert '.apply(' not in text and 'insertRecords(' not in text and 'deleteRecords(' not in text
assert 'hasResolution()' in probe and 'pendingIntent' not in probe
assert 'setInitialDelay(' in (source / 'BackgroundProbeActivity.kt').read_text()
auto = (source / 'AutomaticSyncWorker.kt').read_text()
engine = (source / 'AutomaticSyncEngine.kt').read_text()
scheduler = (source / 'SyncScheduler.kt').read_text()
journal = (source / 'SyncJournal.kt').read_text()
assert 'insertAutomatically(session, decision)' in auto
assert 'ledger.reserve(session)' in auto and 'guard(consent)' in auto
assert 'silentAccess(ports.authorize())' in engine and 'consent.binding' in engine
assert 'enqueueUniquePeriodicWork' in scheduler and 'enqueueUniqueWork' in scheduler
assert 'SYNC_GENERATION to consent.generation' in scheduler
assert 'UNRESOLVED_ATTEMPT' in journal and 'editor.commit()' in journal
for text in (auto, engine, scheduler):
    assert 'deleteRecords(' not in text and 'pendingIntent' not in text
assert 'fresh.action != Action.INSERT' in writer
print('RESULT=PASS_SBR2A_SOURCE_CONTRACT')
