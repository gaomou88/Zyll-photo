# ZYLC v1 密文容器格式

本文件是 Android（Kotlin）与 Web（TypeScript）两端共同遵守的唯一规范。
任何改动都必须同时更新 `test-vectors/` 并让两端测试通过。

## 1. 原语

| 用途 | 算法 |
| --- | --- |
| 对称加密 | AES-256-GCM，12 字节 nonce，16 字节 tag |
| 子密钥派生 | HKDF-SHA256（RFC 5869） |
| 密码派生 | Argon2id v1.3（RFC 9106） |
| 头部摘要 | SHA-256 |

所有整数均为**大端序**。

## 2. 密钥层级

```
解锁密码 ──Argon2id(salt)──▶ KEK ──AES-GCM 解密──▶ MK（32 字节随机，永不更换）
恢复密钥 ──HKDF(salt, "zyll/v1/recovery-kek")──▶ KEK' ──▶ MK
MK ──HKDF(salt=空, info="zyll/v1/wrap")──▶ K_wrap（32 字节）
K_wrap ──AES-GCM──▶ 每个文件各自的 DEK（32 字节随机）
```

- HKDF 的空 salt 按 RFC 5869 等价于 32 个 0 字节。
- 每个容器（缩略图、预览图、原图、索引快照、日志）都使用**独立的随机 DEK**，
  因此分块 nonce 采用计数器也不会重复。

## 3. 容器布局

```
偏移  长度  字段
0     4     magic       ASCII "ZYLC"
4     1     version     0x01
5     1     alg         0x01 = AES-256-GCM 分块
6     1     chunkLog2   16（每块明文 65536 字节）；合法范围 12..24
7     1     kind        0=缩略图 1=预览图 2=原图 3=索引快照 4=同步日志 5=其他
8     16    blobId      随机 16 字节；其小写十六进制即网盘文件名
24    12    wrapNonce   随机
36    48    wrappedDek  AES-GCM(K_wrap, wrapNonce, DEK, AAD = 字节[0,24)) = 32 密文 + 16 tag
84    8     plainLen    明文总长度（u64）
92    ...   chunks
```

头部共 **92 字节**。

### 3.1 分块

- `chunkSize = 1 << chunkLog2`
- 块数 `n = max(1, ceil(plainLen / chunkSize))`；空明文也有 1 个空块（只含 tag），以便检测截断。
- 第 `i` 块明文 = `plain[i*chunkSize, min((i+1)*chunkSize, plainLen))`
- 第 `i` 块 nonce = `00 00 00 00 || u64(i)`（12 字节）
- 第 `i` 块 AAD = `SHA-256(header[0,92)) || u64(i) || u8(i == n-1 ? 1 : 0)`（41 字节）
- 第 `i` 块密文 = `AES-GCM(DEK, nonce, chunkPlain, AAD)`，即 `密文 || 16 字节 tag`
- 第 `i` 块起始偏移 = `92 + i * (chunkSize + 16)`

### 3.2 解密时必须检查

1. magic、version、alg 均匹配，chunkLog2 在 12..24 之间；
2. 文件总长度 = `92 + plainLen + 16 * n`，否则视为截断或拼接；
3. 每块 GCM 校验通过（AAD 中的“最后一块”标志防止截断与调换）。

任一失败都必须整体报错，**不得返回部分明文**。

## 4. 随机数

所有随机值（MK、DEK、salt、nonce、blobId、恢复密钥）都来自平台 CSPRNG：
Android 用 `SecureRandom`，Web 用 `crypto.getRandomValues`。
测试向量通过注入固定“随机数”来复现逐字节一致的输出。
