"""Validate a stable version tag without executing source or interpolating input."""
import argparse
import os
import re
from pathlib import Path


def workspace_version():
    source = (Path(__file__).resolve().parents[1] / 'Cargo.toml').read_text()
    section = source.split('[workspace.package]', 1)[1].split('\n[', 1)[0]
    return re.search(r'^version\s*=\s*"([^"]+)"', section, re.M).group(1)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--tag')
    args = parser.parse_args()
    if args.tag is None:
        ref = os.environ.get('GITHUB_REF', '')
        if not ref.startswith('refs/tags/'):
            raise SystemExit('Release must run on a version tag, not a branch.')
        tag = ref.removeprefix('refs/tags/')
    else:
        tag = args.tag
    if not re.fullmatch(r'v(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)', tag):
        raise SystemExit('Use a stable tag vX.Y.Z, with no leading zeroes.')
    version = workspace_version()
    if tag != 'v' + version:
        raise SystemExit(f'Tag {tag} does not match Cargo workspace version {version}.')
    print(f'Validated {tag}')
