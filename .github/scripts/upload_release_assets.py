"""Upload checksum-verified public artifacts to an existing draft release."""
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import tempfile
import urllib.error
import urllib.parse
import urllib.request

MAX_BYTES = 150 * 1024 * 1024
ALLOWED_SUFFIXES = {'.apk', '.zip', '.pem', '.json'}


def validate_url(url):
    parsed = urllib.parse.urlsplit(url)
    host = parsed.hostname or ''
    allowed = host in {'codeload.github.com', 'dl.dropboxusercontent.com'} or host.endswith('.dl.dropboxusercontent.com')
    if parsed.scheme != 'https' or parsed.username or parsed.password or parsed.port not in (None, 443) or not allowed:
        raise ValueError('Artifact download URL is not an approved HTTPS source')


class DownloadRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        validate_url(newurl)
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def validate_manifest(raw):
    rows = json.loads(raw)
    if not isinstance(rows, list) or not 1 <= len(rows) <= 10:
        raise ValueError('Expected 1 to 10 public release assets')
    names = set()
    for row in rows:
        name = row.get('name', '')
        if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]{0,119}', name) or name in names:
            raise ValueError('Invalid or duplicate asset filename')
        if name != 'SHA256SUMS' and Path(name).suffix not in ALLOWED_SUFFIXES:
            raise ValueError('Only public APK, source ZIP, certificate, and metadata assets are accepted')
        if name in {'signing-credentials.json', 'auth.json'}:
            raise ValueError('Credential files cannot be release assets')
        if not re.fullmatch(r'[0-9a-f]{64}', row.get('sha256', '')) or type(row.get('bytes')) is not int or not 0 < row['bytes'] <= MAX_BYTES:
            raise ValueError('Asset checksum or size is invalid')
        if ('url' in row) == ('base64' in row):
            raise ValueError('Each asset needs exactly one content source')
        if 'url' in row:
            validate_url(row['url'])
        names.add(name)
    return rows


def receive_asset(row, directory):
    path = directory / row['name']
    if 'base64' in row:
        path.write_bytes(base64.b64decode(row['base64'], validate=True))
    else:
        url = row['url']
        masked = url.replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')
        print('::add-mask::' + masked, flush=True)
        opener = urllib.request.build_opener(DownloadRedirect())
        with opener.open(url, timeout=60) as response, path.open('wb') as output:
            size = 0
            while chunk := response.read(65536):
                size += len(chunk)
                if size > row['bytes']:
                    raise ValueError('Downloaded asset exceeds its declared size')
                output.write(chunk)
    if path.stat().st_size != row['bytes'] or hashlib.sha256(path.read_bytes()).hexdigest() != row['sha256']:
        raise ValueError('Asset size or SHA-256 mismatch: ' + row['name'])
    if path.suffix == '.pem' and (b'-----BEGIN CERTIFICATE-----' not in path.read_bytes() or b'PRIVATE KEY' in path.read_bytes()):
        raise ValueError('Only a public certificate may be uploaded')
    return path


def existing_matches(asset, row):
    return asset.get('state') == 'uploaded' and asset.get('size') == row['bytes'] and asset.get('digest') == 'sha256:' + row['sha256']


def main():
    repo, tag, commit = (os.environ[key] for key in ('GH_REPO', 'RELEASE_TAG', 'RELEASE_COMMIT'))
    if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', repo) or not re.fullmatch(r'[0-9a-f]{40}', commit):
        raise ValueError('Invalid repository or expected source commit')
    rows = validate_manifest(os.environ['RELEASE_ASSETS'])
    headers = {'Authorization': 'Bearer ' + os.environ['GH_TOKEN'], 'Accept': 'application/vnd.github+json', 'X-GitHub-Api-Version': '2022-11-28', 'User-Agent': 'Droide-release-publisher'}

    def api(url, data=None, mime='application/json'):
        request = urllib.request.Request(url, data=data, headers={**headers, 'Content-Type': mime})
        with urllib.request.urlopen(request, timeout=180) as response:
            return json.load(response)

    api_root = 'https://api.github.com/repos/' + repo
    ref = urllib.parse.quote(tag, safe='')
    if api(api_root + '/commits/' + ref)['sha'] != commit:
        raise ValueError('Release tag does not match the verified source commit')
    release = api(api_root + '/releases/tags/' + ref)
    if not release['draft'] or release['tag_name'] != tag:
        raise ValueError('Assets may only be added to the intended draft release')
    existing = {asset['name']: asset for asset in release['assets']}
    for row in rows:
        if row['name'] in existing and not existing_matches(existing[row['name']], row):
            raise ValueError('Conflicting release asset; nothing will be replaced')
    with tempfile.TemporaryDirectory(prefix='droide-release-') as temporary:
        pending = [(row, receive_asset(row, Path(temporary))) for row in rows if row['name'] not in existing]
        for row, path in pending:
            if not api(api_root + '/releases/' + str(release['id']))['draft']:
                raise ValueError('Release was published before the upload completed')
            url = 'https://uploads.github.com/repos/' + repo + '/releases/' + str(release['id']) + '/assets?' + urllib.parse.urlencode({'name': row['name']})
            asset = api(url, path.read_bytes(), 'application/octet-stream')
            if not existing_matches(asset, row):
                raise ValueError('GitHub asset verification failed: ' + row['name'])
            print('Verified upload: ' + row['name'], flush=True)
    print('Release assets complete; draft publication remains a separate step.')


if __name__ == '__main__':
    try:
        main()
    except urllib.error.HTTPError as error:
        raise SystemExit('Release transfer failed with HTTP ' + str(error.code)) from None
    except urllib.error.URLError:
        raise SystemExit('Release transfer could not reach its service') from None
