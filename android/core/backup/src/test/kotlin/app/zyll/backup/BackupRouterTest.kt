package app.zyll.backup

import app.zyll.backup.CloudProvider.Companion.GoogleDrive
import app.zyll.backup.CloudProvider.Companion.OneDrive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BackupRouterTest {
    private val mb = 1024L * 1024
    private fun photo(id: Long, bucket: Long = 1, size: Long = 10 * mb) = BackupCandidate(id, bucket, size, isVideo = false)
    private fun both(gFree: Long? = null, oFree: Long? = null) = listOf(
        DriveStatus(GoogleDrive, true, gFree),
        DriveStatus(OneDrive, true, oFree),
    )

    @Test
    fun mirrorGoesToEveryConnectedDrive() {
        val r = BackupRouter(BackupSettings(mode = BackupMode.Mirror), both())
        assertEquals(Route.Upload(listOf(GoogleDrive, OneDrive)), r.route(photo(1)))
    }

    @Test
    fun mirrorOnlyUploadsWhatIsMissing() {
        val r = BackupRouter(BackupSettings(mode = BackupMode.Mirror), both())
        assertEquals(Route.Upload(listOf(OneDrive)), r.route(photo(1), alreadyOn = setOf(GoogleDrive)))
    }

    @Test
    fun mirrorWithOneDriveDisconnectedUsesTheOther() {
        val drives = listOf(DriveStatus(GoogleDrive, true, null), DriveStatus(OneDrive, false, null))
        val r = BackupRouter(BackupSettings(mode = BackupMode.Mirror), drives)
        assertEquals(Route.Upload(listOf(GoogleDrive)), r.route(photo(1)))
    }

    @Test
    fun overflowFillsPrimaryThenSpillsOver() {
        // 首选 OneDrive 只剩 75 MB，留 50 MB 余量后能放下 2 张 10 MB 的
        val r = BackupRouter(BackupSettings(mode = BackupMode.Overflow, primary = OneDrive), both(gFree = 1000 * mb, oFree = 75 * mb))
        assertEquals(Route.Upload(listOf(OneDrive)), r.route(photo(1)))
        assertEquals(Route.Upload(listOf(OneDrive)), r.route(photo(2)))
        assertEquals(Route.Upload(listOf(GoogleDrive)), r.route(photo(3)))
    }

    @Test
    fun overflowReportsFullWhenNothingFits() {
        val r = BackupRouter(BackupSettings(mode = BackupMode.Overflow), both(gFree = 20 * mb, oFree = 20 * mb))
        assertEquals(Route.Full(listOf(GoogleDrive, OneDrive)), r.route(photo(1)))
    }

    @Test
    fun overflowSkipsItemsAlreadyBackedUpAnywhere() {
        val r = BackupRouter(BackupSettings(mode = BackupMode.Overflow), both())
        assertEquals(Route.Upload(emptyList()), r.route(photo(1), alreadyOn = setOf(OneDrive)))
    }

    @Test
    fun albumRulesOverrideTheMode() {
        val settings = BackupSettings(
            mode = BackupMode.Mirror,
            albumRules = mapOf(7L to AlbumRule.To(setOf(OneDrive)), 8L to AlbumRule.Skip),
        )
        val r = BackupRouter(settings, both())
        assertEquals(Route.Upload(listOf(OneDrive)), r.route(photo(1, bucket = 7)))
        assertEquals(Route.Skipped, r.route(photo(2, bucket = 8)))
        assertEquals(Route.Upload(listOf(GoogleDrive, OneDrive)), r.route(photo(3, bucket = 9)))
    }

    @Test
    fun albumAssignedToDisconnectedDriveWaits() {
        val drives = listOf(DriveStatus(GoogleDrive, true, null), DriveStatus(OneDrive, false, null))
        val settings = BackupSettings(albumRules = mapOf(7L to AlbumRule.To(setOf(OneDrive))))
        assertEquals(Route.NoDrive, BackupRouter(settings, drives).route(photo(1, bucket = 7)))
    }

    @Test
    fun albumAssignedToFullDriveDoesNotSpillOver() {
        val settings = BackupSettings(albumRules = mapOf(7L to AlbumRule.To(setOf(GoogleDrive))))
        val r = BackupRouter(settings, both(gFree = 10 * mb, oFree = 1000 * mb))
        assertEquals(Route.Full(listOf(GoogleDrive)), r.route(photo(1, bucket = 7)))
    }

    @Test
    fun videosCanBeExcluded() {
        val r = BackupRouter(BackupSettings(includeVideos = false), both())
        assertEquals(Route.Skipped, r.route(BackupCandidate(1, 1, 10 * mb, isVideo = true)))
    }

    @Test
    fun noConnectedDrive() {
        val drives = listOf(DriveStatus(GoogleDrive, false, null), DriveStatus(OneDrive, false, null))
        assertEquals(Route.NoDrive, BackupRouter(BackupSettings(), drives).route(photo(1)))
    }
}
