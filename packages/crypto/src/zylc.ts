import {
  KEY_LEN, NONCE_LEN, TAG_LEN, ZyllCryptoError, aesGcmDecrypt, aesGcmEncrypt, bytesEqual, concat,
  importAesKey, randomBytes, readU64, sha256, toHex, u64,
} from "./primitives";

/** ZYLC v1 密文容器，见 spec/crypto-format.md。 */
export const HEADER_LEN = 92;
export const VERSION = 1;
export const ALG_AES256GCM_CHUNKED = 1;
export const DEFAULT_CHUNK_LOG2 = 16;
const MAGIC = new Uint8Array([0x5a, 0x59, 0x4c, 0x43]); // "ZYLC"

export enum BlobKind {
  Thumbnail = 0,
  Preview = 1,
  Original = 2,
  IndexSnapshot = 3,
  Journal = 4,
  Other = 5,
}

export interface ZylcHeader {
  chunkLog2: number;
  kind: BlobKind;
  blobId: Uint8Array;
  wrapNonce: Uint8Array;
  wrappedDek: Uint8Array;
  plainLen: number;
}

export const chunkSize = (h: ZylcHeader) => 1 << h.chunkLog2;
export const chunkCount = (h: ZylcHeader) => Math.max(1, Math.ceil(h.plainLen / chunkSize(h)));
export const totalLen = (h: ZylcHeader) => HEADER_LEN + h.plainLen + TAG_LEN * chunkCount(h);
export const chunkOffset = (h: ZylcHeader, i: number) => HEADER_LEN + i * (chunkSize(h) + TAG_LEN);
export const blobIdHex = (h: ZylcHeader) => toHex(h.blobId);

export function chunkCipherLen(h: ZylcHeader, i: number): number {
  const plain = Math.max(0, Math.min(chunkSize(h), h.plainLen - i * chunkSize(h)));
  return plain + TAG_LEN;
}

export function encodeHeader(h: ZylcHeader): Uint8Array {
  const out = new Uint8Array(HEADER_LEN);
  out.set(MAGIC, 0);
  out[4] = VERSION;
  out[5] = ALG_AES256GCM_CHUNKED;
  out[6] = h.chunkLog2;
  out[7] = h.kind;
  out.set(h.blobId, 8);
  out.set(h.wrapNonce, 24);
  out.set(h.wrappedDek, 36);
  out.set(u64(h.plainLen), 84);
  return out;
}

export function decodeHeader(b: Uint8Array): ZylcHeader {
  if (b.length < HEADER_LEN) throw new ZyllCryptoError("header too short");
  if (!bytesEqual(b.subarray(0, 4), MAGIC)) throw new ZyllCryptoError("bad magic");
  if (b[4] !== VERSION) throw new ZyllCryptoError("unsupported version");
  if (b[5] !== ALG_AES256GCM_CHUNKED) throw new ZyllCryptoError("unsupported alg");
  const chunkLog2 = b[6]!;
  if (chunkLog2 < 12 || chunkLog2 > 24) throw new ZyllCryptoError("bad chunk size");
  const kind = b[7]!;
  if (kind > BlobKind.Other) throw new ZyllCryptoError("unknown blob kind");
  return {
    chunkLog2,
    kind,
    blobId: b.slice(8, 24),
    wrapNonce: b.slice(24, 36),
    wrappedDek: b.slice(36, 84),
    plainLen: readU64(b, 84),
  };
}

const chunkNonce = (i: number) => concat(new Uint8Array(4), u64(i));
const chunkAad = (headerHash: Uint8Array, i: number, last: boolean) =>
  concat(headerHash, u64(i), new Uint8Array([last ? 1 : 0]));

export async function encrypt(plain: Uint8Array, kind: BlobKind, kWrap: CryptoKey, chunkLog2 = DEFAULT_CHUNK_LOG2): Promise<Uint8Array> {
  return encryptWith(plain, kind, kWrap, randomBytes(16), randomBytes(KEY_LEN), randomBytes(NONCE_LEN), chunkLog2);
}

/** 确定性版本：所有“随机”输入显式给出，供测试向量使用。 */
export async function encryptWith(
  plain: Uint8Array, kind: BlobKind, kWrap: CryptoKey,
  blobId: Uint8Array, dek: Uint8Array, wrapNonce: Uint8Array, chunkLog2 = DEFAULT_CHUNK_LOG2,
): Promise<Uint8Array> {
  if (chunkLog2 < 12 || chunkLog2 > 24) throw new Error("bad chunkLog2");
  if (blobId.length !== 16 || dek.length !== KEY_LEN || wrapNonce.length !== NONCE_LEN) throw new Error("bad input sizes");
  const header: ZylcHeader = { chunkLog2, kind, blobId, wrapNonce, wrappedDek: new Uint8Array(48), plainLen: plain.length };
  const prefix = encodeHeader(header).subarray(0, 24);
  header.wrappedDek = await aesGcmEncrypt(kWrap, wrapNonce, dek, prefix);
  const headerBytes = encodeHeader(header);
  const headerHash = await sha256(headerBytes);
  const dekKey = await importAesKey(dek);

  const out = new Uint8Array(totalLen(header));
  out.set(headerBytes, 0);
  const n = chunkCount(header);
  const size = chunkSize(header);
  const jobs: Promise<void>[] = [];
  for (let i = 0; i < n; i++) {
    const part = plain.subarray(i * size, Math.min((i + 1) * size, plain.length));
    jobs.push(aesGcmEncrypt(dekKey, chunkNonce(i), part, chunkAad(headerHash, i, i === n - 1)).then((ct) => out.set(ct, chunkOffset(header, i))));
  }
  await Promise.all(jobs);
  return out;
}

/** 随机访问解密器：只需 92 字节头部即可解开 DEK，之后按块解密。 */
export class ZylcReader {
  private constructor(readonly header: ZylcHeader, private readonly dek: CryptoKey, private readonly headerHash: Uint8Array) {}

  static async open(headerBytes: Uint8Array, kWrap: CryptoKey): Promise<ZylcReader> {
    const dekRaw = await unwrapDek(headerBytes, kWrap);
    try {
      return await ZylcReader.openWithDek(headerBytes, dekRaw);
    } finally {
      dekRaw.fill(0);
    }
  }

  /** 共享相册成员用相册目录里给的 DEK 直接打开（见 spec/sharing.md 4.2）。 */
  static async openWithDek(headerBytes: Uint8Array, dekRaw: Uint8Array): Promise<ZylcReader> {
    if (dekRaw.length !== KEY_LEN) throw new ZyllCryptoError("bad dek");
    const h = decodeHeader(headerBytes);
    return new ZylcReader(h, await importAesKey(dekRaw), await sha256(headerBytes.subarray(0, HEADER_LEN)));
  }

  get chunkCount() { return chunkCount(this.header); }

  async decryptChunk(i: number, cipherAndTag: Uint8Array): Promise<Uint8Array> {
    const n = chunkCount(this.header);
    if (i < 0 || i >= n) throw new ZyllCryptoError("chunk out of range");
    if (cipherAndTag.length !== chunkCipherLen(this.header, i)) throw new ZyllCryptoError("chunk length mismatch");
    return aesGcmDecrypt(this.dek, chunkNonce(i), cipherAndTag, chunkAad(this.headerHash, i, i === n - 1));
  }
}

/** 主人取出某个文件的 DEK（只需前 92 字节），用来写进共享相册目录。 */
export async function unwrapDek(headerBytes: Uint8Array, kWrap: CryptoKey): Promise<Uint8Array> {
  const h = decodeHeader(headerBytes);
  return aesGcmDecrypt(kWrap, h.wrapNonce, h.wrappedDek, headerBytes.subarray(0, 24));
}

export async function decrypt(data: Uint8Array, kWrap: CryptoKey): Promise<Uint8Array> {
  return decryptWith(data, await ZylcReader.open(data.subarray(0, Math.min(HEADER_LEN, data.length)), kWrap));
}

export async function decryptWithDek(data: Uint8Array, dek: Uint8Array, expect: BlobKind): Promise<Uint8Array> {
  const reader = await ZylcReader.openWithDek(data.subarray(0, Math.min(HEADER_LEN, data.length)), dek);
  if (reader.header.kind !== expect) throw new ZyllCryptoError("unexpected blob kind");
  return decryptWith(data, reader);
}

async function decryptWith(data: Uint8Array, reader: ZylcReader): Promise<Uint8Array> {
  const h = reader.header;
  if (data.length !== totalLen(h)) throw new ZyllCryptoError("length mismatch");
  const out = new Uint8Array(h.plainLen);
  const size = chunkSize(h);
  const jobs: Promise<void>[] = [];
  for (let i = 0; i < reader.chunkCount; i++) {
    const off = chunkOffset(h, i);
    jobs.push(reader.decryptChunk(i, data.subarray(off, off + chunkCipherLen(h, i))).then((pt) => out.set(pt, i * size)));
  }
  await Promise.all(jobs);
  return out;
}
