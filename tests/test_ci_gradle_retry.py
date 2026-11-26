"""Exercise bounded retry behavior without downloading dependencies."""
import contextlib
import io
import sys
import unittest
from unittest.mock import patch

from tools import ci_gradle_retry as gate

LIMIT = """FAILURE: Build failed with an exception.
Execution failed for task ':app:testDebugUnitTest'.
> Could not resolve all files for configuration ':app:debugUnitTestRuntimeClasspath'.
> Could not download fixture.jar
> Could not GET 'https://repo.maven.apache.org/maven2/example/fixture.jar'. Received status code 429 from server: Too Many Requests
"""


class RetryTests(unittest.TestCase):
    def exercise(self, outcomes):
        calls, delays = [], []
        pending = iter(outcomes)

        def run(command):
            calls.append(command)
            return next(pending)

        with contextlib.redirect_stdout(io.StringIO()):
            result = gate.run_gate(run=run, pause=delays.append)
        self.assertTrue(all(command == gate.COMMAND for command in calls))
        return result, len(calls), delays

    def test_success_and_single_retry(self):
        self.assertEqual(self.exercise([(0, '')]), (0, 1, []))
        self.assertEqual(self.exercise([(1, LIMIT), (0, '')]), (0, 2, [30]))

    def test_bounded_failure_and_no_retry_for_real_violation(self):
        self.assertEqual(self.exercise([(1, LIMIT)] * 3), (1, 3, [30, 90]))
        self.assertEqual(self.exercise([(2, 'ktlint FAILED')]), (2, 1, []))
        self.assertEqual(self.exercise([(1, LIMIT), (3, 'detekt FAILED')]), (3, 2, [30]))

    def test_narrow_failure_classification(self):
        self.assertTrue(gate.retryable(LIMIT))
        for old, new in (
            ('FAILURE: Build failed with an exception.', ''),
            ('Execution failed for task ', 'Error in task '),
            ('Could not resolve all files for configuration ', 'Cannot resolve '),
            ('Could not download ', 'Other problem '),
            ('Could not GET ', 'Request '),
            ('repo.maven.apache.org', 'example.invalid'),
            ('429', '403'),
        ):
            with self.subTest(old=old):
                self.assertFalse(gate.retryable(LIMIT.replace(old, new)))
        self.assertFalse(gate.retryable(LIMIT + LIMIT))
        self.assertFalse(gate.retryable(LIMIT + "Could not GET 'https://example.invalid/a'\n"))

    def test_subprocess_stream_and_exit(self):
        with contextlib.redirect_stdout(io.StringIO()) as output:
            code, log = gate.run_once((sys.executable, '-c', 'print("fixture"); raise SystemExit(7)'))
        self.assertEqual((code, log, output.getvalue()), (7, 'fixture\n', 'fixture\n'))

    def test_main_and_full_command(self):
        with patch.object(gate, 'run_gate', return_value=7):
            self.assertEqual(gate.main(), 7)
        self.assertEqual(gate.COMMAND[5:], (
            ':core:test', ':core:jacocoTestReport', ':app:createDebugUnitTestCoverageReport',
            ':app:assembleDebug', ':app:assembleRelease', ':app:lintDebug', ':app:lintRelease',
            'detekt', 'ktlintCheck',
        ))


if __name__ == '__main__':
    unittest.main()
