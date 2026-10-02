"""Classify PEM markers without returning private-key contents.

Only exact strings in a bounds-checked DEX string table can be format literals or
an independently verified public upstream capability-test fixture. Other key
markers, including malformed/incomplete payloads, remain audit failures.
"""
import bisect
import hashlib
import re
import struct

BEGIN = re.compile(rb'-----BEGIN ((?:[A-Z]+ )?PRIVATE KEY)-----')


def dex_strings(data):
    if not data.startswith(b'dex\n'):
        return []
    if len(data) < 112 or data[7] != 0 or not data[4:7].isdigit():
        raise ValueError('Invalid DEX header during credential scan')
    size, header, endian = struct.unpack_from('<III', data, 32)
    if size != len(data) or header != 112 or endian != 0x12345678:
        raise ValueError('Unsupported DEX bounds/byte order during credential scan')
    count, offset = struct.unpack_from('<II', data, 56)
    if offset < header or offset + count * 4 > size:
        raise ValueError('Invalid DEX string table during credential scan')
    strings = []
    for i in range(count):
        start = struct.unpack_from('<I', data, offset + i * 4)[0]
        if start < header or start >= size:
            raise ValueError('Invalid DEX string offset during credential scan')
        for width in range(5):
            if start >= size:
                raise ValueError('Truncated DEX string length during credential scan')
            byte = data[start]
            start += 1
            if not byte & 0x80:
                break
        else:
            raise ValueError('Invalid DEX string length during credential scan')
        end = data.find(b'\0', start)
        if end < 0:
            raise ValueError('Unterminated DEX string during credential scan')
        strings.append((start, end))
    strings.sort()
    if any(a[1] >= b[0] for a, b in zip(strings, strings[1:])):
        raise ValueError('Overlapping DEX strings during credential scan')
    return strings


def private_key_markers(data, trusted_fixture_sha=None):
    matches = list(BEGIN.finditer(data))
    if not matches:
        return []
    strings = dex_strings(data)
    starts = [start for start, _ in strings]
    records = []
    for match in matches:
        position = bisect.bisect_right(starts, match.start()) - 1
        owner = strings[position] if position >= 0 and match.end() <= strings[position][1] else None
        record = {'offset': match.start(), 'classification': 'unrecognized_private_key_material', 'accepted': False}
        if owner:
            value = data[owner[0]:owner[1]]
            record['dex_string_size'] = len(value)
            if value in (match.group(), match.group() + b'\n', match.group() + b'\r\n'):
                record.update(classification='header_only_format_literal', accepted=True)
            else:
                footer = b'-----END ' + match[1] + b'-----'
                end = data.find(footer, match.end(), owner[1])
                record['complete_pem'] = end >= 0
                # Match the entire actual string, not just a prefix before a
                # second unknown key/token. Never whitelist a class or DEX.
                if (end >= 0 and owner[0] == match.start() and end + len(footer) == owner[1]
                        and trusted_fixture_sha and hashlib.sha256(value).hexdigest() == trusted_fixture_sha):
                    record.update(classification='public_upstream_tls_capability_test_fixture', accepted=True,
                                  public_fixture_sha256=trusted_fixture_sha)
        records.append(record)
    return records


def credential_like_token(data):
    pattern = re.compile(rb"\b(?:sk-(?:proj-)?[A-Za-z0-9_-]{32,}|gh[pousr]_[A-Za-z0-9]{30,})\b")
    if pattern.search(data):
        return True
    # DEX ULEB string-length bytes may themselves be ASCII word characters,
    # hiding a leading word boundary in a raw scan. Scan actual strings too.
    return any(pattern.search(data[start:end]) for start, end in dex_strings(data))
