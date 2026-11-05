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
}
