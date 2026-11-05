# 密钥包规范 `zyll-keybundle.json`（v1）

密钥包放在网盘库根目录。它本身是明文 JSON，但里面只有被加密的主密钥（MK）。
新设备或网页端拉取该文件，输入密码（或恢复密钥）即可在本地还原 MK。

## 1. 格式

```json
{
  "format": "zyll-keybundle",
  "version": 1,
  "vaultId": "0f8c2c7e-4a1b-4d0e-9a51-2f3b8f6a9c11",
  "slots": [
    {
      "type": "password",
      "kdf": { "alg": "argon2id", "v": 19, "m": 65536, "t": 3, "p": 1, "salt": "<base64 16B>" },
      "nonce": "<base64 12B>",
      "wrappedMk": "<base64 48B>"
    },
    {
      "type": "recovery",
      "kdf": { "alg": "hkdf-sha256", "salt": "<base64 16B>" },
      "nonce": "<base64 12B>",
      "wrappedMk": "<base64 48B>"
    }
  ]
}
```

- base64 为标准字母表、带 `=` 填充。
- `m` 单位为 KiB。默认 `m=65536（64 MiB）, t=3, p=1`；解析时接受任何 `m ≥ 8192, t ≥ 1, p = 1`。
- 未知字段必须忽略；未知 `type` 的槽位必须跳过。

## 2. 派生与包裹

| 槽位 | KEK 派生 |
| --- | --- |
| password | `Argon2id(password = UTF-8(NFC(密码)), salt, m, t, p, 输出 32 字节)` |
| recovery | `HKDF-SHA256(ikm = 恢复密钥 32 字节, salt, info = "zyll/v1/recovery-kek", 32 字节)` |

`wrappedMk = AES-256-GCM(KEK, nonce, MK, AAD)`，其中

```
AAD = UTF-8("zyll-keybundle/v1|" + vaultId + "|" + type)
```

GCM 校验失败即“密码（或恢复密钥）错误”。

## 3. 恢复密钥

- 32 字节随机数，以 RFC 4648 Base32（大写、无填充）显示，共 52 个字符，每 4 个字符一组用 `-` 分隔。
- 输入时忽略空格、`-` 和大小写。

## 4. 修改密码

只重写 `password` 槽位（新 salt、新 nonce），MK 不变，所有已上传数据无需重新加密。
写入前先把旧文件复制为 `zyll-keybundle.prev.json`。
