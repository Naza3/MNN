#!/usr/bin/env python3
"""Prepare an existing, certificate-pinned CI identity without generating keys."""
import argparse
import base64
import binascii
import hashlib
import os
from pathlib import Path
import re
import subprocess
import tempfile


ALIAS = 'mnn-local-api-ci-test'
SECRET_NAME = 'MNN_SIGNING_KEYSTORE_BASE64'
MAX_KEYSTORE_BYTES = 1024 * 1024


def keytool(arguments):
    # Capture all output: malformed input must not put keystore data in CI logs.
    try:
        result = subprocess.run(
            ['keytool', '-J-Duser.language=en', '-J-Duser.country=US', *arguments],
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=30, check=False)
    except (OSError, subprocess.TimeoutExpired):
        raise ValueError('Cannot run JDK keytool to verify the signing identity') from None
    if result.returncode != 0:
        raise ValueError('Cannot verify the existing signing keystore and required alias')
    return result.stdout


def prepare(cache, output, expected, secret):
    if not re.fullmatch(r'[0-9a-fA-F]{64}', expected):
        raise ValueError('Expected certificate SHA-256 must contain exactly 64 hexadecimal digits')
    if output.exists() or output.is_symlink():
        raise ValueError('Prepared signing output already exists; refusing to replace it')
    if secret:
        if len(secret) > MAX_KEYSTORE_BYTES * 2:
            raise ValueError('Signing Secret exceeds the supported keystore size')
        try:
            encoded = secret.encode('ascii')
            data = base64.b64decode(encoded, validate=True)
            if base64.b64encode(data) != encoded:
                raise ValueError('Non-canonical base64')
        except (UnicodeEncodeError, binascii.Error, ValueError):
            raise ValueError('Signing Secret is not canonical base64; cache fallback is disabled') from None
    else:
        if cache.is_symlink() or not cache.is_file():
            raise ValueError('Signing Secret and exact cached signing key are unavailable; no replacement will be generated')
        if cache.stat().st_size > MAX_KEYSTORE_BYTES:
            raise ValueError('Cached signing keystore exceeds the supported size')
        data = cache.read_bytes()
    if not data or len(data) > MAX_KEYSTORE_BYTES:
        raise ValueError('Signing keystore is empty or exceeds the supported size')
    output.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    temporary = None
    try:
        descriptor, name = tempfile.mkstemp(prefix='.verify-signing-', dir=output.parent)
        temporary = Path(name)
        with os.fdopen(descriptor, 'wb') as target:
            os.fchmod(target.fileno(), 0o600)
            target.write(data)
        common = ['-keystore', str(temporary), '-storepass', 'android', '-alias', ALIAS]
        listing = keytool(['-list', '-v', *common])
        if b'Entry type: PrivateKeyEntry' not in listing:
            raise ValueError('Required signing alias is not a PrivateKeyEntry')
        certificate = keytool(['-exportcert', *common])
        fingerprint = hashlib.sha256(certificate).hexdigest()
        if fingerprint != expected.lower():
            raise ValueError('Signing certificate does not match the pinned identity; no replacement will be generated')
        # Publish only a fully verified, private file; no key ever enters reports/artifacts.
        os.replace(temporary, output)
        temporary = None
        print('Prepared existing signing identity; public certificate SHA-256: ' + fingerprint)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--cache-keystore', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--expected-sha256', required=True)
    args = parser.parse_args()
    # Do not inherit the Secret into keytool or leave it in process child environments.
    secret = os.environ.pop(SECRET_NAME, '')
    try:
        prepare(args.cache_keystore, args.output, args.expected_sha256, secret)
    except ValueError as error:
        # These are only this script's fixed messages, never keytool output or key bytes.
        raise SystemExit(str(error)) from None
    except OSError:
        raise SystemExit('Signing identity preparation failed: supply the valid pinned Secret or exact cached key; no replacement was generated') from None


if __name__ == '__main__':
    main()
