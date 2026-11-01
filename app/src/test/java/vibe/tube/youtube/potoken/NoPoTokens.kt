package vibe.tube.youtube.potoken

object NoPoTokens : vibe.tube.youtube.potoken.PoTokenMinter {
    override suspend fun mint(identifier: String): String? = null
    override fun invalidate() {}
    override fun warmUp() {}
}
