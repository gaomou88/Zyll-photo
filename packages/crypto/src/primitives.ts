import { argon2id as argon2idWasm } from "hash-wasm";

/** 所有解密/校验失败都抛这个异常。 */
export class ZyllCryptoError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "ZyllCryptoError";
  }
}

export const KEY_LEN = 32;
export const NONCE_LEN = 12;
export const TAG_LEN = 16;

/** Uint8Array 统一为以 ArrayBuffer 为底的副本，满足 Web Crypto 的 BufferSource 类型要求。 */
const buf = (b: Uint8Array): Uint8Array<ArrayBuffer> => new Uint8Array(b);

export const subtle = (): SubtleCrypto => globalThis.crypto.subtle;

export function randomBytes(n: number): Uint8Array {
  return globalThis.crypto.getRandomValues(new Uint8Array(n));
}

export async function importAesKey(raw: Uint8Array, extractable = false): Promise<CryptoKey> {
  if (raw.length !== KEY_LEN) throw new Error("AES-256 key must be 32 bytes");
  return subtle().importKey("raw", buf(raw), { name: "AES-GCM" }, extractable, ["encrypt", "decrypt"]);
}

export async function aesGcmEncrypt(key: CryptoKey, nonce: Uint8Array, plain: Uint8Array, aad: Uint8Array): Promise<Uint8Array> {
  if (nonce.length !== NONCE_LEN) throw new Error("GCM nonce must be 12 bytes");
  const ct = await subtle().encrypt({ name: "AES-GCM", iv: buf(nonce), additionalData: buf(aad), tagLength: 128 }, key, buf(plain));
  return new Uint8Array(ct);
}

export async function aesGcmDecrypt(key: CryptoKey, nonce: Uint8Array, cipherAndTag: Uint8Array, aad: Uint8Array): Promise<Uint8Array> {
  if (nonce.length !== NONCE_LEN) throw new Error("GCM nonce must be 12 bytes");
  if (cipherAndTag.length < TAG_LEN) throw new ZyllCryptoError("ciphertext too short");
  try {
    const pt = await subtle().decrypt({ name: "AES-GCM", iv: buf(nonce), additionalData: buf(aad), tagLength: 128 }, key, buf(cipherAndTag));
    return new Uint8Array(pt);
  } catch {
    throw new ZyllCryptoError("authentication failed");
  }
}

export async function sha256(...parts: Uint8Array[]): Promise<Uint8Array> {
  return new Uint8Array(await subtle().digest("SHA-256", buf(concat(...parts))));
}

/** HKDF-SHA256，返回原始字节。空 salt 按 RFC 5869 等价于 32 个 0 字节。 */
export async function hkdfSha256(ikm: Uint8Array, salt: Uint8Array, info: Uint8Array, length: number): Promise<Uint8Array> {
  const key = await subtle().importKey("raw", buf(ikm), "HKDF", false, ["deriveBits"]);
  const realSalt = salt.length === 0 ? new Uint8Array(32) : salt;
  const bits = await subtle().deriveBits({ name: "HKDF", hash: "SHA-256", salt: buf(realSalt), info: buf(info) }, key, length * 8);
  return new Uint8Array(bits);
}

/** Argon2id v1.3（RFC 9106），WASM 实现。memoryKiB 单位 KiB。 */
export async function argon2id(password: Uint8Array, salt: Uint8Array, memoryKiB: number, iterations: number, parallelism: number, length = KEY_LEN): Promise<Uint8Array> {
  return argon2idWasm({
    password,
    salt,
    memorySize: memoryKiB,
    iterations,
    parallelism,
    hashLength: length,
    outputType: "binary",
  });
}

export function concat(...parts: Uint8Array[]): Uint8Array {
  const out = new Uint8Array(parts.reduce((n, p) => n + p.length, 0));
  let off = 0;
  for (const p of parts) {
    out.set(p, off);
    off += p.length;
  }
  return out;
}

export function u64(v: number): Uint8Array {
  const out = new Uint8Array(8);
  new DataView(out.buffer).setBigUint64(0, BigInt(v));
  return out;
}

export function readU64(b: Uint8Array, off: number): number {
  const v = new DataView(b.buffer, b.byteOffset, b.byteLength).getBigUint64(off);
  if (v > BigInt(Number.MAX_SAFE_INTEGER)) throw new ZyllCryptoError("bad length");
  return Number(v);
}

export function toHex(b: Uint8Array): string {
  return Array.from(b, (x) => x.toString(16).padStart(2, "0")).join("");
}

export function fromHex(s: string): Uint8Array {
  if (s.length % 2 !== 0) throw new Error("odd hex length");
  const out = new Uint8Array(s.length / 2);
  for (let i = 0; i < out.length; i++) out[i] = parseInt(s.slice(2 * i, 2 * i + 2), 16);
  return out;
}

export function toBase64(b: Uint8Array): string {
  let s = "";
  for (const x of b) s += String.fromCharCode(x);
  return btoa(s);
}

export function fromBase64(s: string): Uint8Array {
  const bin = atob(s);
  return Uint8Array.from(bin, (c) => c.charCodeAt(0));
}

export const utf8 = (s: string): Uint8Array => new TextEncoder().encode(s);

export function bytesEqual(a: Uint8Array, b: Uint8Array): boolean {
  if (a.length !== b.length) return false;
  let d = 0;
  for (let i = 0; i < a.length; i++) d |= a[i]! ^ b[i]!;
  return d === 0;
}
