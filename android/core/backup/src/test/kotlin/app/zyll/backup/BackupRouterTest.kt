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
        val r = BackupRouter(BackupSettings(mode = BackupMode.Overflow, order = listOf(OneDrive)), both(gFree = 1000 * mb, oFree = 75 * mb))
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

    private val g2 = CloudProvider.of(DriveKind.GoogleDrive, 1)
    private val o2 = CloudProvider.of(DriveKind.OneDrive, 1)

    @Test
    fun mirrorPutsOneCopyPerKindFollowingTheOrder() {
        val drives = listOf(DriveStatus(GoogleDrive, true, null), DriveStatus(g2, true, null), DriveStatus(OneDrive, true, null), DriveStatus(o2, true, null))
        val r = BackupRouter(BackupSettings(mode = BackupMode.Mirror, order = listOf(o2, g2)), drives)
        assertEquals(Route.Upload(listOf(g2, o2)), r.route(photo(1)))
    }

    @Test
    fun mirrorSpillsWithinAKindWhenTheFirstAccountIsFull() {
        val drives = listOf(DriveStatus(GoogleDrive, true, 20 * mb), DriveStatus(g2, true, 1000 * mb), DriveStatus(OneDrive, true, null))
        val r = BackupRouter(BackupSettings(mode = BackupMode.Mirror), drives)
        assertEquals(Route.Upload(listOf(g2, OneDrive)), r.route(photo(1)))
    }

    @Test
    fun mirrorDoesNotCopyToASecondAccountOfTheSameKind() {
        val drives = listOf(DriveStatus(GoogleDrive, true, null), DriveStatus(g2, true, null), DriveStatus(OneDrive, true, null))
        val r = BackupRouter(BackupSettings(mode = BackupMode.Mirror), drives)
        // 已经在第二个 Google 账号里：只补 OneDrive
        assertEquals(Route.Upload(listOf(OneDrive)), r.route(photo(1), alreadyOn = setOf(g2)))
        assertEquals(Route.Upload(emptyList()), r.route(photo(2), alreadyOn = setOf(g2, OneDrive)))
    }

    @Test
    fun overflowUsesTheFirstRankedAccountOnly() {
        val drives = listOf(DriveStatus(GoogleDrive, true, null), DriveStatus(g2, true, null), DriveStatus(OneDrive, true, null))
        val r = BackupRouter(BackupSettings(mode = BackupMode.Overflow, order = listOf(g2, OneDrive, GoogleDrive)), drives)
        assertEquals(Route.Upload(listOf(g2)), r.route(photo(1)))
    }

    @Test
    fun albumRuleWithTwoAccountsOfOneKindUsesOnlyOne() {
        val drives = listOf(DriveStatus(GoogleDrive, true, null), DriveStatus(g2, true, null))
        val settings = BackupSettings(albumRules = mapOf(7L to AlbumRule.To(setOf(GoogleDrive, g2))), order = listOf(g2))
        assertEquals(Route.Upload(listOf(g2)), BackupRouter(settings, drives).route(photo(1, bucket = 7)))
    }

    @Test
    fun rankedPutsUnorderedAccountsLast() {
        val s = BackupSettings(order = listOf(o2, GoogleDrive))
        assertEquals(listOf(o2, GoogleDrive, OneDrive, g2), s.ranked(listOf(GoogleDrive, OneDrive, g2, o2)))
    }

    @Test
    fun eachKindRuleFollowsNewAccounts() {
        val drives = listOf(DriveStatus(GoogleDrive, true, null), DriveStatus(g2, true, null), DriveStatus(OneDrive, true, null))
        val settings = BackupSettings(albumRules = mapOf(7L to AlbumRule.EachKind), order = listOf(g2))
        assertEquals(Route.Upload(listOf(g2, OneDrive)), BackupRouter(settings, drives).route(photo(1, bucket = 7)))
    }

    @Test
    fun allowedKeepsTheUsersFirstChoice() {
        val order = listOf(g2, GoogleDrive, OneDrive)
        assertEquals(setOf(g2, OneDrive), AccountLimits.FREE.allowed(listOf(GoogleDrive, g2, OneDrive), order))
    }

    @Test
    fun mirrorReportsTheDriveThatIsFull() {
        // Google 满了、OneDrive 有空：照样传 OneDrive，但要带上“Google 满了”，界面才能提示
        val r = BackupRouter(BackupSettings(mode = BackupMode.Mirror), both(gFree = 20 * mb, oFree = 1000 * mb))
        assertEquals(Route.Upload(listOf(OneDrive), full = listOf(GoogleDrive)), r.route(photo(1)))
    }

    @Test
    fun markFullSpillsTheRestToTheNextAccount() {
        // 剩余空间查不到（null）的账号一直当作放得下；上传时网盘说满了，之后的照片要改放下一个
        val r = BackupRouter(BackupSettings(mode = BackupMode.Overflow, order = listOf(GoogleDrive, OneDrive)), both(gFree = null, oFree = 1000 * mb))
        assertEquals(Route.Upload(listOf(GoogleDrive)), r.route(photo(1)))
        r.markFull(GoogleDrive)
        assertEquals(Route.Upload(listOf(OneDrive)), r.route(photo(2)))
    }
}
