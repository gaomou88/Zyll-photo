package app.zyll.crypto.vectors

import app.zyll.crypto.Argon2Params
import app.zyll.crypto.BlobKind
import app.zyll.crypto.KeyBundle
import app.zyll.crypto.Keys
import app.zyll.crypto.Primitives
import app.zyll.crypto.RecoveryKey
import app.zyll.crypto.Zylc
import app.zyll.crypto.toHex
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * 生成 spec/test-vectors 下的 JSON 向量。所有“随机”输入都是由标签派生的固定字节，
 * 因此重复运行结果完全相同；TS 端必须逐字节复现这些输出。
 */
fun main(args: Array<String>) {
    val dir = File(args.firstOrNull() ?: "../spec/test-vectors").apply { mkdirs() }
    val pretty = Json { prettyPrint = true }
    fun write(name: String, obj: JsonObject) = File(dir, name).writeText(pretty.encodeToString(JsonObject.serializer(), obj) + "\n")

    write("primitives.json", primitives())
    write("zylc.json", zylc())
    write("keybundle.json", keybundle())
    println("vectors written to ${dir.absolutePath}")
}

/** 由标签确定性地生成 n 个字节（SHA-256 计数器模式），仅用于向量。 */
fun fixed(label: String, n: Int): ByteArray {
    val out = ByteArray(n)
    var i = 0
    var counter = 0
    while (i < n) {
        val block = Primitives.sha256("zyll-vector|$label|$counter".toByteArray())
        val m = minOf(block.size, n - i)
        block.copyInto(out, i, 0, m)
        i += m; counter++
    }
    return out
}

fun pattern(n: Int): ByteArray = ByteArray(n) { i -> ((i * 31 + 7) and 0xff).toByte() }

private fun primitives(): JsonObject = buildJsonObject {
    put("description", "HKDF-SHA256 与 Argon2id 原语向量")
    put("hkdf", buildJsonArray {
        // RFC 5869 Test Case 1
        add(buildJsonObject {
            put("name", "rfc5869-case1")
            put("ikm", "0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b")
            put("salt", "000102030405060708090a0b0c")
            put("info", "f0f1f2f3f4f5f6f7f8f9")
            put("length", 42)
            put("okm", Primitives.hkdfSha256(
                "0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b".hex(), "000102030405060708090a0b0c".hex(),
                "f0f1f2f3f4f5f6f7f8f9".hex(), 42).toHex())
        })
        val mk = fixed("mk", 32)
        add(buildJsonObject {
            put("name", "wrap-key-from-mk (empty salt)")
            put("ikm", mk.toHex()); put("salt", ""); put("info", "zyll/v1/wrap".toByteArray().toHex()); put("length", 32)
            put("okm", Keys.deriveWrapKey(mk).toHex())
        })
    })
    put("argon2id", buildJsonArray {
        for ((name, p) in listOf("min" to Argon2Params(8192, 1, 1), "default" to Argon2Params())) {
            val pw = KeyBundle.passwordBytes("correct horse 电池 staple")
            val salt = fixed("argon-salt-$name", 16)
            add(buildJsonObject {
                put("name", name)
                put("password", pw.toHex()); put("salt", salt.toHex())
                put("m", p.memoryKiB); put("t", p.iterations); put("p", p.parallelism)
                put("output", Primitives.argon2id(pw, salt, p.memoryKiB, p.iterations, p.parallelism).toHex())
            })
        }
    })
}

private fun zylc(): JsonObject {
    val kWrap = Keys.deriveWrapKey(fixed("mk", 32))
    data class Case(val name: String, val len: Int, val log2: Int, val kind: BlobKind)
    val cases = listOf(
        Case("empty", 0, 16, BlobKind.OTHER),
        Case("one-byte", 1, 16, BlobKind.THUMBNAIL),
        Case("small-thumbnail", 1000, 16, BlobKind.THUMBNAIL),
        Case("exact-one-chunk-4k", 4096, 12, BlobKind.PREVIEW),
        Case("one-chunk-plus-one-4k", 4097, 12, BlobKind.PREVIEW),
        Case("multi-chunk-4k", 10000, 12, BlobKind.ORIGINAL),
        Case("multi-chunk-64k", 140000, 16, BlobKind.ORIGINAL),
    )
    val good = mutableListOf<Pair<Case, ByteArray>>()
    return buildJsonObject {
        put("description", "ZYLC v1 容器向量；plaintext 为 (i*31+7)&0xff 序列，只给长度")
        put("kWrap", kWrap.toHex())
        put("cases", buildJsonArray {
            for (c in cases) {
                val blobId = fixed("blob-${c.name}", 16)
                val dek = fixed("dek-${c.name}", 32)
                val nonce = fixed("wrapnonce-${c.name}", 12)
                val ct = Zylc.encryptWith(pattern(c.len), c.kind, kWrap, blobId, dek, nonce, c.log2)
                good += c to ct
                add(buildJsonObject {
                    put("name", c.name); put("plainLen", c.len); put("chunkLog2", c.log2); put("kind", c.kind.code)
                    put("blobId", blobId.toHex()); put("dek", dek.toHex()); put("wrapNonce", nonce.toHex())
                    put("ciphertextSha256", Primitives.sha256(ct).toHex())
                    if (ct.size <= 20000) put("ciphertext", ct.toHex())
                })
            }
        })
        // 反例：都必须解密失败
        val base = good.first { it.first.name == "multi-chunk-4k" }.second
        val chunk = 4096 + 16
        put("negative", buildJsonArray {
            fun neg(name: String, data: ByteArray, key: ByteArray = kWrap) = add(buildJsonObject {
                put("name", name); put("ciphertext", data.toHex())
                if (!key.contentEquals(kWrap)) put("kWrap", key.toHex())
            })
            neg("flip-header-kind", base.copyOf().also { it[7] = (it[7].toInt() xor 1).toByte() })
            neg("flip-plainlen", base.copyOf().also { it[91] = (it[91].toInt() xor 1).toByte() })
            neg("flip-chunk0-byte", base.copyOf().also { it[100] = (it[100].toInt() xor 0x80).toByte() })
            neg("truncate-last-chunk", base.copyOf(base.size - 1))
            neg("drop-last-chunk", base.copyOf(92 + 2 * chunk))
            neg("append-byte", base + byteArrayOf(0))
            neg("swap-chunk0-chunk1", base.copyOf().also {
                val c0 = base.copyOfRange(92, 92 + chunk); val c1 = base.copyOfRange(92 + chunk, 92 + 2 * chunk)
                c1.copyInto(it, 92); c0.copyInto(it, 92 + chunk)
            })
            neg("wrong-kwrap", base, fixed("other-key", 32))
            neg("bad-magic", base.copyOf().also { it[0] = 'X'.code.toByte() })
        })
    }
}

private fun keybundle(): JsonObject = buildJsonObject {
    put("description", "密钥包向量。createInputs 用于逐字节复现；unlock 用于解锁测试")
    put("bundles", buildJsonArray {
        for ((name, p) in listOf("min-params" to Argon2Params(8192, 1, 1), "default-params" to Argon2Params())) {
            val vaultId = "00000000-0000-4000-8000-0000000000${if (name == "min-params") "01" else "02"}"
            val mk = fixed("kb-mk-$name", 32)
            val rk = fixed("kb-rk-$name", 32)
            val pw = "correct horse 电池 staple"
            val i = mapOf(
                "pwSalt" to fixed("kb-pwsalt-$name", 16), "pwNonce" to fixed("kb-pwnonce-$name", 12),
                "rcSalt" to fixed("kb-rcsalt-$name", 16), "rcNonce" to fixed("kb-rcnonce-$name", 12),
            )
            val file = KeyBundle.createWith(vaultId, mk, pw, rk, p, i["pwSalt"]!!, i["pwNonce"]!!, i["rcSalt"]!!, i["rcNonce"]!!)
            add(buildJsonObject {
                put("name", name)
                put("password", pw)
                put("recoveryKey", RecoveryKey.format(rk))
                put("masterKey", mk.toHex())
                put("wrapKey", Keys.deriveWrapKey(mk).toHex())
                put("createInputs", buildJsonObject {
                    put("vaultId", vaultId); put("m", p.memoryKiB); put("t", p.iterations); put("p", p.parallelism)
                    i.forEach { (k, v) -> put(k, v.toHex()) }
                })
                put("bundle", Json.parseToJsonElement(KeyBundle.encode(file)))
            })
        }
    })
}

private fun String.hex(): ByteArray = ByteArray(length / 2) { substring(2 * it, 2 * it + 2).toInt(16).toByte() }
