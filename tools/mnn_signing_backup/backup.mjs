#!/usr/bin/env node
// Version 1: AES-256-GCM, with its random key wrapped by RSA-OAEP-SHA256.
// Only a public recipient key belongs in a workflow. Decryption stays local.
import { constants as cryptoConstants, createCipheriv, createDecipheriv, createHash,
    createPrivateKey, createPublicKey, privateDecrypt, publicEncrypt, randomBytes } from 'node:crypto';
import { execFile } from 'node:child_process';
import { constants as fsConstants } from 'node:fs';
import { mkdtemp, open, rm, unlink } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { promisify } from 'node:util';

const run = promisify(execFile);
const ALIAS = 'mnn-local-api-ci-test';
const MAX_KEYSTORE = 64 * 1024;
const MAX_BUNDLE = 256 * 1024;
const MAX_PEM = 16 * 1024;
const HEX64 = /^[0-9a-f]{64}$/;
const sha256 = bytes => createHash('sha256').update(bytes).digest('hex');

class BackupError extends Error {
    constructor(reason) { super(reason); this.reason = reason; }
}
const fail = reason => { throw new BackupError(reason); };

async function boundedRead(path, maximum, reason) {
    let handle;
    try {
        handle = await open(path, fsConstants.O_RDONLY | fsConstants.O_NOFOLLOW);
        const stat = await handle.stat();
        if (!stat.isFile() || stat.size < 1 || stat.size > maximum) fail(reason);
        // Read at most maximum+1 bytes even if the file grows after stat().
        const buffer = Buffer.alloc(maximum + 1);
        let length = 0;
        while (length < buffer.length) {
            const { bytesRead } = await handle.read(buffer, length, buffer.length - length, null);
            if (!bytesRead) break;
            length += bytesRead;
        }
        if (length < 1 || length > maximum) { buffer.fill(0); fail(reason); }
        const result = Buffer.from(buffer.subarray(0, length));
        buffer.fill(0);
        return result;
    } catch (error) {
        if (error instanceof BackupError) throw error;
        fail(reason);
    } finally { if (handle) await handle.close(); }
}

async function writeExclusive(path, bytes) {
    let handle;
    try {
        handle = await open(path, fsConstants.O_WRONLY | fsConstants.O_CREAT | fsConstants.O_EXCL, 0o600);
    } catch (error) {
        fail(error.code === 'EEXIST' ? 'output_already_exists' : 'output_create_failed');
    }
    try {
        await handle.chmod(0o600);
        await handle.writeFile(bytes);
        await handle.sync();
    } catch {
        await handle.close();
        await unlink(path).catch(() => {});
        fail('output_write_failed');
    }
    await handle.close();
}

async function inspectKeystore(path, expected) {
    const common = ['-J-Duser.language=en', '-J-Duser.country=US', '-storetype', 'PKCS12',
        '-keystore', path, '-storepass', 'android', '-alias', ALIAS];
    try {
        const { stdout } = await run('keytool', ['-list', '-v', ...common],
            { encoding: 'utf8', timeout: 20_000, maxBuffer: 128 * 1024 });
        if (!/^Entry type: PrivateKeyEntry\s*$/m.test(stdout)
                || !/^Alias name: mnn-local-api-ci-test\s*$/m.test(stdout)) {
            fail('keystore_private_entry_required');
        }
        const exported = await run('keytool', ['-exportcert', ...common],
            { encoding: null, timeout: 20_000, maxBuffer: 128 * 1024 });
        if (!exported.stdout.length || sha256(exported.stdout) !== expected) {
            fail('certificate_mismatch');
        }
    } catch (error) {
        if (error instanceof BackupError) throw error;
        // Never forward keytool diagnostics, file contents or secret-bearing errors.
        fail('keystore_validation_failed');
    }
}

function checkedRsa(key) {
    const bits = key.asymmetricKeyDetails?.modulusLength;
    if (key.asymmetricKeyType !== 'rsa' || !Number.isInteger(bits) || bits < 3072 || bits > 8192) {
        fail('rsa_key_must_be_3072_to_8192_bits');
    }
    return key;
}
const spki = key => createPublicKey(key).export({ type: 'spki', format: 'der' });
const publicSpki = key => key.export({ type: 'spki', format: 'der' });

async function recipientKey(path) {
    const bytes = await boundedRead(path, MAX_PEM, 'recipient_key_unavailable');
    try {
        const pem = bytes.toString('utf8');
        if (!/^\s*-----BEGIN PUBLIC KEY-----\r?\n[A-Za-z0-9+/=\r\n]+-----END PUBLIC KEY-----\s*$/.test(pem)) {
            fail('recipient_must_be_public_key_pem');
        }
        return checkedRsa(createPublicKey(pem));
    } catch (error) {
        if (error instanceof BackupError) throw error;
        fail('invalid_recipient_public_key');
    } finally { bytes.fill(0); }
}

async function localPrivateKey(path) {
    const bytes = await boundedRead(path, MAX_PEM, 'private_key_unavailable');
    try {
        const pem = bytes.toString('utf8');
        if (!/^\s*-----BEGIN (?:RSA )?PRIVATE KEY-----\r?\n[A-Za-z0-9+/=\r\n]+-----END (?:RSA )?PRIVATE KEY-----\s*$/.test(pem)) {
            fail('invalid_local_private_key');
        }
        const key = createPrivateKey(pem);
        if (key.type !== 'private') fail('invalid_local_private_key');
        return checkedRsa(key);
    } catch (error) {
        if (error instanceof BackupError) throw error;
        fail('invalid_local_private_key');
    } finally { bytes.fill(0); }
}

function exactKeys(value, keys) {
    return value !== null && typeof value === 'object' && !Array.isArray(value)
        && Object.keys(value).length === keys.length && keys.every(key => Object.hasOwn(value, key));
}
function metadata(certificate, length, recipient) {
    return { certificate_sha256: certificate, alias: ALIAS, keystore_bytes: length,
        recipient_spki_sha256: recipient };
}
const aad = meta => Buffer.from(JSON.stringify({ version: 1, metadata: meta }), 'utf8');

function decodeBase64(value, maximum, exact) {
    if (typeof value !== 'string' || value.length < 4 || value.length > 4 * Math.ceil(maximum / 3)
            || !/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(value)) {
        fail('invalid_bundle_encoding');
    }
    const bytes = Buffer.from(value, 'base64');
    if (!bytes.length || bytes.length > maximum || (exact !== undefined && bytes.length !== exact)
            || bytes.toString('base64') !== value) fail('invalid_bundle_encoding');
    return bytes;
}

async function encrypt(args) {
    const recipient = await recipientKey(args.recipient);
    const bytes = await boundedRead(args.keystore, MAX_KEYSTORE, 'keystore_unavailable');
    let temporary, aesKey;
    try {
        // Validate a private 0600 snapshot: exactly these bytes will be encrypted.
        temporary = await mkdtemp(join(tmpdir(), 'mnn-signing-backup-'));
        const snapshot = join(temporary, 'input.p12');
        await writeExclusive(snapshot, bytes);
        await inspectKeystore(snapshot, args.expected);
        const meta = metadata(args.expected, bytes.length, sha256(publicSpki(recipient)));
        aesKey = randomBytes(32);
        const nonce = randomBytes(12);
        const cipher = createCipheriv('aes-256-gcm', aesKey, nonce, { authTagLength: 16 });
        cipher.setAAD(aad(meta));
        const ciphertext = Buffer.concat([cipher.update(bytes), cipher.final()]);
        const wrapped = publicEncrypt({ key: recipient, padding: cryptoConstants.RSA_PKCS1_OAEP_PADDING,
            oaepHash: 'sha256' }, aesKey); // Node uses SHA-256 for OAEP and MGF1.
        const bundle = { version: 1, metadata: meta, nonce: nonce.toString('base64'),
            wrapped_key: wrapped.toString('base64'), ciphertext: ciphertext.toString('base64'),
            tag: cipher.getAuthTag().toString('base64') };
        await writeExclusive(args.output, Buffer.from(JSON.stringify(bundle) + '\n'));
        return { operation: 'encrypt', output: resolve(args.output), certificate_sha256: args.expected };
    } finally {
        bytes.fill(0);
        if (aesKey) aesKey.fill(0);
        if (temporary) await rm(temporary, { recursive: true, force: true });
    }
}

async function decrypt(args) {
    const encoded = await boundedRead(args.bundle, MAX_BUNDLE, 'bundle_unavailable');
    let bundle;
    try { bundle = JSON.parse(encoded.toString('utf8')); } catch { fail('invalid_bundle_json'); }
    if (!exactKeys(bundle, ['version', 'metadata', 'nonce', 'wrapped_key', 'ciphertext', 'tag'])
            || bundle.version !== 1 || !exactKeys(bundle.metadata,
                ['certificate_sha256', 'alias', 'keystore_bytes', 'recipient_spki_sha256'])) fail('invalid_bundle_schema');
    const meta = bundle.metadata;
    if (typeof meta.certificate_sha256 !== 'string' || !HEX64.test(meta.certificate_sha256)
            || meta.certificate_sha256 !== args.expected || meta.alias !== ALIAS
            || typeof meta.recipient_spki_sha256 !== 'string' || !HEX64.test(meta.recipient_spki_sha256)
            || !Number.isSafeInteger(meta.keystore_bytes) || meta.keystore_bytes < 1
            || meta.keystore_bytes > MAX_KEYSTORE) fail('invalid_bundle_metadata');
    const canonicalMeta = metadata(meta.certificate_sha256, meta.keystore_bytes, meta.recipient_spki_sha256);
    const key = await localPrivateKey(args.privateKey);
    if (sha256(spki(key)) !== meta.recipient_spki_sha256) fail('recipient_key_mismatch');
    const nonce = decodeBase64(bundle.nonce, 12, 12);
    const tag = decodeBase64(bundle.tag, 16, 16);
    const ciphertext = decodeBase64(bundle.ciphertext, MAX_KEYSTORE, meta.keystore_bytes);
    const wrapped = decodeBase64(bundle.wrapped_key, 1024, Math.ceil(key.asymmetricKeyDetails.modulusLength / 8));
    let aesKey, plaintext, partial, created = false;
    try {
        try {
            aesKey = privateDecrypt({ key, padding: cryptoConstants.RSA_PKCS1_OAEP_PADDING,
                oaepHash: 'sha256' }, wrapped);
            if (aesKey.length !== 32) fail('bundle_authentication_failed');
            const decipher = createDecipheriv('aes-256-gcm', aesKey, nonce, { authTagLength: 16 });
            decipher.setAAD(aad(canonicalMeta));
            decipher.setAuthTag(tag);
            partial = decipher.update(ciphertext);
            plaintext = Buffer.concat([partial, decipher.final()]);
        } catch { fail('bundle_authentication_failed'); }
        if (plaintext.length !== meta.keystore_bytes) fail('invalid_plaintext_size');
        await writeExclusive(args.output, plaintext);
        created = true;
        await inspectKeystore(args.output, args.expected);
        return { operation: 'decrypt', output: resolve(args.output), certificate_sha256: args.expected };
    } catch (error) {
        if (created) await unlink(args.output).catch(() => {});
        throw error;
    } finally {
        if (aesKey) aesKey.fill(0);
        if (partial) partial.fill(0);
        if (plaintext) plaintext.fill(0);
    }
}

function parseArgs(argv) {
    const [operation, ...rest] = argv;
    const names = operation === 'encrypt' ? ['keystore', 'recipient', 'expected-sha256', 'output']
        : operation === 'decrypt' ? ['bundle', 'private-key', 'expected-sha256', 'output'] : [];
    if (!names.length || rest.length !== 8) fail('invalid_arguments');
    const values = {};
    for (let i = 0; i < rest.length; i += 2) {
        const name = rest[i].slice(2);
        if (!rest[i].startsWith('--') || !names.includes(name) || Object.hasOwn(values, name)
                || !rest[i + 1] || rest[i + 1].includes('\0')) fail('invalid_arguments');
        values[name] = rest[i + 1];
    }
    const expected = values['expected-sha256']?.toLowerCase();
    if (!expected || !HEX64.test(expected)) fail('invalid_expected_certificate');
    return { ...values, operation, expected, privateKey: values['private-key'] };
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
    try {
        const args = parseArgs(process.argv.slice(2));
        console.log(JSON.stringify(await (args.operation === 'encrypt' ? encrypt(args) : decrypt(args))));
    } catch (error) {
        console.error(JSON.stringify({ error: error instanceof BackupError ? error.reason : 'operation_failed' }));
        process.exitCode = 1;
    }
}
