#!/usr/bin/env python3
"""Restore the Dexcom Clarity provider library from the pinned upstream Juggluco APK."""
import hashlib
from html import unescape
import json
from pathlib import Path
import re
import shutil
import sys
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[2]
MANIFEST = ROOT / 'scripts/dist/clarity-provider.json'
CACHE = ROOT / '.local/build-inputs'
# SourceForge answers scripted user agents with 403.
AGENT = 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko)'


def digest(data):
    return hashlib.sha256(data).hexdigest()


def present(spec):
    path = ROOT / spec['path']
    return path.is_file() and path.stat().st_size == spec['size'] and digest(path.read_bytes()) == spec['sha256']


def fetch(url):
    return urllib.request.urlopen(urllib.request.Request(url, headers={'User-Agent': AGENT}), timeout=120)


def download(source, target):
    with fetch(source['page']) as page:
        html = page.read().decode('utf-8', 'replace')
    # The download page carries a short-lived signed link to a mirror.
    link = re.search(r'https://downloads\.sourceforge\.net/project/[^"\'<>\s]*' + re.escape(source['name']) +
                     r'\?ts=[^"\'<>\s]+', html)
    if not link:
        raise ValueError(f"No download link for {source['name']} on {source['page']}")
    partial = target.with_name(target.name + '.part')
    with fetch(unescape(link[0])) as src, partial.open('wb') as out:
        shutil.copyfileobj(src, out)
    partial.replace(target)


def archive(source):
    CACHE.mkdir(parents=True, exist_ok=True)
    apk = CACHE / source['name']
    if apk.is_file() and digest(apk.read_bytes()) == source['sha256']:
        return apk
    print(f"Downloading {source['name']} ({source['size'] / 1e6:.1f} MB) for the Clarity provider", file=sys.stderr)
    download(source, apk)
    if apk.stat().st_size != source['size'] or digest(apk.read_bytes()) != source['sha256']:
        apk.unlink()
        raise ValueError(f"{source['name']} checksum mismatch")
    return apk


def main():
    data = json.loads(MANIFEST.read_text())
    missing = [spec for spec in data['files'] if not present(spec)]
    if not missing:
        return
    with zipfile.ZipFile(archive(data['source'])) as z:
        for spec in missing:
            content = z.read(spec['entry'])
            if len(content) != spec['size'] or digest(content) != spec['sha256']:
                raise ValueError(f"{spec['entry']} checksum mismatch")
            dest = ROOT / spec['path']
            dest.parent.mkdir(parents=True, exist_ok=True)
            dest.write_bytes(content)
            print(f"Restored {spec['path']}", file=sys.stderr)


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, KeyError, zipfile.BadZipFile) as e:
        sys.exit(f'Clarity provider restore failed: {e}')
