"""Retry the complete Android CI gate only on a confirmed Maven Central 429."""
import re
import subprocess
import time

COMMAND = (
    'gradle', '--no-daemon', '--console=plain', '--no-build-cache', '--rerun-tasks',
    ':core:test', ':core:jacocoTestReport', ':app:createDebugUnitTestCoverageReport',
    ':app:assembleDebug', ':app:assembleRelease', ':app:lintDebug', ':app:lintRelease',
    'detekt', 'ktlintCheck',
)
DELAYS = (30, 90)
RATE_LIMIT = re.compile(
    r"Could not GET 'https://repo\.maven\.apache\.org/maven2/[^'\r\n]+'\. "
    r'Received status code 429 from server: Too Many Requests'
)


def retryable(log):
    requests = [line for line in log.splitlines() if 'Could not GET ' in line]
    return (
        log.count('FAILURE: Build failed with an exception.') == 1
        and log.count('Execution failed for task ') == 1
        and 'Could not resolve all files for configuration ' in log
        and 'Could not download ' in log
        and bool(requests)
        and all(RATE_LIMIT.search(line) for line in requests)
    )


def run_once(command):
    lines = []
    with subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                          text=True, encoding='utf-8', errors='replace') as process:
        for line in process.stdout:
            print(line, end='', flush=True)
            lines.append(line)
        return process.wait(), ''.join(lines)


def run_gate(run=run_once, pause=time.sleep):
    for attempt in range(len(DELAYS) + 1):
        code, log = run(COMMAND)
        if code == 0:
            return 0
        if attempt == len(DELAYS) or not retryable(log):
            return code
        delay = DELAYS[attempt]
        print(f'CI_MAVEN_429_RETRY attempt={attempt + 2} delay={delay}s', flush=True)
        pause(delay)
    raise AssertionError('unreachable retry state')


def main():
    return run_gate()


if __name__ == '__main__':
    raise SystemExit(main())
