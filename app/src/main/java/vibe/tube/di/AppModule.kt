package vibe.tube.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import vibe.tube.data.settings.GeoRegion
import kotlinx.serialization.json.Json
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import vibe.tube.account.FileSessionVault
import vibe.tube.account.KeystoreSecretCipher
import vibe.tube.account.SessionHooks
import vibe.tube.account.SessionManager
import vibe.tube.account.SessionVault
import vibe.tube.account.WebSessionCleaner
import vibe.tube.core.ApplicationScope
import vibe.tube.core.Clock
import vibe.tube.core.Diagnostics
import vibe.tube.core.IoDispatcher
import vibe.tube.data.db.AppDatabase
import vibe.tube.data.repo.AccountActions
import vibe.tube.data.repo.FeedCache
import vibe.tube.data.repo.FeedEnvironment
import vibe.tube.data.repo.FeedRepository
import vibe.tube.data.repo.LocalHistoryRepository
import vibe.tube.data.repo.RoomFeedCache
import vibe.tube.data.settings.SettingsRepository
import vibe.tube.network.InnerTubeClient
import vibe.tube.youtube.InnerTubeProvider
import vibe.tube.youtube.YouTubeProvider
import vibe.tube.youtube.player.StreamResolver
import vibe.tube.youtube.potoken.NewPipePoTokenBridge
import vibe.tube.youtube.potoken.PoTokenMinter
import vibe.tube.youtube.potoken.PoTokenSession
import vibe.tube.youtube.potoken.WebViewBotGuardRunner
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor
import vibe.tube.feature.common.UiEvents
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun json(): Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    @Provides
    @Singleton
    fun clock(): Clock = Clock.System

    @Provides
    @IoDispatcher
    fun io(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @Singleton
    @ApplicationScope
    fun appScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    @Singleton
    fun diagnostics(): Diagnostics = Diagnostics()

    @Provides
    @Singleton
    fun okHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .dispatcher(Dispatcher().apply { maxRequestsPerHost = 16 })
        .build()

    @Provides
    @Singleton
    fun innerTube(http: OkHttpClient, json: Json, clock: Clock): InnerTubeClient = InnerTubeClient(http, json, clock)

    @Provides
    @Singleton
    fun extractor(http: OkHttpClient): vibe.tube.youtube.extractor.NewPipeAdapter = vibe.tube.youtube.extractor.NewPipeAdapter(http)

    @Provides
    @Singleton
    fun provider(client: InnerTubeClient, clock: Clock, extractor: vibe.tube.youtube.extractor.NewPipeAdapter): YouTubeProvider =
        vibe.tube.youtube.extractor.ExtractorYouTubeProvider(extractor, InnerTubeProvider(client, clock))

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "vibetube.db")
            .addMigrations(object : androidx.room.migration.Migration(1, 2) {
                override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE playback_history ADD COLUMN channelId TEXT")
                    db.execSQL("ALTER TABLE playback_history ADD COLUMN channelAvatar TEXT")
                }
            }).build()

    @Provides
    @Singleton
    fun dataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(produceFile = { File(context.filesDir, "datastore/settings.preferences_pb") })

    @Provides
    @Singleton
    fun settings(
        store: DataStore<Preferences>,
        @ApplicationScope scope: CoroutineScope,
        @ApplicationContext context: Context,
    ): SettingsRepository = SettingsRepository(store, scope, context)

    @Provides
    @Singleton
    fun poTokens(
        @ApplicationContext context: Context,
        http: OkHttpClient,
        clock: Clock,
        @ApplicationScope scope: CoroutineScope,
    ): PoTokenMinter = PoTokenSession(
        http.newBuilder().cookieJar(okhttp3.CookieJar.NO_COOKIES).build(),
        WebViewBotGuardRunner(context),
        clock,
        scope,
    )

    @Provides
    @Singleton
    fun streamResolver(
        @ApplicationContext context: Context,
        http: OkHttpClient,
        @ApplicationScope scope: CoroutineScope,
        client: InnerTubeClient,
        clock: Clock,
        extractor: vibe.tube.youtube.extractor.NewPipeAdapter,
        poTokens: PoTokenMinter,
    ): StreamResolver {
        vibe.tube.youtube.cipher.CipherHttp.install(http)
        vibe.tube.youtube.cipher.CipherDeobfuscator.initialize(context)
        vibe.tube.youtube.cipher.PipePipeNsigDecoder.initialize(context)
        scope.launch(kotlinx.coroutines.Dispatchers.IO) { vibe.tube.youtube.cipher.PipePipeNsigDecoder.warmUp() }
        val resolver = StreamResolver(client, clock, fallback = { id, locale -> extractor.media(id, locale) }, poTokens = poTokens)
        // The fallback extractor sends the same kind of proof, minted for the visitor it reports.
        YoutubeStreamExtractor.setPoTokenProvider(NewPipePoTokenBridge(poTokens, { resolver.visitorData() }))
        poTokens.warmUp()
        return resolver
    }

    @Provides
    @Singleton
    fun uiEvents(): UiEvents = UiEvents()

    @Provides
    @Singleton
    fun feedCache(db: AppDatabase, json: Json): FeedCache = RoomFeedCache(db.feedCache(), json)

    @Provides
    @Singleton
    fun resumeStore(@ApplicationContext context: Context): vibe.tube.data.repo.ResumeStore =
        vibe.tube.data.repo.ResumeStore(context.getSharedPreferences(vibe.tube.data.repo.ResumeStore.PREFS_NAME, Context.MODE_PRIVATE))

    @Provides
    @Singleton
    fun localHistory(db: AppDatabase, clock: Clock, resume: vibe.tube.data.repo.ResumeStore): LocalHistoryRepository =
        LocalHistoryRepository(db.playbackHistory(), db.searchHistory(), clock, resume)

    @Provides
    @Singleton
    fun sessionVault(@ApplicationContext context: Context): SessionVault =
        FileSessionVault(File(context.noBackupFilesDir, "sessions"), KeystoreSecretCipher())

    @Provides
    @Singleton
    fun sessionHooks(
        client: InnerTubeClient,
        cache: FeedCache,
        history: LocalHistoryRepository,
        resume: vibe.tube.data.repo.ResumeStore,
    ): SessionHooks = object : SessionHooks {
        override fun cancelRequests(accountId: String) = client.cancelAccount(accountId)

        override suspend fun wipeAccountData(accountId: String) {
            cache.clearAccount(accountId)
            history.clearAccount(accountId)
            resume.clearAccount(accountId)
            WebSessionCleaner.clear()
        }
    }

    @Provides
    @Singleton
    fun geoRegion(
        @ApplicationContext context: Context,
        http: OkHttpClient,
        @ApplicationScope scope: CoroutineScope,
        @IoDispatcher io: CoroutineDispatcher,
    ): GeoRegion = GeoRegion(context, http, scope, io)

    @Provides
    @Singleton
    fun sessionManager(
        vault: SessionVault,
        provider: YouTubeProvider,
        hooks: SessionHooks,
        settings: SettingsRepository,
        geo: GeoRegion,
        clock: Clock,
    ): SessionManager = SessionManager(vault, provider, hooks, { settings.latest.value.contentLocale(ipRegion = geo.region.value) }, clock)

    @Provides
    @Singleton
    fun feedEnvironment(
        session: SessionManager,
        settings: SettingsRepository,
        geo: GeoRegion,
        cache: FeedCache,
        diagnostics: Diagnostics,
        provider: YouTubeProvider,
        clock: Clock,
    ): FeedEnvironment = FeedEnvironment(
        session = session,
        localeFlow = combine(settings.settings, geo.region) { s, region -> s.contentLocale(ipRegion = region) }.distinctUntilChanged(),
        cache = cache,
        diagnostics = diagnostics,
        providerName = provider.name,
        clock = clock,
    )

    @Provides
    @Singleton
    fun feedRepository(provider: YouTubeProvider, env: FeedEnvironment, history: LocalHistoryRepository): FeedRepository =
        FeedRepository(provider, env, history)

    @Provides
    @Singleton
    fun accountActions(provider: YouTubeProvider, session: SessionManager, settings: SettingsRepository, geo: GeoRegion): AccountActions =
        AccountActions(provider, session) { settings.latest.value.contentLocale(ipRegion = geo.region.value) }
}
