#!/usr/bin/env python3
import json
import os
import pathlib
import re
import shlex
import subprocess
import time
import uuid

adb = os.environ.get('ADB', 'adb')
serial = os.environ['ANDROID_SERIAL']
package = 'net.wastu.binderclip.debug'
helper = os.environ.get('BINDERCLIP_TEST_CLIPBOARD_HELPER', '/tmp/binderclip-device-clipboard')
backup = pathlib.Path('/tmp/binderclip-ordering-clipboard.plist')
report = pathlib.Path('/tmp/binderclip-device-ordering.json')
rules = []
checks = []
rule_name = 'binderclip-ordering-' + uuid.uuid4().hex[:8]


def a(*args):
    return subprocess.run([adb, '-s', serial, *args], capture_output=True, timeout=20)


def private_file(name):
    result = a('shell', 'run-as', package, 'cat', 'files/' + name)
    if result.returncode:
        return ''
    return result.stdout.decode().strip()


def ack(value):
    command = 'printf %s ' + shlex.quote(value) + ' >files/device-ordering-ack.txt'
    result = a('shell', shlex.join(['run-as', package, 'sh', '-c', command]))
    if result.returncode:
        raise RuntimeError('Could not acknowledge device stage')


def clip(*args):
    return subprocess.run([helper, *map(str, args)], capture_output=True, timeout=10).returncode


def su(command):
    return a('shell', shlex.join(['su', '-c', command]))


def unblock():
    for program, spec in rules[:]:
        if su(program + ' -C ' + spec).returncode == 0 and su(program + ' -D ' + spec).returncode:
            raise RuntimeError('Could not remove own debug-only firewall rule')
        rules.remove((program, spec))


uid = re.search(r'^package:net\.wastu\.binderclip\.debug uid:(\d+)$',
                a('shell', 'pm', 'list', 'packages', '-U', package).stdout.decode(), re.M)
if not uid:
    raise RuntimeError('Debug app not installed')
if clip('save', backup):
    raise RuntimeError('Could not back up private Mac clipboard')
process = None
try:
    a('shell', 'run-as', package, 'rm', '-f', 'files/device-ordering-stage.txt', 'files/device-ordering-ack.txt')
    with open('/tmp/binderclip-device-ordering-instrumentation.log', 'w') as log:
        process = subprocess.Popen([adb, '-s', serial, 'shell', 'am', 'instrument', '-w', '-e',
                                   'hardwareClipboardSync', 'true', '-e', 'class',
                                   'net.wastu.binderclip.ClipboardSyncDeviceTest',
                                   package + '.test/androidx.test.runner.AndroidJUnitRunner'], stdout=log, stderr=subprocess.STDOUT)
        handled = set()
        deadline = time.monotonic() + 300
        while time.monotonic() < deadline:
            stage = private_file('device-ordering-stage.txt')
            if stage == 'failed':
                raise RuntimeError('Device ordering test failed')
            if stage == 'passed':
                break
            if stage and stage not in handled:
                values = json.loads(private_file('device-ordering-values.json'))
                if stage == 'baseline':
                    if clip('set-text', values[0]):
                        raise RuntimeError('Could not set baseline')
                    time.sleep(1)
                    ack(stage)
                elif stage.startswith('block-'):
                    spec = 'OUTPUT -m owner --uid-owner ' + uid.group(1) + ' -p tcp --dport 39422 -m comment --comment ' + rule_name + ' -j REJECT'
                    for program in ['iptables', 'ip6tables']:
                        if su(program + ' -I ' + spec).returncode:
                            raise RuntimeError('Could not isolate debug network')
                        rules.append((program, spec))
                    ack('blocked-' + stage.split('-')[-1])
                elif stage in ('mac-newer', 'mac-earlier', 'mac-recopy', 'mac-changed'):
                    index = {'mac-newer': 2, 'mac-earlier': 3, 'mac-recopy': 4, 'mac-changed': 6}[stage]
                    if clip('set-text', values[index]):
                        raise RuntimeError('Could not copy Mac fixture')
                    time.sleep(1.5)
                    if stage in ('mac-newer', 'mac-recopy'):
                        unblock()
                        ack('unblocked-' + ('1' if stage == 'mac-newer' else '3'))
                    else:
                        ack(stage)
                elif stage.startswith('unblock-'):
                    unblock()
                    ack('unblocked-' + stage.split('-')[-1])
                elif stage.startswith('verify-'):
                    round_number = int(stage.split('-')[-1])
                    if clip('match-text', values[2 if round_number == 1 else 4]):
                        time.sleep(0.1)
                        continue
                    checks.append(['Newer Mac offline copy wins', 'Newer phone offline copy catches up',
                                   'Identical Mac recopy wins a conflict', 'Identical phone recopy wins a conflict'][round_number - 1])
                    print(checks[-1], flush=True)
                    ack('verified-' + str(round_number))
                handled.add(stage)
            if process.poll() is not None:
                if private_file('device-ordering-stage.txt') != 'passed':
                    raise RuntimeError('Instrumentation stopped before convergence')
                break
            time.sleep(0.15)
        else:
            raise RuntimeError('Ordering test deadline exceeded')
        process.wait(timeout=20)
        if 'OK (1 test)' not in pathlib.Path('/tmp/binderclip-device-ordering-instrumentation.log').read_text():
            raise RuntimeError('Instrumentation did not pass')
    report.write_text(json.dumps({'passed': True, 'checks': checks}, indent=2))
finally:
    unblock()
    if process is not None and process.poll() is None:
        process.terminate()
        a('shell', 'am', 'force-stop', package)
    if clip('restore', backup):
        print('Private Mac clipboard restoration failed', flush=True)
    backup.unlink(missing_ok=True)
    a('shell', 'run-as', package, 'rm', '-f', 'files/device-ordering-stage.txt',
      'files/device-ordering-ack.txt', 'files/device-ordering-values.json')
