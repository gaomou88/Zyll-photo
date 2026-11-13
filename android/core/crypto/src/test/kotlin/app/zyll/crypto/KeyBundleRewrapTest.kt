package app.zyll.crypto

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File

/** 换恢复密钥、改密码（重新包裹主密钥）和密钥包的 rev / mac。 */
class KeyBundleRewrapTest {
    private val params = Argon2Params(8192, 1, 1)
    private val dir = File(System.getProperty("zyll.vectorsDir") ?: "../../../spec/test-vectors")

    @Test
    fun macMatchesVectors() {
        val v = Json.parseToJsonElement(File(dir, "keybundle-mac.json").readText()).jsonObject
        for (b in v["bundles"]!!.jsonArray.map { it.jsonObject }) {
            val f = KeyBundle.parse(b["bundle"].toString())
            val wrapKey = b["wrapKey"]!!.jsonPrimitive.content.hexToBytes()
            assertEquals(b["macInput"]!!.jsonPrimitive.content, String(KeyBundle.macInput(f)))
            assertTrue(KeyBundle.verify(f, wrapKey))
            assertEquals(f.mac, KeyBundle.sign(f.copy(mac = null), wrapKey).mac)
            assertFalse(KeyBundle.verify(f.copy(rev = f.rev!! + 1), wrapKey))
            assertFalse(KeyBundle.verify(f, ByteArray(32)))
        }
    }

    @Test
    fun newRecoveryKeyKeepsMasterKeyAndRetiresOldOne() {
        val created = KeyBundle.create("old pass 123", params)
        val first = KeyBundle.parse(created.json)
        assertTrue(KeyBundle.verify(first, created.vault.wrapKey))
        val rk = KeyBundle.newRecoveryKey()
        val json = KeyBundle.rewrap(created.json, created.vault, newPassword = null, newRecoveryKey = rk, minRev = 0)
        val f = KeyBundle.parse(json)
        assertTrue(KeyBundle.revOf(f) > KeyBundle.revOf(first))
        assertTrue(KeyBundle.verify(f, created.vault.wrapKey))
        assertArrayEquals(created.vault.masterKey, KeyBundle.unlockWithRecoveryKey(json, RecoveryKey.format(rk)).masterKey)
        assertArrayEquals(created.vault.masterKey, KeyBundle.unlockWithPassword(json, "old pass 123").masterKey)
        assertThrows<ZyllCryptoException> { KeyBundle.unlockWithRecoveryKey(json, created.recoveryKeyDisplay) }
        // password 槽位原样保留
        assertEquals(first.slots.first { it.type == KeyBundle.SLOT_PASSWORD }, f.slots.first { it.type == KeyBundle.SLOT_PASSWORD })
    }

    @Test
    fun changePasswordWithRecoveryKey() {
        val created = KeyBundle.create("old pass 123", params)
        val unlocked = KeyBundle.unlockWithRecoveryKey(created.json, created.recoveryKeyDisplay)
        val json = KeyBundle.rewrap(created.json, unlocked, newPassword = "new pass 456", newRecoveryKey = null, minRev = 0, params = params)
        assertArrayEquals(created.vault.masterKey, KeyBundle.unlockWithPassword(json, "new pass 456").masterKey)
        assertArrayEquals(created.vault.masterKey, KeyBundle.unlockWithRecoveryKey(json, created.recoveryKeyDisplay).masterKey)
        assertThrows<ZyllCryptoException> { KeyBundle.unlockWithPassword(json, "old pass 123") }
    }

    @Test
    fun revAlwaysIncreases() {
        val created = KeyBundle.create("old pass 123", params)
        val future = KeyBundle.encode(KeyBundle.parse(created.json).copy(rev = Long.MAX_VALUE / 2))
        val json = KeyBundle.rewrap(future, created.vault, newPassword = null, newRecoveryKey = KeyBundle.newRecoveryKey(), minRev = 0, now = 5)
        assertEquals(Long.MAX_VALUE / 2 + 1, KeyBundle.revOf(KeyBundle.parse(json)))
        val json2 = KeyBundle.rewrap(created.json, created.vault, newPassword = null, newRecoveryKey = KeyBundle.newRecoveryKey(), minRev = 1_000, now = 5)
        assertTrue(KeyBundle.revOf(KeyBundle.parse(json2)) > 1_000)
    }

    @Test
    fun oldBundlesWithoutRevStillParse() {
        val created = KeyBundle.create("old pass 123", params)
        val old = KeyBundle.encode(KeyBundle.parse(created.json).copy(rev = null, mac = null))
        assertFalse(old.contains("\"rev\"") || old.contains("\"mac\""))
        assertEquals(0, KeyBundle.revOf(KeyBundle.parse(old)))
        assertFalse(KeyBundle.verify(KeyBundle.parse(old), created.vault.wrapKey))
    }

    @Test
    fun rewrapRejectsOtherVault() {
        val a = KeyBundle.create("old pass 123", params)
        val b = KeyBundle.create("old pass 123", params)
        assertThrows<IllegalArgumentException> { KeyBundle.rewrap(a.json, b.vault, null, KeyBundle.newRecoveryKey(), 0) }
    }
}
