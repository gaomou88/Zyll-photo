import { ZyllCryptoError, aesGcmDecrypt, aesGcmEncrypt, concat, hkdfSha256, importAesKey, randomBytes, sha256, subtle, toHex, utf8 } from "./primitives";

/** 共享相册：身份密钥、相册钥匙封装、清单签名。见 spec/sharing.md。 */

const buf = (b: Uint8Array): Uint8Array<ArrayBuffer> => new Uint8Array(b);

// PKCS#8 前缀（RFC 8410），后接 32 字节原始私钥
const X25519_PKCS8 = Uint8Array.from([0x30, 0x2e, 0x02, 0x01, 0x00, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x6e, 0x04, 0x22, 0x04, 0x20]);
const ED25519_PKCS8 = Uint8Array.from([0x30, 0x2e, 0x02, 0x01, 0x00, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x04, 0x22, 0x04, 0x20]);

function b64urlToBytes(s: string): Uint8Array {
  const b = atob(s.replace(/-/g, "+").replace(/_/g, "/") + "===".slice((s.length + 3) % 4));
  return Uint8Array.from(b, (c) => c.charCodeAt(0));
}

async function importPriv(alg: "X25519" | "Ed25519", raw: Uint8Array): Promise<CryptoKey> {
  if (raw.length !== 32) throw new Error(`${alg} private key must be 32 bytes`);
  const prefix = alg === "X25519" ? X25519_PKCS8 : ED25519_PKCS8;
  return subtle().importKey("pkcs8", buf(concat(prefix, raw)), { name: alg }, true, alg === "X25519" ? ["deriveBits"] : ["sign"]);
}

async function publicOf(alg: "X25519" | "Ed25519", priv: Uint8Array): Promise<Uint8Array> {
  const jwk = await subtle().exportKey("jwk", await importPriv(alg, priv));
  return b64urlToBytes(jwk.x!);
}

export const x25519Public = (priv: Uint8Array) => publicOf("X25519", priv);
export const ed25519Public = (priv: Uint8Array) => publicOf("Ed25519", priv);

export const fingerprint = (xPub: Uint8Array, edPub: Uint8Array) => sha256(utf8("zyll-share-id/v1"), xPub, edPub);

const B32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

/** 给人核对的安全码：指纹前 10 字节的 Base32，每 4 个字符一组。 */
export function safetyCode(fp: Uint8Array): string {
  let out = "";
  let buffer = 0;
  let bits = 0;
  for (const b of fp.subarray(0, 10)) {
    buffer = ((buffer << 8) | b) & 0xffff;
    bits += 8;
    while (bits >= 5) {
      bits -= 5;
      out += B32[(buffer >> bits) & 31];
    }
  }
  return out.match(/.{1,4}/g)!.join(" ");
}

export interface ShareIdentity {
  xPriv: Uint8Array;
  edPriv: Uint8Array;
  xPub: Uint8Array;
  edPub: Uint8Array;
  fingerprint: Uint8Array;
  fpHex: string;
}

export async function identityFrom(xPriv: Uint8Array, edPriv: Uint8Array): Promise<ShareIdentity> {
  const xPub = await x25519Public(xPriv);
  const edPub = await ed25519Public(edPriv);
  const fp = await fingerprint(xPub, edPub);
  return { xPriv, edPriv, xPub, edPub, fingerprint: fp, fpHex: toHex(fp) };
}

export const generateIdentity = () => identityFrom(randomBytes(32), randomBytes(32));

/** 相册包裹密钥：用法同 K_wrap，返回原始字节。 */
export function albumWrapKeyBytes(albumKey: Uint8Array, albumId: string): Promise<Uint8Array> {
  if (albumKey.length !== 32) throw new Error("album key must be 32 bytes");
  return hkdfSha256(albumKey, new Uint8Array(0), utf8(`zyll/v1/album-wrap|${albumId}`), 32);
}

export const albumWrapKey = async (albumKey: Uint8Array, albumId: string) => importAesKey(await albumWrapKeyBytes(albumKey, albumId));

async function x25519(priv: Uint8Array, pub: Uint8Array): Promise<Uint8Array> {
  if (pub.length !== 32) throw new ZyllCryptoError("bad public key");
  let ss: Uint8Array;
  try {
    const pk = await subtle().importKey("raw", buf(pub), { name: "X25519" }, false, []);
    ss = new Uint8Array(await subtle().deriveBits({ name: "X25519", public: pk }, await importPriv("X25519", priv), 256));
  } catch {
    throw new ZyllCryptoError("bad public key");
  }
  if (ss.every((b) => b === 0)) throw new ZyllCryptoError("bad public key");
  return ss;
}

const sealAad = (albumId: string, epoch: number, fpHex: string) => utf8(`zyll-share/v1|${albumId}|${epoch}|${fpHex}`);
const sealKey = async (ss: Uint8Array, ephPub: Uint8Array, recipientXPub: Uint8Array) =>
  importAesKey(await hkdfSha256(ss, concat(ephPub, recipientXPub), utf8("zyll/v1/share-seal"), 32));

export interface SealedKey {
  epk: Uint8Array;
  nonce: Uint8Array;
  ct: Uint8Array;
}

/** 确定性版本，供测试向量使用。 */
export async function sealAlbumKeyWith(
  albumKey: Uint8Array, albumId: string, epoch: number, recipientXPub: Uint8Array, recipientFpHex: string,
  ephPriv: Uint8Array, nonce: Uint8Array,
): Promise<SealedKey> {
  if (albumKey.length !== 32) throw new Error("album key must be 32 bytes");
  const epk = await x25519Public(ephPriv);
  const k = await sealKey(await x25519(ephPriv, recipientXPub), epk, recipientXPub);
  return { epk, nonce, ct: await aesGcmEncrypt(k, nonce, albumKey, sealAad(albumId, epoch, recipientFpHex)) };
}

export const sealAlbumKey = (albumKey: Uint8Array, albumId: string, epoch: number, recipientXPub: Uint8Array, recipientFpHex: string) =>
  sealAlbumKeyWith(albumKey, albumId, epoch, recipientXPub, recipientFpHex, randomBytes(32), randomBytes(12));

export async function openAlbumKey(sealed: SealedKey, albumId: string, epoch: number, me: ShareIdentity): Promise<Uint8Array> {
  if (sealed.epk.length !== 32 || sealed.nonce.length !== 12) throw new ZyllCryptoError("bad sealed key");
  const k = await sealKey(await x25519(me.xPriv, sealed.epk), sealed.epk, me.xPub);
  return aesGcmDecrypt(k, sealed.nonce, sealed.ct, sealAad(albumId, epoch, me.fpHex));
}

const manifestMessage = (body: Uint8Array) => concat(utf8("zyll-shared-album/v1|"), body);

export async function signManifest(body: Uint8Array, edPriv: Uint8Array): Promise<Uint8Array> {
  return new Uint8Array(await subtle().sign({ name: "Ed25519" }, await importPriv("Ed25519", edPriv), buf(manifestMessage(body))));
}

export async function verifyManifest(body: Uint8Array, sig: Uint8Array, edPub: Uint8Array): Promise<boolean> {
  if (sig.length !== 64 || edPub.length !== 32) return false;
  try {
    const pk = await subtle().importKey("raw", buf(edPub), { name: "Ed25519" }, false, ["verify"]);
    return await subtle().verify({ name: "Ed25519" }, pk, buf(sig), buf(manifestMessage(body)));
  } catch {
    return false;
  }
}
