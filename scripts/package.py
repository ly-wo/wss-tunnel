"""Package locked-build executables for CI artifacts and GitHub Releases."""
import argparse
import hashlib
import os
from pathlib import Path
import re
import tarfile
import zipfile
from check_release import workspace_version

TARGETS = ['x86_64-unknown-linux-gnu', 'aarch64-unknown-linux-gnu',
           'aarch64-apple-darwin', 'x86_64-apple-darwin', 'x86_64-pc-windows-msvc']

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--target', required=True, choices=TARGETS)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    target_dir = Path(os.environ.get('CARGO_TARGET_DIR', str(root / 'target')))
    suffix = '.exe' if args.target.endswith('msvc') else ''
    version = workspace_version()
    if not os.environ.get('GITHUB_REF', '').startswith('refs/tags/'):
        sha = os.environ.get('GITHUB_SHA', 'local')[:12]
        if not re.fullmatch('[A-Za-z0-9]+', sha):
            raise SystemExit('Invalid commit label')
        version += '-dev-' + sha
    dist = root / 'dist'
    dist.mkdir(exist_ok=True)
    base = f'wss-tunnel-{version}-{args.target}'
    archive = dist / (base + ('.zip' if suffix else '.tar.gz'))
    files = [(target_dir / args.target / 'release' / (name + suffix), name + suffix)
             for name in ['tunnel-client', 'tunnel-server']]
    files += [(root / 'README.md', 'README.md'),
              (root / 'client/.env.example', 'client.env.example'),
              (root / 'server/.env.example', 'server.env.example')]
    for file, _ in files:
        if not file.is_file():
            raise SystemExit(f'Missing artifact: {file}')
    if suffix:
        with zipfile.ZipFile(archive, 'w', zipfile.ZIP_DEFLATED) as stream:
            for file, name in files:
                stream.write(file, base + '/' + name)
    else:
        with tarfile.open(archive, 'w:gz') as stream:
            for file, name in files:
                stream.add(file, arcname=base + '/' + name)
    checksum = hashlib.sha256(archive.read_bytes()).hexdigest()
    archive.with_name(archive.name + '.sha256').write_text(checksum + '  ' + archive.name + '\n')
    print(archive)
