package vibe.tube.update

import org.junit.Assert.*
import org.junit.Test

class UpdateManifestTest {
    private val release = UpdateManifest("vibe.tube.test", 3, "1.2", "https://github.com/tnyfishy/Vibetube/raw/commit/app.apk", "a".repeat(64), 1234)
    @Test fun onlyNewCompatibleVersionsAreOffered() {
        assertTrue(release.validate("vibe.tube.test", 2, 35))
        assertFalse(release.validate("vibe.tube", 2, 35))
        assertFalse(release.validate("vibe.tube.test", 3, 35))
        assertFalse(release.copy(minSdk = 36).validate("vibe.tube.test", 2, 35))
    }
    @Test fun rejectsUnverifiableOrUntrustedDownloads() {
        listOf("http://github.com/a.apk", "https://github.com.evil.example/a.apk", "https://evil.example/a.apk", "https://user@github.com/a.apk").forEach {
            assertFalse(release.copy(apkUrl = it).validate("vibe.tube.test", 2, 35))
        }
        assertFalse(release.copy(size = 0).validate("vibe.tube.test", 2, 35))
        assertFalse(release.copy(sha256 = "bad").validate("vibe.tube.test", 2, 35))
    }
}
