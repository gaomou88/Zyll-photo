package app.zyll.backup

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CloudProviderTest {
    @AfterEach
    fun reset() = CloudProvider.setExtra(emptyList())

    @Test
    fun firstSlotsKeepTheOldEnumNamesAndOrdinals() {
        assertEquals("GoogleDrive", CloudProvider.GoogleDrive.name)
        assertEquals("OneDrive", CloudProvider.OneDrive.name)
        assertEquals(0, CloudProvider.GoogleDrive.ordinal)
        assertEquals(1, CloudProvider.OneDrive.ordinal)
        assertEquals(listOf(CloudProvider.GoogleDrive, CloudProvider.OneDrive), CloudProvider.entries)
    }

    @Test
    fun extraSlotsRoundTripByNameAndOrdinal() {
        val g2 = CloudProvider.of(DriveKind.GoogleDrive, 1)
        assertEquals("GoogleDrive2", g2.name)
        assertEquals(2, g2.ordinal)
        assertSame(g2, CloudProvider.fromName("GoogleDrive2"))
        assertSame(g2, CloudProvider.fromOrdinal(2))
        assertSame(CloudProvider.OneDrive, CloudProvider.fromName("OneDrive"))
        assertNull(CloudProvider.fromName("Dropbox"))
        assertNull(CloudProvider.fromName("OneDrive1"))
        assertNull(CloudProvider.fromName("OneDrivex"))
    }

    @Test
    fun registeredSlotsShowUpInEntriesInOrder() {
        val o2 = CloudProvider.of(DriveKind.OneDrive, 1)
        CloudProvider.register(o2)
        assertEquals(listOf(CloudProvider.GoogleDrive, CloudProvider.OneDrive, o2), CloudProvider.entries)
        assertEquals(CloudProvider.of(DriveKind.OneDrive, 2), CloudProvider.nextFree(DriveKind.OneDrive))
        assertEquals(CloudProvider.of(DriveKind.GoogleDrive, 1), CloudProvider.nextFree(DriveKind.GoogleDrive))
        CloudProvider.unregister(o2)
        assertFalse(o2 in CloudProvider.entries)
    }

    @Test
    fun limits() {
        val g = CloudProvider.GoogleDrive
        val o = CloudProvider.OneDrive
        assertTrue(AccountLimits.FREE.canAdd(DriveKind.OneDrive, listOf(g)))
        assertFalse(AccountLimits.FREE.canAdd(DriveKind.GoogleDrive, listOf(g)))
        assertFalse(AccountLimits.FREE.canAdd(DriveKind.GoogleDrive, listOf(g, o)))
        assertTrue(AccountLimits.PRO.canAdd(DriveKind.GoogleDrive, listOf(g, o)))
        assertFalse(AccountLimits.SINGLE.canAdd(DriveKind.OneDrive, listOf(g)))
        assertTrue(AccountLimits.SINGLE.canAdd(DriveKind.OneDrive, emptyList()))
    }

    @Test
    fun allowedKeepsFirstSlotsAndPausesTheRest() {
        val g2 = CloudProvider.of(DriveKind.GoogleDrive, 1)
        val g3 = CloudProvider.of(DriveKind.GoogleDrive, 2)
        val all = listOf(g3, CloudProvider.OneDrive, g2, CloudProvider.GoogleDrive)
        assertEquals(setOf(CloudProvider.GoogleDrive, CloudProvider.OneDrive), AccountLimits.FREE.allowed(all))
        assertEquals(setOf(CloudProvider.GoogleDrive), AccountLimits.SINGLE.allowed(all))
        assertEquals(all.toSet(), AccountLimits.PRO.allowed(all))
        // 只有额外槽位登录着时，第一个额外槽位仍然可用
        assertEquals(setOf(g2), AccountLimits.FREE.allowed(listOf(g3, g2)))
    }
}
