import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location(
    'release_notes', Path(__file__).resolve().parents[1] / 'release-notes.py')
release_notes = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release_notes)


class ReleaseNotesTest(unittest.TestCase):
    def test_extracts_only_requested_version(self):
        text = ('# Changelog\n\n## [Unreleased]\n\nFuture changes.\n\n'
                '## [1.2.0]\n\n### Added\n\n- Current changes.\n\n'
                '## [1.1.0]\n\nOld changes.\n')
        expected = '### Added\n\n- Current changes.\n'
        self.assertEqual(expected, release_notes.extract(text, 'v1.2.0'))
        self.assertEqual(expected, release_notes.extract(text, '1.2.0'))

    def test_rejects_missing_duplicate_empty_and_invalid_versions(self):
        for text, version in [
            ('## [1.1.0]\nOld.\n', '1.2.0'),
            ('## [1.2.0]\nFirst.\n## [1.2.0]\nSecond.\n', '1.2.0'),
            ('## [1.2.0]\n\n## [1.1.0]\nOld.\n', '1.2.0'),
            ('## [1.2.0]\nCurrent.\n', 'main'),
        ]:
            with self.subTest(text=text, version=version), self.assertRaises(ValueError):
                release_notes.extract(text, version)


if __name__ == '__main__':
    unittest.main()
