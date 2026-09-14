#!/usr/bin/env python3
"""Relax androguard's ARSC res0/res1 assertions (Debian's patch).

Debian's androguard 3.4.0~a1-17 carries a patch that downgrades the
"res0/res1 must be zero!" ResParserError to a warning, because APKs built
with AGP 8+ legitimately have non-zero values there. The pristine PyPI
release raises, which makes `fdroid update` abort on those APKs.

This applies the equivalent relaxation to an androguard installed in a
virtualenv. Safe to run repeatedly (no-op if already patched).

Usage: patch_androguard.py [--venv /opt/fdroid-venv]
"""

import argparse
import glob
import os
import re
import sys

TARGETS = (
    'raise ResParserError("res0 must be zero!")',
    'raise ResParserError("res1 must be zero!")',
)


def find_axml(venv):
    pats = [
        os.path.join(venv, 'lib', 'python*', 'site-packages',
                     'androguard', 'core', 'bytecodes', 'axml', '__init__.py'),
        os.path.join(venv, 'lib', 'python*', 'site-packages',
                     'androguard', 'core', 'axml', '__init__.py'),
    ]
    for pat in pats:
        for path in glob.glob(pat):
            return path
    return None


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--venv', default='/opt/fdroid-venv')
    args = p.parse_args()

    path = find_axml(args.venv)
    if not path:
        print('androguard axml module not found under %s' % args.venv, file=sys.stderr)
        return 1

    src = open(path, encoding='utf-8').read()
    out = src
    for stmt in TARGETS:
        field = re.search(r'res(\d)', stmt).group(0)
        out = out.replace(stmt, 'log.warning("%s must be zero!")' % field)

    if out == src:
        print('already patched: %s' % path)
        return 0

    # make sure the module is wrapped in try/except like Debian's version
    open(path, 'w', encoding='utf-8').write(out)
    print('patched: %s' % path)
    return 0


if __name__ == '__main__':
    sys.exit(main())
