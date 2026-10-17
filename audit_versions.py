#!/usr/bin/env python3
"""Re-implementation of Dynmap's HDBlockModels.checkVersionRange / parseVersion
to audit which version-tagged lines of models_*.txt / texture_*.txt would be
skipped for a given MC version."""

import re, sys, collections

VSCALE = [10000000000, 100000000, 1000000, 10000, 100, 1]

def normalize_version(v):
    out = []
    skip = False
    for c in v:
        if c == '.' or c.isdigit():
            out.append(c)
            skip = False
        else:
            if not skip:
                skip = True
                out.append('.')
    return ''.join(out)

def parse_version(v, up):
    v = normalize_version(v)
    vv = v.split('.')
    ver = 0
    for i in range(len(VSCALE)):
        if i < len(vv):
            try:
                ver += VSCALE[i] * int(vv[i])
            except ValueError:
                pass
        elif up:
            ver += VSCALE[i] * 99
    return ver

def check_version_range(ver, rng):
    if ver == rng:
        return True
    parts = rng.split('-')
    if len(parts) == 1:
        low = high = parts[0]
    else:
        low, high = parts[0], parts[1]
    v = parse_version(ver, False)
    if v == 0:
        return False
    if low and parse_version(low, False) > v:
        return False
    if high and parse_version(high, True) < v:
        return False
    return True

TAG_RE = re.compile(r'^\[([^\]]*)\](.*)$')

def audit(path, mcver, refver):
    results = collections.defaultdict(list)  # tag -> [(lineno, sample)]
    with open(path, encoding='utf-8', errors='replace') as f:
        for n, line in enumerate(f, 1):
            line = line.rstrip('\n')
            m = TAG_RE.match(line)
            if not m:
                continue
            tag, rest = m.group(1), m.group(2)
            if tag.startswith('mod:'):
                continue
            ok_cur = check_version_range(mcver, tag)
            ok_ref = check_version_range(refver, tag)
            if ok_cur != ok_ref:
                results[tag].append((n, rest[:90]))
    return results

if __name__ == '__main__':
    mcver = sys.argv[1] if len(sys.argv) > 1 else '26.1.2'
    refver = sys.argv[2] if len(sys.argv) > 2 else '1.21.11'
    for path in sys.argv[3:] or []:
        res = audit(path, mcver, refver)
        print(f'== {path}: lines whose behavior differs between "{mcver}" and "{refver}" ==')
        if not res:
            print('   (none)')
        for tag, items in sorted(res.items()):
            skipped = not check_version_range(mcver, tag)
            state = 'SKIPPED on ' + mcver if skipped else 'NEW on ' + mcver
            print(f'  [{tag}]  -> {state}  ({len(items)} lines)')
            for n, sample in items[:4]:
                print(f'      line {n}: {sample}')
            if len(items) > 4:
                print(f'      ... and {len(items)-4} more')
