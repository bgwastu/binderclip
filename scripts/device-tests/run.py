#!/usr/bin/env python3
"""Run debug-only physical transfers; optional firewall rules target only debug's UID/port."""
import ipaddress, os, pathlib, re, shlex, subprocess, sys, urllib.parse

adb = os.environ.get('ADB', 'adb')
serial = os.environ['ANDROID_SERIAL']
package = 'net.wastu.binderclip.debug'
env = os.environ.copy()
env['BINDERCLIP_TEST_SERVICE_NAME'] = 'Send to Phones via BinderClip Debug'

def a(*args):
    return subprocess.run([adb, '-s', serial, *args], capture_output=True, text=True, timeout=30)

def su(command):
    return a('shell', shlex.join(['su', '-c', command]))

if '--promotion' in sys.argv or '--doze' in sys.argv:
    raise SystemExit('Shared radio/Doze mutations require dedicated acceptance devices')
rules = []
try:
    route_flags = [flag for flag in ['--bluetooth', '--mesh', '--lan'] if flag in sys.argv]
    if len(route_flags) > 1:
        raise RuntimeError('Choose only one transport to isolate')
    if route_flags:
        match = re.search(r'^package:net\.wastu\.binderclip\.debug uid:(\d+)$', a('shell', 'pm', 'list', 'packages', '-U', package).stdout, re.M)
        if not match:
            raise RuntimeError('Debug app not installed; refusing to select another UID')
        destinations = ['']
        programs = ['iptables', 'ip6tables']
        if '--bluetooth' not in sys.argv:
            invitation = pathlib.Path.home()/'Library/Application Support/net.wastu.binderclip.debug.mb/debug-invite.txt'
            url = urllib.parse.urlsplit(invitation.read_text().strip())
            if url.scheme != 'binderclip-debug' or url.netloc != 'pair':
                raise RuntimeError('Refusing to use another environment\'s invitation')
            endpoints = urllib.parse.parse_qs(url.query).get('endpoints', [''])[0].split(',')
            addresses = [str(ipaddress.IPv4Address(endpoint.rsplit(':', 1)[0])) for endpoint in endpoints if endpoint]
            block_mesh = '--lan' in sys.argv
            destinations = [' -d '+ip for ip in set(addresses) if ip.startswith('100.') == block_mesh]
            programs = ['iptables']
        for destination in destinations:
            spec = 'OUTPUT -m owner --uid-owner '+match.group(1)+destination+' -p tcp --dport 39422 -j REJECT'
            for program in programs:
                if su(program+' -I '+spec).returncode:
                    raise RuntimeError('Could not install debug-only test rule')
                rules.append((program, spec))
    subprocess.run([sys.executable, str(pathlib.Path(__file__).with_name('coordinator.py')), *sys.argv[1:]], env=env, check=True)
finally:
    for program, spec in rules:
        if su(program+' -C '+spec).returncode == 0:
            if su(program+' -D '+spec).returncode:
                raise RuntimeError('Could not clean up own debug-only firewall rule')
