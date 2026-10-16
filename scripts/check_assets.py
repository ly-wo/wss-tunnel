"""Require all five target archives and verify their checksums before publication."""
import hashlib
import os
from pathlib import Path
from package import TARGETS
from check_release import workspace_version

root = Path(__file__).resolve().parents[1]
version = workspace_version()
if os.environ['TAG'] != 'v' + version:
    raise SystemExit('Release tag/version mismatch')
lines = []
for target in TARGETS:
    ext = '.zip' if target.endswith('msvc') else '.tar.gz'
    name = f'wss-tunnel-{version}-{target}{ext}'
    archive = root / 'dist' / name
    digest = hashlib.sha256(archive.read_bytes()).hexdigest()
    expected = (root / 'dist' / (name + '.sha256')).read_text().strip()
    line = digest + '  ' + name
    if expected != line:
        raise SystemExit(f'Checksum mismatch: {name}')
    lines.append(line)
(root / 'dist/SHA256SUMS').write_text('\n'.join(lines) + '\n')
print('All five archives verified.')
