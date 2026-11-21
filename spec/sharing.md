# 共享相册规范（v1，测试分支）

Android（Kotlin）和网页版（TypeScript）共同遵守。改动时同时更新 `test-vectors/sharing.json`，两端测试都要通过。

## 0. 目标与原则

- 把某个相册共享给别人，对方用自己的 Zyll（安卓或网页版）打开。
- **主密钥（MK）和包裹密钥（K_wrap）永远不离开本人设备。** 分享出去的只有“相册钥匙”，它只能打开这一个相册。
- 照片密文不重新加密。只把每张照片的 DEK 用相册钥匙另存一份（写在加密的相册目录里）。
- 没有自建服务器。访问控制分两层：网盘权限决定“谁能读、谁能写这个文件夹”，签名决定“哪些成员、哪个目录是可信的”。
- 第一版只支持同一家网盘内共享（Google 分享给 Google 账号，OneDrive 分享给 Microsoft 账号）。

## 1. 原语（新增）

| 用途 | 算法 | 备注 |
| --- | --- | --- |
| 密钥协商 | X25519（RFC 7748） | WebCrypto 原生支持；Android 用 Bouncy Castle |
| 签名 | Ed25519（RFC 8032） | 同上 |
| 其余 | 沿用 crypto-format.md：HKDF-SHA256、AES-256-GCM、SHA-256 | |

公钥、私钥都是 32 字节原始字节，JSON 里用标准 base64（带 `=`）。

## 2. 身份密钥（每个库一套）

每个库第一次用到共享时生成：

- `xPriv/xPub`：X25519，用来接收相册钥匙；
- `edPriv/edPub`：Ed25519，用来给相册清单签名。

私钥保存在网盘 `vault/identity.zylc`（ZYLC，kind=5，用本库 K_wrap 加密），这样同一个库的其他手机和网页版解锁后都能拿到。明文是 JSON：

```json
{ "format": "zyll-identity", "version": 1, "x": "<xPriv b64>", "ed": "<edPriv b64>", "created": 1730000000000 }
```

公钥由私钥算出，不单独存。

### 2.1 指纹与安全码

```
fp       = SHA-256(UTF-8("zyll-share-id/v1") || xPub || edPub)
fpHex    = 小写十六进制(fp)                 # 程序内部用作成员 id
安全码    = fp 前 10 字节的 Base32（RFC 4648 大写无填充，16 个字符），每 4 个一组用空格分隔
```

双方当面或打电话核对安全码，防止网盘或聊天渠道里的人调包公钥。

### 2.2 分享名片

想接收共享的人把名片发给对方（二维码或链接）：

```json
{ "format": "zyll-share-card", "version": 1, "provider": "google|onedrive", "email": "a@example.com", "x": "<xPub b64>", "ed": "<edPub b64>" }
```

链接形式：`https://<网页版域名>/#card=<base64url(名片 JSON)>`，App 内扫码同样解析 `card=` 后的部分。

## 3. 相册钥匙

- `AK`：32 字节随机数，每个共享相册一把；撤销成员时整把换掉（`epoch + 1`）。
- 相册包裹密钥：`K_album = HKDF-SHA256(ikm = AK, salt = 空, info = UTF-8("zyll/v1/album-wrap|" + albumId), 32)`。
  它的用法和 K_wrap 完全一样：作为 ZYLC 的包裹密钥，用来加密相册目录和成员新上传的照片。

### 3.1 把相册钥匙封装给某个成员（sealed box）

```
eph        = 新随机 X25519 私钥；ephPub = 对应公钥
ss         = X25519(eph, 成员 xPub)                  # 全 0 结果必须拒绝
k          = HKDF-SHA256(ikm = ss, salt = ephPub || 成员 xPub, info = UTF-8("zyll/v1/share-seal"), 32)
nonce      = 12 字节随机
aad        = UTF-8("zyll-share/v1|" + albumId + "|" + epoch + "|" + 成员 fpHex)
ct         = AES-256-GCM(k, nonce, AK, aad)          # 32 + 16 字节
```

记作 `{ "epk": ephPub, "nonce": nonce, "ct": ct }`（都是 base64）。打开时用自己的 xPriv 重算 `ss`，GCM 校验失败即“不是给我的或被改过”。

