import pathlib,subprocess,time,json,os,sys,shlex
adb=os.environ.get('ADB', 'adb')
phone=os.environ['ANDROID_SERIAL']
helper=os.environ.get('BINDERCLIP_TEST_CLIPBOARD_HELPER', '/tmp/binderclip-device-clipboard')
backup=pathlib.Path.home()/'Library/Application Support/net.wastu.binderclip.debug.mb/device-test-backup/clipboard.plist'
report=pathlib.Path('/tmp/binderclip-device-'+('manual' if '--manual-share' in sys.argv else 'cache' if '--image-cache' in sys.argv else 'promotion' if '--promotion' in sys.argv else 'bluetooth' if '--bluetooth' in sys.argv else 'network')+'.json')
def a(*args): return subprocess.run([adb,'-s',phone,*args],capture_output=True,timeout=20)
def clip(*args): return subprocess.run([helper,*map(str,args)],capture_output=True,timeout=10).returncode
def readstage(): return a('shell','run-as','net.wastu.binderclip.debug','cat','files/device-test-stage.txt').stdout.decode().strip()
def getpng(name):
    dest=pathlib.Path('/tmp/'+name)
    r=a('exec-out','run-as','net.wastu.binderclip.debug','cat','files/'+name)
    if r.returncode: raise RuntimeError('Cannot read test PNG')
    dest.write_bytes(r.stdout)
    return dest
if '--promotion' in sys.argv or '--doze' in sys.argv:
    raise RuntimeError('Radio/Doze mutations affect production; use dedicated acceptance devices')
manual='--manual-share' in sys.argv
cache='--image-cache' in sys.argv
promotion='--promotion' in sys.argv
bluetooth='--bluetooth' in sys.argv or promotion
large='--large-text' in sys.argv
cacheSeconds=next((int(arg.split('=',1)[1]) for arg in sys.argv if arg.startswith('--cache-seconds=')),70)
if not 70 <= cacheSeconds <= 600: raise ValueError('Cache observation must be 70–600 seconds')
suffix=' BLE' if bluetooth else ''
checks=[]
backup.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
if clip('save',backup): raise RuntimeError('Could not back up the Mac clipboard')
try:
    a('shell','run-as','net.wastu.binderclip.debug','rm','-f','files/device-test-stage.txt','files/device-test-ack.txt','files/device-test-selected-transport.txt')
    with open('/tmp/binderclip-device-instrumentation.log','w') as log:
        process=subprocess.Popen([adb,'-s',phone,'shell','am','instrument','-w','-e','cacheObserveSeconds',str(cacheSeconds),'-e','hardwareRoundTrip','true','-e','hardwareManualShare','true' if manual else 'false','-e','hardwareImageCache','true' if cache else 'false','-e','largeText','true' if large else 'false','-e','promotion','true' if promotion else 'false','-e','expectedTransport','BLUETOOTH' if bluetooth else 'MESH' if '--mesh' in sys.argv else 'LAN' if '--lan' in sys.argv else 'NETWORK','-e','class','net.wastu.binderclip.ManualShareDeviceTest' if manual else 'net.wastu.binderclip.ImageCacheDeviceTest' if cache else 'net.wastu.binderclip.TransportDeviceTest','net.wastu.binderclip.debug.test/androidx.test.runner.AndroidJUnitRunner'],stdout=log,stderr=subprocess.STDOUT)
        handled=set()
        deadline=time.monotonic()+330+(cacheSeconds if cache else 0)
        while time.monotonic()<deadline:
            stage=readstage()
            if stage=='manual-android-text' and stage not in handled:
                if clip('match-text','BinderClip manual share Android to Mac')==0:
                    checks.append('Android share sheet text delivered');print(checks[-1],flush=True)
                    if clip('service-text','BinderClip Mac Services to Android'):raise RuntimeError('Mac Services invocation failed')
                    handled.add(stage)
            elif stage=='manual-copied-url' and stage not in handled:
                if clip('match-text','https://example.com/?binderclip-test=copied')==0:
                    checks.append('Mac Services text delivered; copied URL remains clipboard text');print(checks[-1],flush=True)
                    if clip('service-text','https://example.com/?binderclip-test=shared'):raise RuntimeError('Mac Services URL invocation failed')
                    handled.add(stage)
            elif stage=='passed' and manual:
                checks.append('Shared URL delivered as actionable Browser Links notification');print(checks[-1],flush=True);break
            elif stage in ('cache-large-image','cache-mutated-image') and stage not in handled:
                filename='device-test-cache-large.png' if stage=='cache-large-image' else 'device-test-cache-changed.png'
                if clip('match-image',getpng(filename))==0:
                    r=a('shell',shlex.join(['run-as','net.wastu.binderclip.debug','sh','-c','printf %s '+stage+' >files/device-test-ack.txt']))
                    if r.returncode: raise RuntimeError('Cannot acknowledge cache image')
                    checks.append(stage+' delivered byte-for-byte');print(checks[-1],flush=True);handled.add(stage)
            elif stage=='android-text' and stage not in handled:
                if clip('match-text-file',getpng('device-test-android.txt'))==0:
                    checks.append('Android→Mac encrypted text delivered'); print(checks[-1],flush=True)
                    clip('set-text-file',getpng('device-test-mac.txt'));handled.add(stage)
            elif stage=='deferred-image' and stage not in handled:
                if clip('match-image',getpng('device-test-android.png'))==0:
                    raise RuntimeError('Image was delivered before WebSocket returned')
                checks.append('Image deferred while Bluetooth-only');print(checks[-1],flush=True)
                spec='OUTPUT -m owner --uid-owner '+os.environ['BINDERCLIP_TEST_UID']+' -p tcp --dport 39422 -j REJECT'
                for program in ['iptables','ip6tables']:
                    r=a('shell',shlex.join(['su','-c',program+' -D '+spec]))
                    if r.returncode: raise RuntimeError('Could not release '+program+' test rule')
                # Restore a real Wi-Fi path to exercise ConnectivityManager.onAvailable.
                cycle="nohup sh -c 'svc wifi disable; sleep 2; svc wifi enable' >/data/local/tmp/binderclip-wifi-cycle.log 2>&1 &"
                r=a('shell',shlex.join(['su','-c',cycle]))
                if r.returncode: raise RuntimeError('Could not start reversible Wi-Fi cycle')
                handled.add(stage)
            elif stage in ('android-image','promoted-image') and stage not in handled:
                if not checks or 'Mac→Android encrypted text delivered' not in checks:
                    checks.append('Mac→Android encrypted text delivered');print(checks[-1],flush=True)
                android=getpng('device-test-android.png')
                if clip('match-image',android)==0:
                    checks.append('Android→Mac encrypted PNG delivered byte-for-byte');print(checks[-1],flush=True)
                    clip('set-image',getpng('device-test-mac.png'));handled.add(stage)
            elif stage=='echo-prior-text' and stage not in handled:
                checks.append('Mac→Android encrypted PNG delivered byte-for-byte');print(checks[-1],flush=True)
                clip('set-text-file',getpng('device-test-android.txt'));handled.add(stage)
            elif stage=='passed' and cache:
                metrics=a('shell','run-as','net.wastu.binderclip.debug','cat','files/device-test-cache.json').stdout.decode()
                checks.append('Unchanged image cache: '+metrics);print(checks[-1],flush=True);break
            elif stage=='passed':
                checks.append('Mac→Android previous text delivered after image' if 'echo-prior-text' in handled else 'Mac→Android encrypted PNG delivered byte-for-byte' if (promotion or not bluetooth) else 'Mac→Android encrypted text delivered');print(checks[-1],flush=True);break
            elif stage=='failed':
                process.wait(timeout=20)
                raise RuntimeError('Android instrumentation failed; see /tmp/binderclip-device-instrumentation.log')
            if process.poll() is not None:
                if stage!='passed': raise RuntimeError('Instrumentation finished before all stages')
                break
            time.sleep(2 if cache and "cache-large-image" in handled else .3)
        else: raise RuntimeError('Coordinator timed out')
        process.wait(timeout=20)
        if 'OK (1 test)' not in pathlib.Path('/tmp/binderclip-device-instrumentation.log').read_text():
            raise RuntimeError('Instrumentation did not report a passing test')
        if promotion: checks.append('Automatic Wi-Fi/mesh promotion and Bluetooth release after grace period')
        report.write_text(json.dumps({'largeText':large,'promotion':promotion,'transport':'BLUETOOTH' if bluetooth else 'NETWORK','selectedTransport':a('shell','run-as','net.wastu.binderclip.debug','cat','files/device-test-selected-transport.txt').stdout.decode().strip(),'checks':checks,'instrumentationExit':process.returncode},indent=2))
finally:
    if 'process' in locals() and process.poll() is None:
        process.terminate()
    if clip('restore',backup): print('Warning: Mac clipboard restoration failed',flush=True)
