#!/usr/bin/env python3
"""Validate built identities and entry points, independently of source-level unit fixtures."""
import argparse, json, pathlib, plistlib, xml.etree.ElementTree as E

parser = argparse.ArgumentParser()
parser.add_argument('--android', type=pathlib.Path, help='Android app/build directory')
parser.add_argument('--mac', type=pathlib.Path, help='Installed debug app bundle')
args = parser.parse_args()
if not args.android and not args.mac: parser.error('Choose an artifact to verify')
if args.android:
    root = args.android
    app = json.loads((root/'outputs/apk/debug/output-metadata.json').read_text())
    tests = json.loads((root/'outputs/apk/androidTest/debug/output-metadata.json').read_text())
    assert app['applicationId'] == 'net.wastu.binderclip.debug'
    assert tests['applicationId'] == 'net.wastu.binderclip.debug.test'
    test_manifests = [E.parse(p).getroot() for p in (root/'intermediates').rglob('AndroidManifest.xml')
                      if 'androidtest' in str(p).lower()]
    test_manifests = [m for m in test_manifests if m.get('package') == tests['applicationId']]
    assert test_manifests, 'Instrumentation manifest missing'
    ns = '{http://schemas.android.com/apk/res/android}'
    assert all(m.find('instrumentation').get(ns+'targetPackage') == app['applicationId'] for m in test_manifests)
    manifests = list((root/'intermediates/merged_manifests/debug').rglob('AndroidManifest.xml'))
    assert manifests, 'Merged debug manifest missing'
    manifest = E.parse(manifests[0]).getroot()
    ns = '{http://schemas.android.com/apk/res/android}'
    assert manifest.get('package') == app['applicationId']
    application = manifest.find('application')
    assert any(p.get(ns+'authorities') == app['applicationId']+'.images' for p in application.findall('provider'))
    schemes = [node.get(ns+'scheme') for node in application.findall('.//data') if node.get(ns+'scheme')]
    assert 'binderclip-debug' in schemes and 'binderclip' not in schemes
    receiver = next(n for n in application.findall('receiver') if n.get(ns+'name') == 'net.wastu.binderclip.BootReceiver')
    assert receiver.get(ns+'enabled') == 'false'
    permissions = {n.get(ns+'name') for n in manifest.findall('uses-permission')}
    assert 'android.permission.READ_CLIPBOARD_IN_BACKGROUND' not in permissions
    assert 'android.permission.RECEIVE_BOOT_COMPLETED' not in permissions
    print('Android debug/test identities, provider, pairing scheme, and disabled boot/root integration verified')
if args.mac:
    with (args.mac/'Contents/Info.plist').open('rb') as f: info = plistlib.load(f)
    assert info['CFBundleIdentifier'] == 'net.wastu.binderclip.debug.mb'
    assert info['BinderClipEnvironment'] == 'debug'
    assert info['NSBonjourServices'] == ['_binderclip-dbg._tcp']
    assert info['SUEnableAutomaticChecks'] is False and 'SUFeedURL' not in info
    assert all(service['NSPortName'] == 'BinderClip Debug' for service in info['NSServices'])
    assert all(service['NSMenuItem']['default'] == 'Send to Phones via BinderClip Debug' for service in info['NSServices'])
    print('Mac debug bundle, discovery, Services, and updater configuration verified')
