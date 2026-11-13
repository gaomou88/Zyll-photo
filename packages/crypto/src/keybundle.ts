import {
  ZyllCryptoError, aesGcmDecrypt, aesGcmEncrypt, argon2id, bytesEqual, fromBase64, hkdfSha256, importAesKey,
  randomBytes, subtle, toBase64, utf8,
} from "./primitives";

/** 密钥包 v1，见 spec/keybundle.md。 */
export const FORMAT = "zyll-keybundle";

export interface KdfParams {
  alg: string;
  v?: number;
  m?: number;
  t?: number;
  p?: number;
  salt: string;
}

export interface KeySlot {
  type: string;
  kdf: KdfParams;
  nonce: string;
  wrappedMk: string;
}

export interface KeyBundleFile {
  format: string;
  version: number;
  vaultId: string;
  slots: KeySlot[];
  /** 修改先后（毫秒时间，单调递增）。旧版本没有，按 0 算。见 spec/keybundle.md §5。 */
  rev?: number;
  /** HMAC-SHA256(HKDF(K_wrap, "zyll/v1/keybundle-mac"), macInput)，证明是手里有这套密钥的设备写的。 */
  mac?: string;
}

export interface Argon2Params {
  memoryKiB: number;
  iterations: number;
  parallelism: number;
}

export const DEFAULT_ARGON2: Argon2Params = { memoryKiB: 65536, iterations: 3, parallelism: 1 };

function checkParams(p: Argon2Params) {
  // 上限防止被改过的密钥包让解锁时内存耗尽（和 Android 端一致：m ≤ 1 GiB、t ≤ 10）
  if (!(p.memoryKiB >= 8192 && p.memoryKiB <= 1048576 && p.iterations >= 1 && p.iterations <= 10 && p.parallelism === 1)) throw new ZyllCryptoError("bad kdf params");
}

const aad = (vaultId: string, type: string) => utf8(`zyll-keybundle/v1|${vaultId}|${type}`);

export const passwordBytes = (password: string) => utf8(password.normalize("NFC"));

/** 解锁后的库：MK 只以不可导出的 CryptoKey 形式保留。 */
export interface UnlockedVault {
  vaultId: string;
  /** 包裹每个文件 DEK 的子密钥（不可导出）。 */
  wrapKey: CryptoKey;
  /** 核对密钥包 mac 用的 HMAC 密钥（不可导出，只能验证），见 [verifyWithKey]。 */
  macKey: CryptoKey;
}

/** 从 MK 原始字节派生 K_wrap 的原始字节（仅测试与 Android 互验用）。 */
export const deriveWrapKeyBytes = (mk: Uint8Array) => hkdfSha256(mk, new Uint8Array(0), utf8("zyll/v1/wrap"), 32);

/** 从 MK 派生不可导出的 K_wrap，并尽快清零 MK 原始字节。 */
export async function vaultFromMasterKey(vaultId: string, mk: Uint8Array): Promise<UnlockedVault> {
  const hkdf = await subtle().importKey("raw", new Uint8Array(mk), "HKDF", false, ["deriveKey"]);
  const wrapKey = await subtle().deriveKey(
    { name: "HKDF", hash: "SHA-256", salt: new Uint8Array(32), info: new Uint8Array(utf8("zyll/v1/wrap")) },
    hkdf,
    { name: "AES-GCM", length: 256 },
    false,
    ["encrypt", "decrypt"],
  );
  const macKey = await macKeyFromMasterKey(mk);
  mk.fill(0);
  return { vaultId, wrapKey, macKey };
}

async function recoveryKek(recoveryKey: Uint8Array, salt: Uint8Array): Promise<CryptoKey> {
  if (recoveryKey.length !== 32) throw new ZyllCryptoError("bad recovery key");
  return importAesKey(await hkdfSha256(recoveryKey, salt, utf8("zyll/v1/recovery-kek"), 32));
}

export async function passwordSlot(vaultId: string, mk: Uint8Array, password: string, params: Argon2Params, salt: Uint8Array, nonce: Uint8Array): Promise<KeySlot> {
  checkParams(params);
  const kek = await importAesKey(await argon2id(passwordBytes(password), salt, params.memoryKiB, params.iterations, params.parallelism));
  return {
    type: "password",
    kdf: { alg: "argon2id", v: 19, m: params.memoryKiB, t: params.iterations, p: params.parallelism, salt: toBase64(salt) },
    nonce: toBase64(nonce),
    wrappedMk: toBase64(await aesGcmEncrypt(kek, nonce, mk, aad(vaultId, "password"))),
  };
}

export async function recoverySlot(vaultId: string, mk: Uint8Array, recoveryKey: Uint8Array, salt: Uint8Array, nonce: Uint8Array): Promise<KeySlot> {
  const kek = await recoveryKek(recoveryKey, salt);
  return {
    type: "recovery",
    kdf: { alg: "hkdf-sha256", salt: toBase64(salt) },
    nonce: toBase64(nonce),
    wrappedMk: toBase64(await aesGcmEncrypt(kek, nonce, mk, aad(vaultId, "recovery"))),
  };
}

export interface CreateInputs {
  vaultId: string;
  masterKey: Uint8Array;
  recoveryKey: Uint8Array;
  pwSalt: Uint8Array;
  pwNonce: Uint8Array;
  rcSalt: Uint8Array;
  rcNonce: Uint8Array;
}

/** 确定性创建，供测试向量使用。 */
export async function createWith(i: CreateInputs, password: string, params: Argon2Params): Promise<KeyBundleFile> {
  return {
    format: FORMAT,
    version: 1,
    vaultId: i.vaultId,
    slots: [
      await passwordSlot(i.vaultId, i.masterKey, password, params, i.pwSalt, i.pwNonce),
      await recoverySlot(i.vaultId, i.masterKey, i.recoveryKey, i.rcSalt, i.rcNonce),
    ],
  };
}

export interface Created {
  json: string;
  vault: UnlockedVault;
  /** 展示给用户抄写的恢复密钥。 */
  recoveryKey: string;
}

/** 首次使用：本地生成 MK 与恢复密钥。 */
export async function create(password: string, params: Argon2Params = DEFAULT_ARGON2): Promise<Created> {
  const inputs: CreateInputs = {
    vaultId: globalThis.crypto.randomUUID(),
    masterKey: randomBytes(32),
    recoveryKey: randomBytes(32),
    pwSalt: randomBytes(16), pwNonce: randomBytes(12),
    rcSalt: randomBytes(16), rcNonce: randomBytes(12),
  };
  const file = await createWith(inputs, password, params);
  const recoveryKey = formatRecoveryKey(inputs.recoveryKey);
  inputs.recoveryKey.fill(0);
  return { json: JSON.stringify(file, null, 2), vault: await vaultFromMasterKey(file.vaultId, inputs.masterKey), recoveryKey };
}

export function parse(text: string): KeyBundleFile {
  let f: KeyBundleFile;
  try {
    f = JSON.parse(text);
  } catch {
    throw new ZyllCryptoError("malformed keybundle");
  }
  if (f?.format !== FORMAT || f.version !== 1 || typeof f.vaultId !== "string" || !Array.isArray(f.slots)) {
    throw new ZyllCryptoError("unsupported keybundle");
  }
  return f;
}

/** 返回 MK 原始字节；调用方应尽快交给 vaultFromMasterKey（会清零）。 */
export async function unwrapWithPassword(text: string, password: string): Promise<{ vaultId: string; masterKey: Uint8Array }> {
  const f = parse(text);
  const slot = f.slots.find((s) => s.type === "password");
  if (!slot) throw new ZyllCryptoError("no password slot");
  const k = slot.kdf;
  if (k.alg !== "argon2id" || k.v !== 19) throw new ZyllCryptoError("unsupported kdf");
  const params = { memoryKiB: k.m ?? 0, iterations: k.t ?? 0, parallelism: k.p ?? 0 };
  checkParams(params);
  const kek = await importAesKey(await argon2id(passwordBytes(password), fromBase64(k.salt), params.memoryKiB, params.iterations, params.parallelism));
  const masterKey = await aesGcmDecrypt(kek, fromBase64(slot.nonce), fromBase64(slot.wrappedMk), aad(f.vaultId, "password"));
  return { vaultId: f.vaultId, masterKey };
}

export async function unwrapWithRecoveryKey(text: string, recoveryKeyDisplay: string): Promise<{ vaultId: string; masterKey: Uint8Array }> {
  const f = parse(text);
  const slot = f.slots.find((s) => s.type === "recovery");
  if (!slot) throw new ZyllCryptoError("no recovery slot");
  if (slot.kdf.alg !== "hkdf-sha256") throw new ZyllCryptoError("unsupported kdf");
  const kek = await recoveryKek(parseRecoveryKey(recoveryKeyDisplay), fromBase64(slot.kdf.salt));
  const masterKey = await aesGcmDecrypt(kek, fromBase64(slot.nonce), fromBase64(slot.wrappedMk), aad(f.vaultId, "recovery"));
  return { vaultId: f.vaultId, masterKey };
}

export async function unlockWithPassword(text: string, password: string): Promise<UnlockedVault> {
  const r = await unwrapWithPassword(text, password);
  return vaultFromMasterKey(r.vaultId, r.masterKey);
}

export async function unlockWithRecoveryKey(text: string, recoveryKey: string): Promise<UnlockedVault> {
  const r = await unwrapWithRecoveryKey(text, recoveryKey);
  return vaultFromMasterKey(r.vaultId, r.masterKey);
}

const B32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

/** RFC 4648 Base32，大写无填充，每 4 字符一组。 */
export function formatRecoveryKey(key: Uint8Array): string {
  let out = "";
  let buffer = 0;
  let bits = 0;
  for (const b of key) {
    buffer = ((buffer << 8) | b) & 0xffff;
    bits += 8;
    while (bits >= 5) {
      bits -= 5;
      out += B32[(buffer >> bits) & 31];
    }
  }
  if (bits > 0) out += B32[(buffer << (5 - bits)) & 31];
  return out.match(/.{1,4}/g)!.join("-");
}

export function parseRecoveryKey(text: string): Uint8Array {
  const clean = text.toUpperCase().replace(/[\s-]/g, "");
  const out: number[] = [];
  let buffer = 0;
  let bits = 0;
  for (const c of clean) {
    const v = B32.indexOf(c);
    if (v < 0) throw new ZyllCryptoError("bad recovery key character");
    buffer = ((buffer << 5) | v) & 0xffff;
    bits += 5;
    if (bits >= 8) {
      bits -= 8;
      out.push((buffer >> bits) & 0xff);
    }
  }
  return new Uint8Array(out);
}

// ---------- rev 和 mac（spec/keybundle.md §5） ----------

export const revOf = (f: KeyBundleFile): number => (typeof f.rev === "number" && Number.isFinite(f.rev) ? f.rev : 0);

/** 参与 mac 的内容，逐项用换行隔开；没有的数字字段写空串。和 Android 端 KeyBundle.macInput 一致。 */
export function macInput(f: KeyBundleFile): Uint8Array {
  const num = (v: number | undefined) => (v === undefined || v === null ? "" : String(v));
  let s = `zyll-keybundle-mac/v1\n${f.vaultId}\n${revOf(f)}\n`;
  for (const slot of f.slots) {
    const k = slot.kdf;
    for (const x of [slot.type, k.alg, num(k.v), num(k.m), num(k.t), num(k.p), k.salt, slot.nonce, slot.wrappedMk]) s += x + "\n";
  }
  return utf8(s);
}

async function macOf(f: KeyBundleFile, wrapKeyBytes: Uint8Array): Promise<Uint8Array> {
  const raw = await hkdfSha256(wrapKeyBytes, new Uint8Array(0), utf8("zyll/v1/keybundle-mac"), 32);
  const key = await subtle().importKey("raw", new Uint8Array(raw), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  raw.fill(0);
  return new Uint8Array(await subtle().sign("HMAC", key, new Uint8Array(macInput(f))));
}

/** 附上 mac。[wrapKeyBytes] 是 K_wrap 的原始字节（只在 Worker 里、手里有 MK 时才有）。 */
export async function sign(f: KeyBundleFile, wrapKeyBytes: Uint8Array): Promise<KeyBundleFile> {
  return { ...f, mac: toBase64(await macOf({ ...f, mac: undefined }, wrapKeyBytes)) };
}

/** mac 对得上 [wrapKeyBytes]。没有 mac 的旧密钥包返回 false。 */
export async function verify(f: KeyBundleFile, wrapKeyBytes: Uint8Array): Promise<boolean> {
  if (!f.mac) return false;
  let got: Uint8Array;
  try {
    got = fromBase64(f.mac);
  } catch {
    return false;
  }
  return bytesEqual(got, await macOf(f, wrapKeyBytes));
}

/**
 * 从 MK 派生核对 mac 用的 HMAC 密钥（不可导出，只能验证）。解锁时和 K_wrap 一起派生，
 * 之后主线程不用 MK 也能判断网盘上的密钥包是不是有这套密钥的设备写的。
 */
export async function macKeyFromMasterKey(mk: Uint8Array): Promise<CryptoKey> {
  const wrap = await deriveWrapKeyBytes(mk);
  const raw = await hkdfSha256(wrap, new Uint8Array(0), utf8("zyll/v1/keybundle-mac"), 32);
  wrap.fill(0);
  const key = await subtle().importKey("raw", new Uint8Array(raw), { name: "HMAC", hash: "SHA-256" }, false, ["verify"]);
  raw.fill(0);
  return key;
}

/** 用 [macKeyFromMasterKey] 的密钥核对 mac。没有 mac 的旧密钥包返回 false。 */
export async function verifyWithKey(f: KeyBundleFile, macKey: CryptoKey): Promise<boolean> {
  if (!f.mac) return false;
  let got: Uint8Array;
  try {
    got = fromBase64(f.mac);
  } catch {
    return false;
  }
  return subtle().verify("HMAC", macKey, new Uint8Array(got), new Uint8Array(macInput(f)));
}

/**
 * 重新包裹主密钥：[newPassword] 不为空时换掉 password 槽位，[newRecoveryKey] 不为空时换掉 recovery 槽位，其余原样保留。
 * MK 不变，已上传的照片都不用重新加密。rev 取“现在”和 max(旧 rev, [minRev]) + 1 里较大的那个，再附上 mac。
 * 返回新的密钥包 JSON。只在 Worker 里调用（要用 MK 原始字节）。
 */
export async function rewrap(
  text: string, masterKey: Uint8Array, opts: { newPassword?: string; newRecoveryKey?: Uint8Array; minRev?: number; params?: Argon2Params; now?: number },
): Promise<string> {
  const f = parse(text);
  let slots = f.slots;
  const put = (s: KeySlot) => (slots.some((x) => x.type === s.type) ? slots.map((x) => (x.type === s.type ? s : x)) : [...slots, s]);
  if (opts.newPassword !== undefined) slots = put(await passwordSlot(f.vaultId, masterKey, opts.newPassword, opts.params ?? DEFAULT_ARGON2, randomBytes(16), randomBytes(12)));
  if (opts.newRecoveryKey) slots = put(await recoverySlot(f.vaultId, masterKey, opts.newRecoveryKey, randomBytes(16), randomBytes(12)));
  const rev = Math.max(opts.now ?? Date.now(), Math.max(revOf(f), opts.minRev ?? 0) + 1);
  const wrapKey = await deriveWrapKeyBytes(masterKey);
  try {
    return JSON.stringify(await sign({ ...f, slots, rev, mac: undefined }, wrapKey), null, 2);
  } finally {
    wrapKey.fill(0);
  }
}
