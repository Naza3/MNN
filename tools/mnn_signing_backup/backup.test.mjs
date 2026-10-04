import test, { before, after } from 'node:test';
import assert from 'node:assert/strict';
import { constants, createCipheriv, createHash, generateKeyPairSync, publicEncrypt, randomBytes } from 'node:crypto';
import { execFileSync, spawnSync } from 'node:child_process';
import { existsSync, mkdtempSync, readFileSync, rmSync, statSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const script = join(dirname(fileURLToPath(import.meta.url)), 'backup.mjs');
const alias = 'mnn-local-api-ci-test';
const sha = bytes => createHash('sha256').update(bytes).digest('hex');
let root, key, expected, otherKey, otherExpected, publicKey, privateKey, wrongPrivateKey, bundlePath;
let sequence = 0;
const file = name => join(root, `${++sequence}-${name}`);

function cli(operation, values) {
    const args = [script, operation, ...Object.entries(values).flatMap(([k, v]) => ['--' + k, v])];
    return spawnSync(process.execPath, args, { encoding: 'utf8', timeout: 35_000, maxBuffer: 64 * 1024 });
}
function encrypt(output, changes = {}) {
    return cli('encrypt', { keystore: key, recipient: publicKey, 'expected-sha256': expected, output, ...changes });
}
function decrypt(output, changes = {}) {
    return cli('decrypt', { bundle: bundlePath, 'private-key': privateKey, 'expected-sha256': expected, output, ...changes });
}
function successful(result) {
    assert.equal(result.error, undefined, 'CLI must complete within its time bound');
    assert.equal(result.status, 0, result.stderr);
    assert.equal(result.stderr, '');
    const summary = JSON.parse(result.stdout);
    assert.deepEqual(Object.keys(summary).sort(), ['certificate_sha256', 'operation', 'output']);
    return summary;
}
function rejected(result, output, reason) {
    assert.equal(result.error, undefined, 'CLI must complete within its time bound');
    assert.notEqual(result.status, 0);
    assert.equal(result.stdout, '');
    const error = JSON.parse(result.stderr);
    assert.deepEqual(Object.keys(error), ['error']);
    assert.match(error.error, /^[a-z0-9_]+$/); // No external diagnostics or secret-bearing values.
    if (reason) assert.equal(error.error, reason);
    assert.equal(existsSync(output), false, 'A rejected operation must not leave plaintext/output');
}
function createP12(path) {
    execFileSync('keytool', ['-genkeypair', '-noprompt', '-storetype', 'PKCS12', '-keystore', path,
        '-storepass', 'android', '-keypass', 'android', '-alias', alias, '-keyalg', 'RSA', '-keysize', '2048',
        '-validity', '2', '-dname', 'CN=Synthetic Backup Test'], { stdio: 'pipe', timeout: 30_000 });
    return execFileSync('keytool', ['-exportcert', '-storetype', 'PKCS12', '-keystore', path,
        '-storepass', 'android', '-alias', alias], { stdio: ['ignore', 'pipe', 'pipe'], timeout: 20_000 });
}
function changedBundle(field, change) {
    const bundle = JSON.parse(readFileSync(bundlePath, 'utf8'));
    bundle[field] = change(bundle[field]);
    const path = file('changed.json');
    writeFileSync(path, JSON.stringify(bundle), { mode: 0o600 });
    return path;
}
const flip = value => {
    const buffer = Buffer.from(value, 'base64'); buffer[0] ^= 1; return buffer.toString('base64');
};

before(() => {
    root = mkdtempSync(join(tmpdir(), 'mnn-backup-test-'));
    key = file('source.p12'); otherKey = file('other.p12');
    expected = sha(createP12(key)); otherExpected = sha(createP12(otherKey));
    const pair = generateKeyPairSync('rsa', { modulusLength: 3072 });
    const wrong = generateKeyPairSync('rsa', { modulusLength: 3072 });
    publicKey = file('recipient.pem'); privateKey = file('private.pem'); wrongPrivateKey = file('wrong-private.pem');
    writeFileSync(publicKey, pair.publicKey.export({ format: 'pem', type: 'spki' }), { mode: 0o600 });
    writeFileSync(privateKey, pair.privateKey.export({ format: 'pem', type: 'pkcs8' }), { mode: 0o600 });
    writeFileSync(wrongPrivateKey, wrong.privateKey.export({ format: 'pem', type: 'pkcs8' }), { mode: 0o600 });
    bundlePath = file('backup.json');
    successful(encrypt(bundlePath));
});
after(() => { if (root) rmSync(root, { recursive: true, force: true }); });

test('round trip preserves exact PKCS12 bytes and certificate with private output modes', () => {
    const output = file('roundtrip.p12'); const original = readFileSync(key);
    const summary = successful(decrypt(output));
    assert.equal(summary.certificate_sha256, expected);
    assert.deepEqual(readFileSync(output), original);
    assert.deepEqual(readFileSync(key), original);
    assert.equal(statSync(output).mode & 0o777, 0o600);
    assert.equal(statSync(bundlePath).mode & 0o777, 0o600);
    const cert = execFileSync('keytool', ['-exportcert', '-keystore', output, '-storetype', 'PKCS12',
        '-storepass', 'android', '-alias', alias], { stdio: ['ignore', 'pipe', 'pipe'], timeout: 20_000 });
    assert.equal(sha(cert), expected);
    const bundle = JSON.parse(readFileSync(bundlePath, 'utf8'));
    assert.equal(bundle.metadata.alias, alias);
    assert.equal(bundle.metadata.keystore_bytes, original.length);
    assert.equal(readFileSync(bundlePath, 'utf8').includes(original.toString('base64')), false);
});

test('encrypt refuses a mismatched certificate before creating a bundle', () => {
    const output = file('wrong-certificate.json');
    rejected(encrypt(output, { 'expected-sha256': otherExpected }), output, 'certificate_mismatch');
});
test('missing keystore cannot generate a replacement identity', () => {
    const output = file('missing.json'), missing = file('missing.p12');
    rejected(encrypt(output, { keystore: missing }), output, 'keystore_unavailable');
    assert.equal(existsSync(missing), false);
});
test('recipient must be a public key, not the private PEM', () => {
    const output = file('private-recipient.json');
    rejected(encrypt(output, { recipient: privateKey }), output, 'recipient_must_be_public_key_pem');
});
test('RSA recipient shorter than 3072 bits is rejected', () => {
    const pair = generateKeyPairSync('rsa', { modulusLength: 2048 });
    const recipient = file('small-public.pem'), output = file('small-rsa.json');
    writeFileSync(recipient, pair.publicKey.export({ format: 'pem', type: 'spki' }));
    rejected(encrypt(output, { recipient }), output, 'rsa_key_must_be_3072_to_8192_bits');
});
test('missing local recipient private key leaves no plaintext', () => {
    const output = file('missing-private.p12');
    rejected(decrypt(output, { 'private-key': file('absent.pem') }), output, 'private_key_unavailable');
});
test('wrong recipient private key leaves no plaintext', () => {
    const output = file('wrong-private.p12');
    rejected(decrypt(output, { 'private-key': wrongPrivateKey }), output, 'recipient_key_mismatch');
});
test('wrong expected certificate is rejected before decryption', () => {
    const output = file('wrong-expected.p12');
    rejected(decrypt(output, { 'expected-sha256': otherExpected }), output, 'invalid_bundle_metadata');
});

for (const field of ['ciphertext', 'tag', 'nonce', 'wrapped_key']) {
    test(`tampered ${field} is rejected without plaintext output`, () => {
        const output = file(field + '.p12');
        rejected(decrypt(output, { bundle: changedBundle(field, flip) }), output, 'bundle_authentication_failed');
    });
}
test('certificate metadata is authenticated even if the supplied expected pin is changed with it', () => {
    const output = file('metadata.p12');
    const bundle = changedBundle('metadata', meta => ({ ...meta, certificate_sha256: otherExpected }));
    rejected(decrypt(output, { bundle, 'expected-sha256': otherExpected }), output, 'bundle_authentication_failed');
});
test('strict schema rejects extra fields and incorrect types', () => {
    for (const [field, value] of [['version', '1'], ['extra', true]]) {
        const output = file('schema.p12');
        rejected(decrypt(output, { bundle: changedBundle(field, () => value) }), output, 'invalid_bundle_schema');
    }
});
test('oversized input is refused before parsing', () => {
    const oversized = file('oversized.json'), output = file('oversized.p12');
    writeFileSync(oversized, Buffer.alloc(256 * 1024 + 1));
    rejected(decrypt(output, { bundle: oversized }), output, 'bundle_unavailable');
});
test('a valid encrypted envelope containing another certificate is deleted after post-decrypt validation', () => {
    // Independent synthetic sender knows the public recipient key but must not be
    // able to make certificate metadata authorize a different decrypted identity.
    const bytes = readFileSync(otherKey), aesKey = randomBytes(32), nonce = randomBytes(12);
    const meta = { certificate_sha256: expected, alias, keystore_bytes: bytes.length,
        recipient_spki_sha256: JSON.parse(readFileSync(bundlePath, 'utf8')).metadata.recipient_spki_sha256 };
    const cipher = createCipheriv('aes-256-gcm', aesKey, nonce);
    cipher.setAAD(Buffer.from(JSON.stringify({ version: 1, metadata: meta })));
    const ciphertext = Buffer.concat([cipher.update(bytes), cipher.final()]);
    const wrapped = publicEncrypt({ key: readFileSync(publicKey), padding: constants.RSA_PKCS1_OAEP_PADDING,
        oaepHash: 'sha256' }, aesKey);
    const bundle = file('wrong-plaintext.json'), output = file('wrong-plaintext.p12');
    writeFileSync(bundle, JSON.stringify({ version: 1, metadata: meta, nonce: nonce.toString('base64'),
        wrapped_key: wrapped.toString('base64'), ciphertext: ciphertext.toString('base64'), tag: cipher.getAuthTag().toString('base64') }));
    rejected(decrypt(output, { bundle }), output, 'certificate_mismatch');
});
test('certificate-only keystore is refused despite matching public certificate', () => {
    const cert = file('public.der'), store = file('certificate-only.p12'), output = file('certificate-only.json');
    writeFileSync(cert, execFileSync('keytool', ['-exportcert', '-keystore', key, '-storepass', 'android',
        '-alias', alias], { stdio: ['ignore', 'pipe', 'pipe'], timeout: 20_000 }));
    execFileSync('keytool', ['-importcert', '-noprompt', '-file', cert, '-keystore', store, '-storetype', 'PKCS12',
        '-storepass', 'android', '-alias', alias], { stdio: 'pipe', timeout: 20_000 });
    rejected(encrypt(output, { keystore: store }), output, 'keystore_private_entry_required');
});
test('existing output is preserved and never overwritten', () => {
    const output = file('existing.p12'), sentinel = Buffer.from('Existing output remains untouched');
    writeFileSync(output, sentinel, { mode: 0o600 });
    const result = decrypt(output);
    assert.notEqual(result.status, 0);
    assert.equal(JSON.parse(result.stderr).error, 'output_already_exists');
    assert.deepEqual(readFileSync(output), sentinel);
});
