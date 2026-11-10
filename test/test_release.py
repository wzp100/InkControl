"""Cloud-only release policy tests: foreign, unsigned, or altered evidence cannot publish."""
import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from types import SimpleNamespace
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('publish_release', Path(__file__).resolve().parents[1]/'tools/publish_release.py')
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)


class ReleasePolicyTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        self.context = {'GITHUB_REPOSITORY': release.REPOSITORY, 'GITHUB_SHA': 'a'*40,
                        'GITHUB_RUN_ID': '123', 'GITHUB_RUN_ATTEMPT': '1'}
        self.artifacts = {}
        for name, data in [('InkControl-v0.1.0-rc15.apk', b'app'), ('inkcontrol_backend-v0.1.0-rc15.zip', b'backend')]:
            (self.directory/name).write_bytes(data)
            self.artifacts[name] = hashlib.sha256(data).hexdigest()
        self.manifest = {'version': '0.1.0-rc15', 'signing': 'verified', 'artifacts': self.artifacts,
                         'cloud': dict(self.context), 'sources': {'ci/trusted-certificate.der': 'c'*64}}
        self.package = {'version': '0.1.0-rc15', 'signing': 'verified', 'default_off': 88,
                        'module_checksums': True, 'embedded_zip_identical': True,
                        'apk_sha256': self.artifacts['InkControl-v0.1.0-rc15.apk'],
                        'zip_sha256': self.artifacts['inkcontrol_backend-v0.1.0-rc15.zip']}
        self.save()

    def save(self):
        for name, value in [('build-manifest.json', self.manifest), ('package-verification.json', self.package)]:
            (self.directory/name).write_text(json.dumps(value), encoding='utf-8')
        (self.directory/'SHA256SUMS').write_text(''.join(sha+'  '+name+'\n' for name, sha in self.artifacts.items()), encoding='utf-8')

    def validate(self):
        return release.validate_candidate(self.directory, self.context, 'c'*64)

    def test_signed_pair_and_versions(self):
        self.assertEqual(self.validate()['version'], '0.1.0-rc15')
        self.assertEqual(release.release_identity('1.2.3'), ('v1.2.3', False))
        self.assertEqual(release.release_identity('1.2.3-rc2'), ('v1.2.3-rc2', True))

    def test_foreign_run_attempt_commit_and_repo(self):
        for key in self.context:
            with self.subTest(key=key):
                original = copy.deepcopy(self.manifest)
                self.manifest['cloud'][key] = 'foreign'
                self.save()
                with self.assertRaises(ValueError): self.validate()
                self.manifest = original

    def test_unsigned_unknown_assets_and_certificate(self):
        original = copy.deepcopy(self.manifest)
        for mutation in ('unsigned', 'extra', 'certificate'):
            with self.subTest(mutation=mutation):
                self.manifest = copy.deepcopy(original)
                if mutation == 'unsigned': self.manifest['signing'] = 'unsigned'
                if mutation == 'extra': self.manifest['artifacts'] = {'../../private.key': 'a'*64}
                if mutation == 'certificate': self.manifest['sources']['ci/trusted-certificate.der'] = 'b'*64
                self.save()
                with self.assertRaises(ValueError): self.validate()

    def test_tampered_artifact_and_package(self):
        name = 'InkControl-v0.1.0-rc15.apk'
        (self.directory/name).write_bytes(b'tampered')
        with self.assertRaises(ValueError): self.validate()
        (self.directory/name).write_bytes(b'app')
        self.package['zip_sha256'] = 'f'*64
        self.save()
        with self.assertRaises(ValueError): self.validate()

    def test_incomplete_verification(self):
        for field in ('signing', 'default_off', 'module_checksums', 'embedded_zip_identical'):
            with self.subTest(field=field):
                previous = self.package[field]
                self.package[field] = False
                self.save()
                with self.assertRaises(ValueError): self.validate()
                self.package[field] = previous

    def test_unsafe_version(self):
        for version in ('../secret', '1.0', '1.0.0-rc0', '1.0.0;echo', '1.0.0-beta'):
            with self.assertRaises(ValueError): release.release_identity(version)

    def test_existing_assets_must_match_without_overwrite(self):
        path = self.directory/'InkControl-v0.1.0-rc15.apk'
        existing = {'assets': [{'name': path.name, 'digest': 'sha256:'+release.digest(path)}]}
        self.assertEqual(release.verify_remote_assets(existing, [path], True), {path.name})
        existing['assets'][0]['digest'] = 'sha256:'+'0'*64
        with self.assertRaises(ValueError): release.verify_remote_assets(existing, [path], True)
        with self.assertRaises(ValueError): release.verify_remote_assets({'assets': []}, [path], True)

    def test_foreign_repository_and_noncloud_publication_rejected(self):
        with patch.dict(release.os.environ, {}, clear=True):
            with self.assertRaises(ValueError): release.publish('v1.0.0', False, [], None, 'a'*40)
        with patch.dict(release.os.environ, {'GITHUB_ACTIONS': 'true', 'GITHUB_REF': 'refs/heads/main'}, clear=True):
            with patch.object(release, 'gh', return_value='{"full_name":"other/fork","private":false}'):
                with self.assertRaises(ValueError): release.publish('v1.0.0', False, [], None, 'a'*40)

    def test_public_repository_can_retain_published_version(self):
        with patch.dict(release.os.environ, {'GITHUB_ACTIONS': 'true', 'GITHUB_REF': 'refs/heads/main'}, clear=True), \
             patch.object(release, 'gh', return_value=json.dumps({'full_name': release.REPOSITORY, 'private': False})), \
             patch.object(release, 'lookup_release', return_value={'draft': False, 'html_url': 'https://example.test/release'}):
            self.assertEqual(release.publish('v1.0.0', False, [], None, 'a'*40), 'https://example.test/release')

    def test_published_version_is_retained(self):
        with patch.dict(release.os.environ, {'GITHUB_ACTIONS': 'true', 'GITHUB_REF': 'refs/heads/main'}, clear=True), \
             patch.object(release, 'gh', return_value=json.dumps({'full_name': release.REPOSITORY, 'private': True})) as api, \
             patch.object(release, 'lookup_release', return_value={'draft': False, 'html_url': 'https://example.test/release'}):
            self.assertEqual(release.publish('v1.0.0', False, [], None, 'a'*40), 'https://example.test/release')
            self.assertEqual(api.call_count, 1)

    def test_draft_without_tag_resolves_from_authenticated_list(self):
        draft = {'id': 42, 'tag_name': 'v1.0.0-rc1', 'draft': True, 'assets': []}
        with patch.object(release.subprocess, 'run', return_value=SimpleNamespace(returncode=1, stderr='gh: Not Found (HTTP 404)')), \
             patch.object(release, 'gh', return_value=json.dumps([[], [draft]])):
            self.assertEqual(release.lookup_release('v1.0.0-rc1'), draft)
            self.assertIsNone(release.lookup_release('v2.0.0'))

    def test_lookup_network_failure_does_not_create_release(self):
        with patch.object(release.subprocess, 'run', return_value=SimpleNamespace(returncode=1, stderr='connection reset')), \
             patch.object(release, 'gh') as api:
            with self.assertRaises(RuntimeError): release.lookup_release('v1.0.0')
            api.assert_not_called()

    def test_new_draft_uses_returned_id_without_tag_lookup(self):
        sha = 'a'*40
        notes = self.directory/'notes.md'
        notes.write_text('notes\n<!-- InkControl source: '+sha+' -->\n', encoding='utf-8')
        artifact = self.directory/'InkControl-v0.1.0-rc15.apk'
        remote_asset = {'name': artifact.name, 'digest': 'sha256:'+release.digest(artifact)}
        draft = {'id': 42, 'tag_name': 'v0.1.0-rc15', 'draft': True, 'assets': []}
        published = dict(draft, draft=False, html_url='https://example.test/release', assets=[remote_asset])
        with patch.dict(release.os.environ, {'GITHUB_ACTIONS': 'true', 'GITHUB_REF': 'refs/heads/main'}, clear=True), \
             patch.object(release, 'gh', return_value=json.dumps({'full_name': release.REPOSITORY, 'private': True})), \
             patch.object(release, 'lookup_release', return_value=None) as lookup, \
             patch.object(release, 'api_json', side_effect=[draft, remote_asset, published]) as api:
            self.assertEqual(release.publish('v0.1.0-rc15', True, [artifact], notes, sha), 'https://example.test/release')
            self.assertEqual(lookup.call_count, 1)
            self.assertEqual(api.call_args_list[1].args[1], 'repos/'+release.REPOSITORY+'/releases/42/assets?name='+artifact.name)
            self.assertEqual(api.call_args_list[2].args[1], 'repos/'+release.REPOSITORY+'/releases/42')

    def test_mismatched_upload_is_never_published(self):
        sha = 'a'*40
        notes = self.directory/'notes.md'; notes.write_text('notes', encoding='utf-8')
        artifact = self.directory/'InkControl-v0.1.0-rc15.apk'
        draft = {'id': 42, 'tag_name': 'v0.1.0-rc15', 'draft': True, 'assets': []}
        wrong = {'name': artifact.name, 'digest': 'sha256:'+'0'*64}
        with patch.dict(release.os.environ, {'GITHUB_ACTIONS': 'true', 'GITHUB_REF': 'refs/heads/main'}, clear=True), \
             patch.object(release, 'gh', return_value=json.dumps({'full_name': release.REPOSITORY, 'private': True})), \
             patch.object(release, 'lookup_release', return_value=None), \
             patch.object(release, 'api_json', side_effect=[draft, wrong]) as api:
            with self.assertRaises(ValueError): release.publish('v0.1.0-rc15', True, [artifact], notes, sha)
            self.assertEqual(api.call_count, 2)


if __name__ == '__main__': unittest.main()
