"""Verify exact upstream public self-test data; never expose its PEM body."""
import hashlib
import json
from pathlib import Path
import re
import struct
import zipfile


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def class_utf8(data):
    if data[:4] != b'\xca\xfe\xba\xbe':
        raise ValueError('Invalid fixture class file')
    count, = struct.unpack_from('>H', data, 8)
    offset, index, result = 10, 1, []
    widths = {3: 4, 4: 4, 5: 8, 6: 8, 7: 2, 8: 2, 9: 4, 10: 4, 11: 4,
              12: 4, 15: 3, 16: 2, 17: 4, 18: 4, 19: 2, 20: 2}
    while index < count:
        tag = data[offset]
        offset += 1
        if tag == 1:
            size, = struct.unpack_from('>H', data, offset)
            offset += 2
            result.append(data[offset:offset + size])
            offset += size
        else:
            offset += widths[tag]
            if tag in (5, 6):
                index += 1
        index += 1
    return result


def verify_fixture_inputs(binary, source, lock):
    for name, data in [('binary', binary), ('source', source)]:
        if sha256(data) != lock[name + '_sha256']:
            raise ValueError('Public fixture ' + name + ' SHA-256 mismatch')
    import io
    with zipfile.ZipFile(io.BytesIO(source)) as archive:
        text = archive.read('io/netty/handler/ssl/SslUtils.java').decode()
        declaration = re.search(r'static final String PROBING_KEY = (.*?);', text, re.S)
        if not declaration:
            raise ValueError('Missing reviewed public fixture source symbol')
        literals = re.findall(r'"(?:[^"\\]|\\.)*"', declaration[1])
        fixture = ''.join(json.loads(value) for value in literals).encode()
    if len(fixture) != lock['constant_size'] or sha256(fixture) != lock['constant_sha256']:
        raise ValueError('Public fixture source constant mismatch')
    classes = ['io/netty/handler/ssl/' + name + '.class' for name in ('SslUtils', 'OpenSsl', 'JdkSslServerContext')]
    with zipfile.ZipFile(io.BytesIO(binary)) as archive:
        for name in classes:
            if fixture not in class_utf8(archive.read(name)):
                raise ValueError('Reviewed public fixture missing from exact binary class')
    proof = dict(lock, classification='public_upstream_tls_capability_test_fixture',
                 verified_binary_classes=classes, inputs_verified=True)
    return fixture, proof


def validate_fixture_proof(proof, lock, gradle_home):
    if not proof.get('inputs_verified') or proof.get('classification') != 'public_upstream_tls_capability_test_fixture':
        raise ValueError('Public fixture source/binary proof is missing')
    for key, value in lock.items():
        if proof.get(key) != value:
            raise ValueError('Public fixture proof mismatch: ' + key)
    group, name, version = lock['coordinate'].split(':')
    directory = Path(gradle_home) / 'caches/modules-2/files-2.1' / group / name / version
    files = list(directory.glob('*/' + name + '-' + version + '.jar'))
    if len(files) != 1 or sha256(files[0].read_bytes()) != lock['binary_sha256']:
        raise ValueError('Actual Gradle runtime binary does not match public fixture provenance')
