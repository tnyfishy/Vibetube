package vibe.tube.youtube.player

/**
 * The user agent each InnerTube client presents. googlevideo checks that a URL is fetched by the
 * client that minted it (the URL's `c` parameter), so a stream URL must be requested with the same
 * user agent the player request used, or it is refused partway through the video.
 */
object ClientUserAgents {
    const val VISIONOS = "com.google.visionos.youtube/1.04(RealityDevice17,1; U; CPU visionOS 26_6_0 like Mac OS X; {region})"
    const val ANDROID_VR = "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip"
    const val ANDROID_VR_1_61_48 = "com.google.android.apps.youtube.vr.oculus/1.61.48 (Linux; U; Android 12; en_US; Quest 3; Build/SQ3A.220605.009.A1; Cronet/132.0.6808.3)"
    const val ANDROID_VR_1_43_32 = "com.google.android.apps.youtube.vr.oculus/1.43.32 (Linux; U; Android 12; en_US; Quest 3; Build/SQ3A.220605.009.A1; Cronet/107.0.5284.2)"
    const val TV_TIZEN = "Mozilla/5.0 (SMART-TV; Linux; Tizen 2.4.0) AppleWebKit/538.1 (KHTML, like Gecko) Version/2.4.0 TV Safari/538.1"
    const val MWEB = "Mozilla/5.0 (iPad; CPU OS 16_7_10 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1,gzip(gfe)"
    const val WEB = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"
    const val IOS = "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)"
    const val ANDROID = "com.google.android.youtube/20.10.38 (Linux; U; Android 11) gzip"

    /** The user agent for URLs minted by [client], or [fallback] when the client is not one of ours. */
    fun forClient(client: String?, fallback: String, region: String = "US"): String = when (client?.uppercase()) {
        "VISIONOS" -> VISIONOS.replace("{region}", region)
        "ANDROID_VR" -> ANDROID_VR
        "TVHTML5" -> TV_TIZEN
        "IOS" -> IOS
        "ANDROID", "ANDROID_CREATOR" -> ANDROID
        else -> fallback
    }
}
