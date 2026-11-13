import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";
import { ZyllCryptoError, fromBase64, fromHex, toBase64, toHex } from "../src/primitives";
import * as sh from "../src/sharing";
import * as zylc from "../src/zylc";

/** 读取 Kotlin 端生成的 sharing.json：两端必须逐字节一致。 */
const dir = fileURLToPath(new URL("../../../spec/test-vectors/", import.meta.url));
const v = JSON.parse(readFileSync(dir + "sharing.json", "utf8"));
const pattern = (n: number) => Uint8Array.from({ length: n }, (_, i) => (i * 31 + 7) & 0xff);
const id = (name: string) => sh.identityFrom(fromHex(v.identities[name].xPriv), fromHex(v.identities[name].edPriv));

describe("sharing", () => {
  for (const name of ["owner", "member"]) it(`identity ${name}`, async () => {
    const e = v.identities[name];
    const i = await id(name);
    expect(toHex(i.xPub)).toBe(e.xPub);
    expect(toHex(i.edPub)).toBe(e.edPub);
    expect(i.fpHex).toBe(e.fingerprint);
    expect(sh.safetyCode(i.fingerprint)).toBe(e.safetyCode);
  });

  it("seal + open album key", async () => {
    const member = await id("member");
    const ak = fromHex(v.albumKey);
    const s = v.seal;
    const again = await sh.sealAlbumKeyWith(ak, v.albumId, v.epoch, member.xPub, member.fpHex, fromHex(s.ephPriv), fromBase64(s.nonce));
    expect(toBase64(again.epk)).toBe(s.epk);
    expect(toBase64(again.ct)).toBe(s.ct);
    const sealed = { epk: fromBase64(s.epk), nonce: fromBase64(s.nonce), ct: fromBase64(s.ct) };
    expect(toHex(await sh.openAlbumKey(sealed, v.albumId, v.epoch, member))).toBe(v.albumKey);
    expect(toHex(await sh.albumWrapKeyBytes(ak, v.albumId))).toBe(v.albumWrapKey);
    await expect(sh.openAlbumKey(sealed, v.albumId, v.epoch, await id("owner"))).rejects.toBeInstanceOf(ZyllCryptoError);
    await expect(sh.openAlbumKey(sealed, v.albumId + "x", v.epoch, member)).rejects.toBeInstanceOf(ZyllCryptoError);
    await expect(sh.openAlbumKey(sealed, v.albumId, v.epoch + 1, member)).rejects.toBeInstanceOf(ZyllCryptoError);
    await expect(sh.openAlbumKey({ ...sealed, epk: new Uint8Array(32) }, v.albumId, v.epoch, member)).rejects.toBeInstanceOf(ZyllCryptoError);
  });

  it("manifest signature", async () => {
    const body = fromBase64(v.manifest.body);
    const sig = fromBase64(v.manifest.sig);
    const owner = await id("owner");
    expect(toBase64(await sh.signManifest(body, owner.edPriv))).toBe(v.manifest.sig);
    expect(await sh.verifyManifest(body, sig, owner.edPub)).toBe(true);
    expect(await sh.verifyManifest(body, sig, (await id("member")).edPub)).toBe(false);
    expect(await sh.verifyManifest(new Uint8Array([...body, 32]), sig, owner.edPub)).toBe(false);
  });

  it("member decrypts owner's photo with DEK", async () => {
    const p = v.photo;
    const ct = fromHex(p.ciphertext);
    const dek = fromHex(p.dek);
    expect(await zylc.decryptWithDek(ct, dek, zylc.BlobKind.Original)).toEqual(pattern(p.plainLen));
    const kWrap = await (await import("../src/primitives")).importAesKey(fromHex(p.kWrap));
    expect(toHex(await zylc.unwrapDek(ct.subarray(0, zylc.HEADER_LEN), kWrap))).toBe(p.dek);
    await expect(zylc.decryptWithDek(ct, dek, zylc.BlobKind.Thumbnail)).rejects.toBeInstanceOf(ZyllCryptoError);
    await expect(zylc.decryptWithDek(ct, new Uint8Array(32), zylc.BlobKind.Original)).rejects.toBeInstanceOf(ZyllCryptoError);
    const bad = ct.slice();
    bad[40]! ^= 1;
    await expect(zylc.decryptWithDek(bad, dek, zylc.BlobKind.Original)).rejects.toBeInstanceOf(ZyllCryptoError);
  });
});
