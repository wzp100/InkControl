"""显式授权后运行的实机回归；使用正式应用服务，不修改配置文件。"""
import argparse
import json
from pathlib import Path
import time
from device_session import Session


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--session', required=True, type=Path)
    parser.add_argument('--prefix', required=True)
    parser.add_argument('--group', choices=('native-hot', 'motion', 'lock', 'desktop'), required=True)
    args = parser.parse_args()
    meta = json.loads(args.session.read_text(encoding='utf-8'))
    session = Session(meta['serial'], meta['output'])
    session.adb('shell','input','keyevent','224')
    session.adb('shell','wm','dismiss-keyguard')
    rows = []

    def change(id, enabled, value=None):
        name = '%s-%02d-%s' % (args.prefix, len(rows), id)
        kwargs = dict(id=id, enabled=str(enabled).lower())
        if value is not None: kwargs['value'] = value
        result = session.probe(name, 'change', **kwargs)
        row = {'id': id, 'enabled': enabled, 'value': value, 'status': result['statuses'][id]}
        rows.append(row)
        (session.output/(args.prefix+'-summary.json')).write_text(json.dumps(rows, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
        print(json.dumps(row, ensure_ascii=False), flush=True)
        return result

    if args.group == 'native-hot':
        for id, prop, enabled, disabled in (
            ('display.sampling','debug.inkcontrol.sampling','1','0'),
            ('lock.dither','debug.eink.lock.dither','nearest','stock'),
            ('experiment.cache','debug.inkcontrol.cache','1','0'),
            ('experiment.video','debug.eink.video.dither','blue','stock')):
            try:
                change(id, True)
                actual = session.record(args.prefix+'-'+id+'-on', 'getprop '+prop)
                assert actual == enabled, (id, actual)
            finally:
                change(id, False)
                actual = session.record(args.prefix+'-'+id+'-off', 'getprop '+prop)
                assert actual == disabled, (id, actual)
        key = 'dynamic_refresh_mode'
        original = session.record(args.prefix+'-dynamic-before', 'settings get global '+key)
        try:
            session.root('settings put global '+key+' 1')
            change('display.dynamic', True)
            assert session.record(args.prefix+'-dynamic-on', 'settings get global '+key) == '0'
            change('display.dynamic', False)
            assert session.record(args.prefix+'-dynamic-restored', 'settings get global '+key) == '1'
            change('display.dynamic', True)
            session.root('settings put global '+key+' 1')
            state = session.probe(args.prefix+'-dynamic-independent-edit')
            assert '独立修改' in state['statuses']['display.dynamic'], state['statuses']['display.dynamic']
            change('display.dynamic', False)
            assert session.record(args.prefix+'-dynamic-kept', 'settings get global '+key) == '1'
        finally:
            session.root(('settings delete global '+key) if original == 'null' else 'settings put global '+key+' '+original)
            assert session.record(args.prefix+'-dynamic-final', 'settings get global '+key) == original
    elif args.group == 'motion':
        try:
            change('motion.master', True)
            for id in ('app_open','app_close','in_app_forward','in_app_back','dialog','gc_clear','lock','unlock','steps','hold','origin','direction','spread'):
                change('motion.'+id, True)
            # 关闭总开关后场景应显示依赖未满足；重新打开恢复已生效。
            r = change('motion.master', False)
            assert '依赖' in r['statuses']['motion.app_open'], r['statuses']['motion.app_open']
            r = change('motion.master', True)
            assert r['statuses']['motion.app_open'] == '已生效'
            session.adb('shell','input','keyevent','224');session.adb('shell','wm','dismiss-keyguard')
            session.root('am start -n com.einkmotion/.DemoActivity --ei page 1')
            time.sleep(2)
            session.tap_text('测试对话框', args.prefix+'-dialog')
            time.sleep(1)
            (session.output/(args.prefix+'-dialog.png')).write_bytes(session.adb('exec-out','screencap','-p').stdout)
            session.adb('shell','input','keyevent','4');time.sleep(1)
            session.adb('shell','input','keyevent','4');time.sleep(1)
            session.adb('shell','input','keyevent','3')
            session.adb('shell','input','keyevent','223');time.sleep(2)
            session.adb('shell','input','keyevent','224');time.sleep(2)
            session.adb('shell','wm','dismiss-keyguard')
            session.record(args.prefix+'-events', 'getprop debug.eink.motion.req; getprop debug.eink.motion.gc; logcat -d -s EinkGPU | tail -n 100')
        finally:
            for id in ('app_open','app_close','in_app_forward','in_app_back','dialog','gc_clear','lock','unlock','steps','hold','origin','direction','spread'):
                change('motion.'+id, False)
            change('motion.master', False)
    elif args.group == 'lock':
        try:
            for value in ('8','5','6','7'):
                change('lock.style', True, value)
                assert session.record(args.prefix+'-style-'+value, 'settings get global eink_keyguard_style') == value
                session.adb('shell','input','keyevent','223'); time.sleep(1)
                session.adb('shell','input','keyevent','224'); time.sleep(2)
                (session.output/(args.prefix+'-style-'+value+'.png')).write_bytes(session.adb('exec-out','screencap','-p').stdout)
                session.adb('shell','wm','dismiss-keyguard')
            change('lock.weather', True)
            r = change('lock.style', False)
            assert '依赖' in r['statuses']['lock.weather']
            assert session.record(args.prefix+'-style-restored', 'settings get global eink_keyguard_style') == '0'
            change('lock.weather', False)
            change('lock.lyric', True)
            change('lock.lyricmode', True)
            r = change('lock.lyric', False)
            assert '依赖' in r['statuses']['lock.lyricmode']
            change('lock.lyricmode', False)
        finally:
            change('lock.style', False)
            session.adb('shell','input','keyevent','224')
            session.adb('shell','wm','dismiss-keyguard')
    elif args.group == 'desktop':
        try:
            session.adb('shell','input','keyevent','3')
            for value in ('glass','paper','ink'):
                change('desktop.theme', True, value)
                if value == 'glass':
                    for id in ('icons','font','wallpaper','widget'):
                        change('desktop.'+id, True)
                for widget in ('clock','weather','music'):
                    change('desktop.widget', True, widget)
                    session.adb('shell','input','keyevent','3'); time.sleep(2)
                    actual=json.loads(session.record(args.prefix+'-'+value+'-'+widget, 'cat /data/user/0/com.hmct.vision/files/einkthemes-state.json'))
                    assert actual['theme']==value and actual['kind']==widget and actual['enabled'], actual
                    assert actual['weather_available'] and actual['media_available'], actual
                    shot = session.adb('exec-out','screencap','-p').stdout
                    (session.output/(args.prefix+'-'+value+'-'+widget+'.png')).write_bytes(shot)
            for id in ('icons','font','wallpaper','widget'):
                change('desktop.'+id, False)
                session.adb('shell','input','keyevent','3'); time.sleep(1)
                (session.output/(args.prefix+'-'+id+'-off.png')).write_bytes(session.adb('exec-out','screencap','-p').stdout)
            change('desktop.icons', True)
            r = change('desktop.theme', False)
            assert '依赖' in r['statuses']['desktop.icons']
            change('desktop.icons', False)
        finally:
            change('desktop.theme', False)
            session.adb('shell','input','keyevent','3'); time.sleep(1)
            actual=json.loads(session.record(args.prefix+'-restored', 'cat /data/user/0/com.hmct.vision/files/einkthemes-state.json'))
            assert not actual['enabled'] and actual['styled_views']==0,actual
    assert all(row['status'] in ('已生效','关闭') for row in rows), rows


if __name__ == '__main__': main()
