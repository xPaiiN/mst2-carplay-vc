#!/usr/bin/env python3
# Made by xPaiiN <3
# github.com/xPaiiN/mst2-carplay-vc
# v2.0
"""Remove private data from a "Collect Logs" folder before you post it.

    python cpvc_clean_logs.py <folder>

Writes <folder>-clean/ and <folder>-clean.zip next to the folder. The folder itself is not changed.

Removed: VIN, serial numbers, MAC, Bluetooth and IPv6 addresses, phone numbers, phone, contact and carrier
names, GPS sentences and coordinates, navigation and position lines of the system log, destination, call and
contact entries of the iAP2 trace, raw iAP2 payload bytes (the authentication certificate among them).
Kept: firmware, unit type, patch logs, song info, the iAP2 message flow, the cover transfer, local IPv4 addresses.

Runs on Windows, macOS and Linux with Python 3.6 or newer, nothing to install.
"""
import argparse
import os
import re
import shutil
import sys
import zipfile

BINARY_EXT = ('.jar', '.jpg', '.jpeg', '.png', '.zip')
BINARY_NAMES = ('cpvc-cover-elf',)

VIN = re.compile(r'(?<![A-Za-z0-9])[A-HJ-NPR-Z0-9]{17}(?![A-Za-z0-9])')
MAC = re.compile(r'(?<![0-9A-Fa-f:-])(?:[0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}(?![0-9A-Fa-f:-])')
HEX48 = re.compile(r'(?i)(?<![0-9a-z])0x[0-9a-f]{12}(?![0-9a-f])')
IPV6 = re.compile(r'(?i)(?<![0-9a-z:])(?:(?:[0-9a-f]{1,4}:){1,6}:(?:[0-9a-f]{1,4}(?::[0-9a-f]{1,4})*)?|::(?:[0-9a-f]{1,4}:){0,5}[0-9a-f]{1,4}|(?:[0-9a-f]{1,4}:){7}[0-9a-f]{1,4})(?:%\w+)?(?![0-9a-z:])')
PHONE = re.compile(r'(?<![\w+])\+[1-9]\d{6,14}(?!\d)')
NMEA = re.compile(r'\$(?:G[PNLAB]|PASCD|PAGC)[A-Z]{0,3},[^\s\]\)]*')
SERIAL_KV = re.compile(r'(?i)\b(sernum|serial(?:number|no|_?num)?|seriennummer)(\s*[=:]\s*)([^\s,;\]]+)')
VIN_KV = re.compile(r'(?i)\b(vin|fin)(\s*[=:]\s*)([A-Za-z0-9]{11,17})')
SPLIT_MAC = re.compile(r'(?i)(mac[\w ]*?address[ .:]*)([0-9a-f]{6})[ :-]?([0-9a-f]{6})')
SECRET_KV = re.compile(r'(?i)\b(ssid|passphrase|password|psk|wpa_?key|imei|imsi|iccid|udid)(\s*[=:]\s*)(\S+)')
COORD_KV = re.compile(r'(?i)\b(lat(?:itude)?|lon(?:gitude)?|lng|la|lo|alt(?:itude)?|heading|speed)(\s*[=:]\s*)(-?\d+(?:[.,]\d+)?)')
DECIMAL = re.compile(r'(?<![\d.])(?<!\d\d:)-?\d{1,3}\.\d{4,}(?![\d.])')
BIGINT = re.compile(r'(?<![\d.])-?\d{6,10}(?![\d.])')
FROM_NAME = re.compile(r'From=(.+?)(?= [A-Z]=0x| D=| A=|$)')

# iAP2 trace, decoded view: "        (n=  24)[    0] DeviceName: iPhone"
PARAM = re.compile(r'^(?P<head>(?:TID[0-9A-Fa-f]+)?\s*\(n=\s*\d+\)\[\s*[0-9A-Fa-f]+\]\s*)(?P<key>[A-Za-z0-9_]+)(?P<sep>:\s?)(?P<val>.*)$')
KEEP_PREFIX = ('Playback', 'MediaItem')
RISKY_KEY = re.compile(r'(?i)id|identifier|address|serial|lat|lon|number|mac|uuid|key|token|cert|signature|challenge|response|name|label|nmea|position|location|email|phone|call|contact|carrier|ssid|text|message|subject|sender')
HEX_ONLY = re.compile(r'^(?:[0-9A-Fa-f]{2}(?: |$))+$')
NAME_KEYS = re.compile(r'(?i)^(name|devicename|medialibraryname|displayname|carriername|label|remoteid|addressbookid|nickname|firstname|lastname)$')
GENERIC = ('phone', 'iphone', 'ipad', 'mobile', 'home', 'work', 'main', 'other', 'car', 'music', 'apple music', 'none', 'null', 'unknown', 'yes', 'no')
DATA_HEX = re.compile(r'data=((?:[0-9A-Fa-f]{2} ?){4,})')

# System log sources that report position, routes and destinations; their message text is dropped
POS_SOURCE = re.compile(r'(?i)(^|\s)(pos|gps|navi|nav|map|tmc):|imx6\.navi|tsd\.(mibstd2\.)?(nav|psd|map)|geoposition|poiwarn|dsimapview|guidance|navlocresolver')


class Cleaner:
    def __init__(self):
        self.values = {}          # exact private strings found anywhere -> placeholder
        self.positions = []       # (lat, lon) in degrees
        self.count = {}

    def add(self, kind, value):
        value = value.strip()
        if len(value) < 3 or value.lower() in GENERIC:
            return
        self.values.setdefault(value, '<%s>' % kind)

    def bump(self, kind, n=1):
        if n:
            self.count[kind] = self.count.get(kind, 0) + n

    def harvest(self, text):
        for m in VIN_KV.finditer(text):
            self.add('vin', m.group(3))
        for m in VIN.finditer(text):
            v = m.group(0)
            if re.search(r'[A-Z]', v) and re.search(r'\d', v) and not re.fullmatch(r'[0-9A-F]+', v):
                self.add('vin', v)
        for m in SERIAL_KV.finditer(text):
            self.add('serial', m.group(3))
        for m in MAC.finditer(text):
            v = m.group(0)
            if v.replace('-', ':').lower() not in ('00:00:00:00:00:00', 'ff:ff:ff:ff:ff:ff'):
                self.add('address', v)
        for m in HEX48.finditer(text):
            self.add('address', m.group(0))
        for m in SPLIT_MAC.finditer(text):
            self.add('address', m.group(2))
            self.add('address', m.group(3))
        for m in FROM_NAME.finditer(text):
            self.add('phone-name', m.group(1))
        for line in text.splitlines():
            p = PARAM.match(line)
            if p and NAME_KEYS.match(p.group('key')) and p.group('key').lower() != 'label' and not HEX_ONLY.match(p.group('val').strip() or '00'):
                self.add('name', p.group('val'))
        for m in re.finditer(r'\$G[PN](?:RMC|GGA|GLL),[^,]*,(?:[AV],)?(\d{2})(\d{2}\.\d+),([NS]),(\d{3})(\d{2}\.\d+),([EW])', text):
            lat = int(m.group(1)) + float(m.group(2)) / 60
            lon = int(m.group(4)) + float(m.group(5)) / 60
            self.positions.append((-lat if m.group(3) == 'S' else lat, -lon if m.group(6) == 'W' else lon))
        for m in re.finditer(r'(?i)latitude\s*[:=]\s*(-?\d{6,10})\D[^\n]{0,80}?longitude\s*[:=]\s*(-?\d{5,10})', text):
            self._add_scaled(m.group(1), m.group(2))
        for m in re.finditer(r'(?i)\bla:\s*(-?\d{6,10}),\s*lo:\s*(-?\d{5,10})', text):
            self._add_scaled(m.group(1), m.group(2))

    def _add_scaled(self, a, b):
        for scale in (1e5, 1e6, 1e7):
            lat, lon = int(a) / scale, int(b) / scale
            if -90 <= lat <= 90 and -180 <= lon <= 180 and abs(lat) > 1:
                self.positions.append((lat, lon))
                return

    def finish_harvest(self, own_text=''):
        # Names from the unit's own config (accessory name, feature names) are not private
        for v in [v for v, ph in self.values.items() if ph in ('<name>', '<phone-name>')
                  and re.search(r'(?<!\w)' + re.escape(v) + r'(?!\w)', own_text)]:
            del self.values[v]
        # Spellings of the same value: MAC with ":" or "-", upper and lower case, plain hex
        extra = {}
        for v, ph in self.values.items():
            if MAC.fullmatch(v):
                h = re.sub(r'[:-]', '', v)
                for f in (h.lower(), h.upper(), ':'.join(h[i:i + 2] for i in range(0, 12, 2)).lower(),
                          ':'.join(h[i:i + 2] for i in range(0, 12, 2)).upper(), '-'.join(h[i:i + 2] for i in range(0, 12, 2)).upper()):
                    extra.setdefault(f, ph)
            if ph in ('<vin>', '<serial>', '<name>', '<phone-name>'):
                spaced = ' '.join('%02X' % b for b in v.encode('utf-8'))
                if len(spaced) >= 8:
                    extra.setdefault(spaced, ph)
        self.values.update(extra)
        self.lats = sorted(set(round(p[0], 2) for p in self.positions))
        self.lons = sorted(set(round(p[1], 2) for p in self.positions))
        ordered = sorted(self.values, key=len, reverse=True)
        names = [v for v in ordered if self.values[v] in ('<name>', '<phone-name>')]
        names = [v for v in names if not HEX_ONLY.match(v)]
        hexish = [v for v in ordered if v not in names and re.fullmatch(r'[0-9A-Fa-f:\- x]+', v)]
        plain = [v for v in ordered if v not in names and v not in hexish]
        self.rx_names = re.compile(r'(?<!\w)(?:' + '|'.join(re.escape(v) for v in names) + r')(?!\w)') if names else None
        self.rx_hex = re.compile('|'.join(re.escape(v) for v in hexish), re.I) if hexish else None
        self.rx_plain = re.compile('|'.join(re.escape(v) for v in plain)) if plain else None

    def near_position(self, value):
        try:
            x = float(value.replace(',', '.'))
        except ValueError:
            return False
        for scale in (1, 1e5, 1e6, 1e7):
            y = x / scale
            if any(abs(y - a) < 0.1 for a in self.lats) or any(abs(y - b) < 0.1 for b in self.lons):
                return True
        return False

    def sub(self, rx, repl, text, kind):
        if rx is None:
            return text
        done = [0]

        def one(m):
            r = repl(m) if callable(repl) else repl
            if r != m.group(0):
                done[0] += 1
            return r
        new = rx.sub(one, text)
        self.bump(kind, done[0])
        return new

    def clean_line(self, line, trace):
        if trace:
            line = self.clean_trace_line(line)
        line = self.sub(self.rx_plain, lambda m: self.values.get(m.group(0), '<private>'), line, 'known values')
        line = self.sub(self.rx_names, lambda m: self.values.get(m.group(0), '<name>'), line, 'names')
        line = self.sub(self.rx_hex, '<address>', line, 'known values')
        line = self.sub(NMEA, '<position removed>', line, 'position')
        line = self.sub(VIN_KV, lambda m: m.group(1) + m.group(2) + '<vin>', line, 'vin')
        line = self.sub(SERIAL_KV, lambda m: m.group(1) + m.group(2) + '<serial>', line, 'serial')
        line = self.sub(SECRET_KV, lambda m: m.group(1) + m.group(2) + '<removed>', line, 'other ids')
        line = self.sub(MAC, '<address>', line, 'address')
        line = self.sub(HEX48, '<address>', line, 'address')
        line = self.sub(IPV6, '<ipv6>', line, 'address')
        line = self.sub(PHONE, '<phone-number>', line, 'phone number')
        line = self.sub(COORD_KV, lambda m: m.group(1) + m.group(2) + '<pos>', line, 'position')
        src = POS_SOURCE.search(line)
        if src and not trace:
            self.bump('navigation lines')
            line = line[:src.end()] + ' <navigation details removed>'
        elif src:
            line = self.sub(DECIMAL, '<pos>', line, 'position')
            line = self.sub(BIGINT, '<pos>', line, 'position')
        elif self.lats:
            line = self.sub(DECIMAL, lambda m: '<pos>' if self.near_position(m.group(0)) else m.group(0), line, 'position')
        line = self.sub(VIN, lambda m: '<vin>' if re.search(r'[A-Z]', m.group(0)) and re.search(r'\d', m.group(0)) and not re.fullmatch(r'[0-9A-F]+', m.group(0)) else m.group(0), line, 'vin')
        return line

    def clean_trace_line(self, line):
        p = PARAM.match(line)
        if p:
            key, val = p.group('key'), p.group('val')
            if not val.strip():
                return line
            keep = key.startswith(KEEP_PREFIX) and not NAME_KEYS.match(key)
            if not keep and HEX_ONLY.match(val.strip()) and not RISKY_KEY.search(key):
                keep = True
            if keep:
                return line
            self.bump('iap2 values')
            return p.group('head') + key + p.group('sep') + '<removed>'
        if 'FILE[' in line:
            return line
        new, n = DATA_HEX.subn(lambda m: 'data=<removed %d bytes> ' % len(m.group(1).split()), line)
        self.bump('iap2 payloads', n)
        return new


def is_text(path):
    # Only known binaries are copied as they are; everything else is cleaned as text
    name = os.path.basename(path)
    return not (name.lower().endswith(BINARY_EXT) or name in BINARY_NAMES)


def read(path):
    with open(path, 'rb') as f:
        return f.read().decode('utf-8', 'replace')


def clean_folder(src, dst):
    files = []
    for root, _, names in os.walk(src):
        for n in sorted(names):
            files.append(os.path.join(root, n))
    c = Cleaner()
    texts = {}
    for p in files:
        if is_text(p):
            texts[p] = read(p)
            c.harvest(texts[p])
    own = ''.join(t for p, t in texts.items() if os.path.basename(p).startswith(('iap2.cfg', 'runHMI.sh')))
    c.finish_harvest(own)
    if os.path.exists(dst):
        shutil.rmtree(dst)
    os.makedirs(dst)
    for p in files:
        out = os.path.join(dst, os.path.relpath(p, src))
        os.makedirs(os.path.dirname(out), exist_ok=True)
        if p not in texts:
            shutil.copyfile(p, out)
            continue
        trace = os.path.basename(p).startswith('iap2_')
        lines = texts[p].split('\n')
        with open(out, 'w', encoding='utf-8', newline='') as f:
            f.write('\n'.join(c.clean_line(l, trace) for l in lines))
    return c


def leftovers(c, dst):
    """Second, independent look at the result: nothing private may be left."""
    left = {}
    probes = [v for v in c.values if len(v) >= 4]
    words = [re.compile(r'(?<!\w)' + re.escape(v) + r'(?!\w)') for v in probes if c.values[v] in ('<name>', '<phone-name>')]
    probes = [v for v in probes if c.values[v] not in ('<name>', '<phone-name>')]
    for root, _, names in os.walk(dst):
        for n in names:
            p = os.path.join(root, n)
            if not is_text(p):
                continue
            t = read(p)
            tl = t.lower()
            for v in probes:
                if v.lower() in tl:
                    left[c.values[v]] = left.get(c.values[v], 0) + 1
            for w in words:
                if w.search(t):
                    left['name'] = left.get('name', 0) + 1
            for kind, rx in (('vin', VIN_KV), ('serial', SERIAL_KV), ('position', NMEA), ('address', MAC), ('address', HEX48), ('phone number', PHONE)):
                for m in rx.finditer(t):
                    s = m.group(0)
                    if '<' in s:
                        continue
                    left[kind] = left.get(kind, 0) + 1
    return left


def make_zip(folder):
    z = folder + '.zip'
    with zipfile.ZipFile(z, 'w', zipfile.ZIP_DEFLATED) as zf:
        for root, _, names in os.walk(folder):
            for n in sorted(names):
                p = os.path.join(root, n)
                zf.write(p, os.path.relpath(p, os.path.dirname(folder)))
    return z


def main(argv=None):
    ap = argparse.ArgumentParser(description='Remove private data from a Collect Logs folder.')
    ap.add_argument('folders', nargs='+', help='folder(s) written by Collect Logs')
    ap.add_argument('-o', '--out', help='write the cleaned copies here instead of next to the folder')
    ap.add_argument('--no-zip', action='store_true', help='do not create the zip file')
    a = ap.parse_args(argv)
    if hasattr(sys.stdout, 'reconfigure'):
        sys.stdout.reconfigure(errors='replace')
    rc = 0
    for src in a.folders:
        src = os.path.abspath(src)
        if not os.path.isdir(src) or not os.path.basename(src):
            print('not a log folder: %s' % src)
            rc = max(rc, 1)
            continue
        if not any(names for _, _, names in os.walk(src)):
            print('empty folder: %s' % src)
            rc = max(rc, 1)
            continue
        base = os.path.basename(src) + '-clean'
        dst = os.path.join(os.path.abspath(a.out) if a.out else os.path.dirname(src), base)
        if os.path.exists(dst) and not os.path.isfile(os.path.join(dst, 'CLEANED.txt')):
            print('not overwriting %s, it was not made by this tool' % dst)
            rc = max(rc, 1)
            continue
        if os.path.isfile(dst + '.zip'):
            os.remove(dst + '.zip')
        c = clean_folder(src, dst)
        left = leftovers(c, dst)
        print(os.path.basename(src))
        for k in sorted(c.count):
            print('  removed %-14s %d' % (k, c.count[k]))
        with open(os.path.join(dst, 'CLEANED.txt'), 'w', encoding='utf-8', newline='\n') as f:
            f.write('cleaned with cpvc_clean_logs.py\n')
            for k in sorted(c.count):
                f.write('removed %s: %d\n' % (k, c.count[k]))
            f.write('left: %s\n' % ('none' if not left else ', '.join('%s %d' % kv for kv in sorted(left.items()))))
        if left:
            print('  WARNING: not everything could be removed: %s' % ', '.join('%s %d' % kv for kv in sorted(left.items())))
            print('  The folder was not zipped. Check %s before you post anything.' % dst)
            rc = max(rc, 2)
            continue
        print('  nothing private left')
        print('  ' + (dst if a.no_zip else make_zip(dst)))
    return rc


if __name__ == '__main__':
    sys.exit(main())
