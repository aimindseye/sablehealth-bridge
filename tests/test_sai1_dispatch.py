"""Run the actual remote dispatch with a fake client, without SSH or signing."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / 'tools/build-release.sh'


class RemoteReleaseDispatch(unittest.TestCase):
    def test_remote_dispatch_and_missing_client(self):
        with tempfile.TemporaryDirectory() as directory:
            home = Path(directory)
            env = {**os.environ, 'HOME': directory}
            def invoke(*args):
                return subprocess.run(['bash', str(SCRIPT), '--remote', *args],
                                      cwd=ROOT, env=env, capture_output=True,
                                      text=True, timeout=10, check=False)
            self.assertEqual(invoke().returncode, 2)
            self.assertIn('HOLD_SAI1_FINAL_CLIENT_NOT_INSTALLED', invoke().stdout)
            client = home / '.local/share/sai1/final/final.py'
            client.parent.mkdir(parents=True)
            client.write_text('import sys\nprint(repr(sys.argv[1:]))\nsys.exit(23)\n')
            result = invoke('--run', '0123456789ab', '--retrieve')
            self.assertEqual(result.returncode, 23)
            self.assertIn("['release', '--run', '0123456789ab', '--retrieve']", result.stdout)
            client.unlink()
            client.symlink_to('/dev/null')
            self.assertEqual(invoke().returncode, 2)


if __name__ == '__main__':
    unittest.main()
