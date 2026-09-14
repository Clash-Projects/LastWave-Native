#!/usr/bin/env python3
"""Resolve the universal (non-legacy, non-raw) release APK URL for a tag.

Checks THIS repo's release first, then the upstream repo. Prints the asset
download URL, or exits 1.

Usage: resolve_apk.py [--tag TAG] [--upstream owner/repo]
"""

import argparse
import json
import os
import sys
import urllib.error
import urllib.request


def api(path, token):
    req = urllib.request.Request('https://api.github.com' + path)
    req.add_header('Accept', 'application/vnd.github+json')
    if token:
        req.add_header('Authorization', 'token ' + token)
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return json.loads(r.read().decode())
    except urllib.error.HTTPError:
        return None
    except Exception as exc:  # network hiccup -> caller decides
        print('request failed: %s' % exc, file=sys.stderr)
        return None


def latest_tag(repo, token):
    d = api('/repos/%s/releases/latest' % repo, token)
    return (d or {}).get('tag_name') or ''


def pick_universal(repo, tag, token):
    d = api('/repos/%s/releases/tags/%s' % (repo, tag), token)
    if not d or 'assets' not in d:
        return None
    for a in d['assets']:
        name = a['name']
        if not name.endswith('.apk'):
            continue
        # universal only: android7 shares the same versionCode as universal and
        # would be rejected by `fdroid update`; raw/uncompressed are huge.
        if any(x in name for x in ('android7', 'uncompressed', 'raw')):
            continue
        return a['browser_download_url']
    return None


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--tag', default='')
    p.add_argument('--upstream', default='Clash-Projects/LastWave-Native')
    p.add_argument('--print-tag', action='store_true',
                   help='print the resolved tag instead of the APK URL')
    args = p.parse_args()

    token = os.environ.get('GH_TOKEN', '')
    repo = os.environ.get('GITHUB_REPOSITORY', args.upstream)

    tags = [args.tag] if args.tag else []
    if not tags:
        t = latest_tag(repo, token) or latest_tag(args.upstream, token)
        if t:
            tags.append(t)
        else:
            print('could not determine latest tag', file=sys.stderr)
            return 1
        if args.print_tag:
            print(tags[0])
            return 0

    for tag in tags:
        for source in (repo, args.upstream):
            url = pick_universal(source, tag, token)
            if url:
                print(url)
                return 0
        print('no universal APK for %s in %s or %s' % (tag, repo, args.upstream),
              file=sys.stderr)
    return 1


if __name__ == '__main__':
    sys.exit(main())
