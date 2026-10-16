"""Exercise release gates with isolated synthetic archives, never production assets."""
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'scripts'))
from package import TARGETS
from check_release import workspace_version


class ReleaseTools(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        shutil.copytree(ROOT / 'scripts', self.root / 'scripts')
        for name in ['Cargo.toml', 'README.md', 'client/.env.example', 'server/.env.example']:
            dest = self.root / name
            dest.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy(ROOT / name, dest)
        self.version = workspace_version()
        self.env = dict(os.environ, GITHUB_REF='refs/tags/v'+self.version, TAG='v'+self.version,
                        CARGO_TARGET_DIR=str(self.root / 'target'))

    def tearDown(self):
        self.temp.cleanup()

    def run_script(self, script, *args, ok=True):
        result = subprocess.run([sys.executable, str(self.root / 'scripts' / script), *args],
                                env=self.env, capture_output=True, text=True)
        self.assertEqual(result.returncode == 0, ok, result.stdout + result.stderr)

    def test_tag_gate(self):
        self.run_script('check_release.py')
        for tag in ['main', 'v999999.0.0', 'v01.1.0', 'v0.1.0;echo nope', 'v0.1.0-rc1']:
            self.run_script('check_release.py', '--tag', tag, ok=False)
        self.env['GITHUB_REF'] = 'refs/heads/main'
        self.run_script('check_release.py', ok=False)

    def test_archives_and_tamper_detection(self):
        for target in TARGETS:
            directory = self.root / 'target' / target / 'release'
            directory.mkdir(parents=True)
            for name in ['tunnel-client', 'tunnel-server']:
                file = directory / (name + ('.exe' if target.endswith('msvc') else ''))
                file.write_bytes(b'fixture, not a real executable')
                file.chmod(0o755)
            self.run_script('package.py', '--target', target)
        self.run_script('check_assets.py')
        self.assertEqual(len((self.root / 'dist/SHA256SUMS').read_text().splitlines()), 5)
        next((self.root / 'dist').glob('*.tar.gz')).write_bytes(b'tampered')
        self.run_script('check_assets.py', ok=False)

    def test_missing_assets_fail(self):
        self.run_script('check_assets.py', ok=False)
        self.run_script('package.py', '--target', TARGETS[0], ok=False)


if __name__ == '__main__':
    unittest.main()
