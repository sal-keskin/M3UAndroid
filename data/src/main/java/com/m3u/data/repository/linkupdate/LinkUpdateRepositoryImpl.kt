package com.m3u.data.repository.linkupdate

import android.content.Context
import androidx.room.withTransaction
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.m3u.core.foundation.architecture.preferences.PreferencesKeys
import com.m3u.core.foundation.architecture.preferences.Settings
import com.m3u.core.foundation.architecture.preferences.flowOf
import com.m3u.core.foundation.architecture.preferences.get
import com.m3u.core.foundation.architecture.preferences.set
import com.m3u.data.api.OkhttpClient
import com.m3u.data.api.ProviderOkhttpClient
import com.m3u.data.database.M3UDatabase
import com.m3u.data.database.dao.ChannelDao
import com.m3u.data.database.dao.LinkRegistryDao
import com.m3u.data.database.dao.PlaylistDao
import com.m3u.data.database.model.Channel
import com.m3u.data.database.model.ChannelStream
import com.m3u.data.database.model.DataSource
import com.m3u.data.database.model.Playlist
import com.m3u.data.database.model.RankedChannel
import com.m3u.data.database.model.RegistryChannel
import com.m3u.data.registry.fetch.ConditionalFetcher
import com.m3u.data.registry.match.MatchTier
import com.m3u.data.registry.model.RegistryChannelSpec
import com.m3u.data.registry.model.RegistryDocument
import com.m3u.data.registry.model.RegistryJson
import com.m3u.data.registry.model.SourceSpec
import com.m3u.data.registry.model.SourcesDocument
import com.m3u.data.registry.plan.ChannelPlan
import com.m3u.data.registry.plan.LinkUpdatePlanner
import com.m3u.data.registry.plan.MatchedCandidate
import com.m3u.data.registry.resolve.OfficialPageScanner
import com.m3u.data.registry.resolve.ResolvedLink
import com.m3u.data.registry.plan.LinkUpdateSummary
import com.m3u.data.registry.plan.StreamRecord
import com.m3u.data.registry.source.CandidateParser
import com.m3u.data.registry.source.StreamCandidate
import com.m3u.data.registry.validate.StreamStatus
import com.m3u.data.registry.validate.StreamValidator
import com.m3u.data.registry.validate.ValidationResult
import com.m3u.data.worker.LinkUpdateWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import okhttp3.OkHttpClient
import timber.log.Timber
import java.io.File
import java.io.InputStream
import java.util.Optional
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class LinkUpdateRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: M3UDatabase,
    private val linkRegistryDao: LinkRegistryDao,
    private val channelDao: ChannelDao,
    private val playlistDao: PlaylistDao,
    @OkhttpClient(false) playbackClient: OkHttpClient,
    @param:ProviderOkhttpClient private val documentClient: OkHttpClient,
    private val workManager: WorkManager,
    private val settings: Settings,
    config: Optional<LinkRegistryConfig>,
    private val officialPageResolver: OfficialPageResolver,
) : LinkUpdateRepository {
    private val timber = Timber.tag("LinkUpdateRepository")
    private val config: LinkRegistryConfig? = config.orElse(null)
    private val directory = File(context.filesDir, "link-registry")
    private val stateStore = LinkUpdateStateStore(File(directory, "state.json"))
    private val fetcher = ConditionalFetcher(documentClient, File(directory, "http-cache"))
    private val runMutex = Mutex()
    private val planner = LinkUpdatePlanner()

    // validate with the player's own TLS policy, minus the interceptor that turns errors into 999s
    private val validator = StreamValidator(
        client = playbackClient.newBuilder().apply { interceptors().clear() }.build(),
    )

    override val enabled: Boolean get() = config != null

    override fun observeRankedChannels(): Flow<List<RankedChannel>> =
        if (config == null) flowOf(emptyList())
        else linkRegistryDao.observeRankedChannels(LinkRegistry.PLAYLIST_URL)

    override fun observeDiagnostics(): Flow<List<ChannelDiagnostics>> {
        if (config == null) return flowOf(emptyList())
        return combine(
            linkRegistryDao.observeChannels(LinkRegistry.PLAYLIST_URL),
            linkRegistryDao.observeStreamsByPlaylistUrl(LinkRegistry.PLAYLIST_URL),
            linkRegistryDao.observeRankedChannels(LinkRegistry.PLAYLIST_URL),
        ) { registryChannels, streams, ranked ->
            val titles = ranked.associate { it.channel.relationId to it.channel.title }
            val streamsById = streams.groupBy { it.canonicalId }
            registryChannels.map { channel ->
                ChannelDiagnostics(
                    canonicalId = channel.canonicalId,
                    rank = channel.rank,
                    title = titles[channel.canonicalId] ?: channel.canonicalId,
                    officialLivePage = channel.officialLivePage,
                    stale = channel.stale,
                    updatedAt = channel.updatedAt,
                    streams = streamsById[channel.canonicalId].orEmpty().map { it.toDiagnostics() },
                )
            }
        }
    }

    override fun observeProgress(): Flow<LinkUpdateProgress> = workManager
        .getWorkInfosByTagFlow(LinkUpdateWorker.TAG)
        .map { infos ->
            val running = infos.firstOrNull { it.state == WorkInfo.State.RUNNING }
                ?: return@map LinkUpdateProgress.Idle
            LinkUpdateWorker.progressOf(running)
        }
        .distinctUntilChanged()

    override fun observeRun(id: UUID): Flow<LinkUpdateProgress> = workManager
        .getWorkInfoByIdFlow(id)
        .map { info ->
            when (info?.state) {
                WorkInfo.State.RUNNING -> LinkUpdateWorker.progressOf(info)
                WorkInfo.State.SUCCEEDED -> LinkUpdateWorker.reportOf(info)
                    ?.let { LinkUpdateProgress.Finished(it) }
                    ?: LinkUpdateProgress.Idle
                WorkInfo.State.FAILED,
                WorkInfo.State.CANCELLED -> LinkUpdateProgress.Failed(LinkUpdateWorker.channelIdOf(info))
                else -> LinkUpdateProgress.Idle
            }
        }
        .distinctUntilChanged()

    override fun observeLastReport(): Flow<LinkUpdateReport?> =
        stateStore.current.map { it.lastReport }.distinctUntilChanged()

    override fun observeBaseUrl(): Flow<String> = settings
        .flowOf(PreferencesKeys.REGISTRY_BASE_URL)
        .map { it.ifBlank { config?.defaultBaseUrl.orEmpty() } }

    override suspend fun setBaseUrl(url: String) {
        val trimmed = url.trim().trimEnd('/')
        settings[PreferencesKeys.REGISTRY_BASE_URL] =
            if (trimmed == config?.defaultBaseUrl?.trimEnd('/')) "" else trimmed
    }

    override suspend fun bootstrap(): Boolean {
        if (config == null) return false
        if (stateStore.current.value.bootstrapped) return false
        if (playlistDao.get(LinkRegistry.PLAYLIST_URL) != null) {
            stateStore.update { it.copy(bootstrapped = true) }
            return false
        }
        return try {
            run(LinkUpdateRequest(LinkUpdateTrigger.FIRST_RUN, validate = false, offline = true))
            stateStore.update { it.copy(bootstrapped = true) }
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (exception: Exception) {
            timber.e(exception, "bootstrap failed")
            false
        }
    }

    override suspend fun scheduleAutomaticUpdates() {
        if (config == null) return
        if (!settings[PreferencesKeys.LINK_AUTO_UPDATE]) {
            workManager.cancelUniqueWork(LinkUpdateWorker.UNIQUE_WEEKLY)
            return
        }
        val unmetered = Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build()
        workManager.enqueueUniquePeriodicWork(
            LinkUpdateWorker.UNIQUE_WEEKLY,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<LinkUpdateWorker>(7, TimeUnit.DAYS)
                .setInitialDelay(7, TimeUnit.DAYS)
                .setConstraints(unmetered)
                .setInputData(LinkUpdateWorker.input(LinkUpdateTrigger.PERIODIC))
                .addTag(LinkUpdateWorker.TAG)
                .build(),
        )
        val lastRun = stateStore.current.value.lastFullRunAt
        if (System.currentTimeMillis() - lastRun >= AUTO_UPDATE_INTERVAL_MILLIS) {
            workManager.enqueueUniqueWork(
                LinkUpdateWorker.UNIQUE_UPDATE,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<LinkUpdateWorker>()
                    .setConstraints(unmetered)
                    .setInputData(LinkUpdateWorker.input(LinkUpdateTrigger.AUTO))
                    .addTag(LinkUpdateWorker.TAG)
                    .build(),
            )
        }
    }

    override fun updateNow(): UUID {
        val request = OneTimeWorkRequestBuilder<LinkUpdateWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(LinkUpdateWorker.input(LinkUpdateTrigger.MANUAL))
            .addTag(LinkUpdateWorker.TAG)
            .build()
        workManager.enqueueUniqueWork(LinkUpdateWorker.UNIQUE_UPDATE, ExistingWorkPolicy.REPLACE, request)
        return request.id
    }

    override fun repair(channelId: String): UUID {
        val request = OneTimeWorkRequestBuilder<LinkUpdateWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(LinkUpdateWorker.input(LinkUpdateTrigger.REPAIR, channelId))
            .addTag(LinkUpdateWorker.TAG)
            .build()
        workManager.enqueueUniqueWork(
            "${LinkUpdateWorker.UNIQUE_REPAIR_PREFIX}$channelId",
            ExistingWorkPolicy.REPLACE,
            request,
        )
        return request.id
    }

    override suspend fun run(
        request: LinkUpdateRequest,
        onProgress: suspend (LinkUpdateProgress.Running) -> Unit,
    ): LinkUpdateReport = runMutex.withLock {
        val config = checkNotNull(config) { "This app ships no channel registry" }
        withContext(Dispatchers.IO) {
            val startedAt = System.currentTimeMillis()
            onProgress(LinkUpdateProgress.Running(LinkUpdateProgress.Phase.FETCHING, 0, 0, request.channelId))
            val baseUrl = currentBaseUrl(config)
            val (registry, registryFromCache) = loadDocument(
                name = REGISTRY_FILE,
                url = "$baseUrl/$REGISTRY_FILE",
                deserializer = RegistryDocument.serializer(),
                offline = request.offline,
            )
            val sources = loadDocument(
                name = SOURCES_FILE,
                url = "$baseUrl/$SOURCES_FILE",
                deserializer = SourcesDocument.serializer(),
                offline = request.offline,
            ).first
            val allChannels = registry.enabledChannels
            check(allChannels.isNotEmpty()) { "The registry lists no enabled channels" }
            val channels = allChannels.filter { request.channelId == null || it.id == request.channelId }
            check(channels.isNotEmpty()) { "Unknown registry channel ${request.channelId}" }

            val sourceErrors = linkedMapOf<String, String>()
            val candidates = loadCandidates(config, baseUrl, sources, request, sourceErrors) { done, total ->
                onProgress(LinkUpdateProgress.Running(LinkUpdateProgress.Phase.FETCHING, done, total, request.channelId))
            }

            // official live pages, read on this device only; opt-in, never in the registry's output
            val officialLinks = !request.offline && settings[PreferencesKeys.OFFICIAL_PAGE_LINKS]
            val resolved = if (officialLinks) resolveOfficialLinks(channels) else emptyMap()

            onProgress(LinkUpdateProgress.Running(LinkUpdateProgress.Phase.MATCHING, 0, channels.size, request.channelId))
            val matched = planner.match(allChannels, candidates, sources.blockedHosts, sources.officialHosts)
                .withOfficial(resolved)
            val trustBySource = sources.sources.associate { it.id to it.trust } +
                (LinkUpdatePlanner.SOURCE_PINNED to 1.0) +
                (OfficialPageScanner.SOURCE_OFFICIAL to 1.0) +
                (OfficialPageScanner.SOURCE_YOUTUBE to 1.0)
            val known = channels.associate { spec ->
                spec.id to linkRegistryDao.getStreams(spec.id)
                    // switching official links off drops what they found
                    .filter { officialLinks || it.sourceId !in OFFICIAL_SOURCES }
                    .map { it.toRecord(trustBySource, sources.officialHosts) }
            }

            val validations = if (request.validate && !request.offline) {
                val targets = channels.flatMap { spec ->
                    planner.validationTargets(spec, matched[spec.id].orEmpty(), known[spec.id].orEmpty())
                }.filterNot { OfficialPageScanner.isYoutubeUrl(it.url) }
                onProgress(LinkUpdateProgress.Running(LinkUpdateProgress.Phase.VALIDATING, 0, targets.size, request.channelId))
                val progressMutex = Mutex()
                validator.validateAll(targets) { done, total ->
                    progressMutex.withLock {
                        onProgress(
                            LinkUpdateProgress.Running(LinkUpdateProgress.Phase.VALIDATING, done, total, request.channelId)
                        )
                    }
                } + youtubeValidations(resolved)
            } else {
                null
            }

            val now = System.currentTimeMillis()
            val plans = channels.map { spec ->
                planner.plan(spec, matched[spec.id].orEmpty(), known[spec.id].orEmpty(), validations, now)
            }

            onProgress(LinkUpdateProgress.Running(LinkUpdateProgress.Phase.SAVING, 0, plans.size, request.channelId))
            write(
                config = config,
                registry = registry,
                channels = channels,
                plans = plans,
                fullScope = request.channelId == null,
                now = now,
            )

            val summary = LinkUpdateSummary.of(plans)
            val report = LinkUpdateReport(
                trigger = request.trigger.name,
                channelId = request.channelId,
                startedAt = startedAt,
                finishedAt = System.currentTimeMillis(),
                channels = plans.size,
                updated = summary.updated,
                unchanged = summary.unchanged,
                failing = summary.failing,
                newFuzzy = summary.newFuzzy,
                validated = validations != null,
                sourceErrors = sourceErrors,
                registryFromCache = registryFromCache,
            )
            stateStore.update { state ->
                if (request.channelId == null) {
                    state.copy(
                        lastFullRunAt = if (validations != null) report.finishedAt else state.lastFullRunAt,
                        lastReport = report,
                    )
                } else {
                    state
                }
            }
            timber.d("link update finished: $report")
            report
        }
    }

    override suspend fun streamsFor(channel: Channel): List<PlaybackStream> {
        if (config == null || !isRegistryPlaylistUrl(channel.playlistUrl)) return emptyList()
        val canonicalId = channel.relationId ?: return emptyList()
        val streams = linkRegistryDao.getStreams(canonicalId).map { stream ->
            PlaybackStream(
                url = stream.url,
                headers = stream.headers.orEmpty(),
                sourceId = stream.sourceId,
                height = stream.height,
            )
        }
        return streams.ifEmpty {
            listOfNotNull(
                channel.url.takeIf(String::isNotBlank)?.let { url ->
                    PlaybackStream(url, channel.headers.orEmpty(), LinkUpdatePlanner.SOURCE_PINNED, null)
                }
            )
        }
    }

    override suspend fun reportPlaybackFailure(channel: Channel, url: String, reason: String) {
        if (config == null || !isRegistryPlaylistUrl(channel.playlistUrl)) return
        val canonicalId = channel.relationId ?: return
        database.withTransaction {
            linkRegistryDao.demoteStream(
                canonicalId = canonicalId,
                url = url,
                status = StreamStatus.FAILED.value,
                reason = "playback: $reason",
                checkedAt = System.currentTimeMillis(),
            )
            val primary = linkRegistryDao.getStreams(canonicalId).firstOrNull() ?: return@withTransaction
            if (primary.url != url) {
                linkRegistryDao.updateChannelInPlace(
                    playlistUrl = channel.playlistUrl,
                    canonicalId = canonicalId,
                    url = primary.url,
                    headers = primary.headers,
                    title = channel.title,
                    cover = channel.cover,
                    category = channel.category,
                )
            }
        }
    }

    override suspend fun refreshOfficialLink(channel: Channel): Boolean {
        val config = config ?: return false
        if (!isRegistryPlaylistUrl(channel.playlistUrl)) return false
        if (!settings[PreferencesKeys.OFFICIAL_PAGE_LINKS]) return false
        val canonicalId = channel.relationId ?: return false
        val spec = cachedRegistry(config)?.channels?.firstOrNull { it.id == canonicalId } ?: return false
        val resolver = spec.resolver ?: return false
        val streams = linkRegistryDao.getStreams(canonicalId)
        val official = streams.firstOrNull { it.sourceId in OFFICIAL_SOURCES }
        val now = System.currentTimeMillis()
        val maxAge = TimeUnit.MINUTES.toMillis(resolver.ttlMinutes.toLong())
        if (official != null && now - official.checkedAt < maxAge) return false
        val link = runCatching { officialPageResolver.resolve(spec) }
            .onFailure { timber.w(it, "cannot resolve ${spec.id}") }
            .getOrNull()
            ?: return false
        val refreshed = link.toStream(canonicalId, now, previous = official)
        database.withTransaction {
            val others = streams.filter { it.sourceId !in OFFICIAL_SOURCES && it.url != refreshed.url }
            linkRegistryDao.replaceStreams(
                canonicalId,
                listOf(refreshed) + others.mapIndexed { index, stream -> stream.copy(position = index + 1) },
            )
            linkRegistryDao.updateChannelInPlace(
                playlistUrl = channel.playlistUrl,
                canonicalId = canonicalId,
                url = refreshed.url,
                headers = refreshed.headers,
                title = channel.title,
                cover = channel.cover,
                category = channel.category,
            )
        }
        return true
    }

    private suspend fun resolveOfficialLinks(channels: List<RegistryChannelSpec>): Map<String, ResolvedLink> =
        channels
            .filter { it.resolver != null }
            .mapNotNull { spec ->
                runCatching { officialPageResolver.resolve(spec) }
                    .onFailure { timber.w(it, "cannot resolve ${spec.id}") }
                    .getOrNull()
                    ?.let { spec.id to it }
            }
            .toMap()

    // a link found on the channel's own page belongs to that channel: no name matching needed
    private fun Map<String, List<MatchedCandidate>>.withOfficial(
        resolved: Map<String, ResolvedLink>,
    ): Map<String, List<MatchedCandidate>> {
        if (resolved.isEmpty()) return this
        val merged = toMutableMap()
        resolved.forEach { (channelId, link) ->
            val candidate = StreamCandidate(
                sourceId = link.sourceId,
                trust = 1.0,
                url = link.url,
                name = channelId,
                tvgId = channelId,
                headers = link.headers,
            )
            merged[channelId] = listOf(MatchedCandidate(channelId, candidate, MatchTier.EXACT_ID, 1.0)) +
                merged[channelId].orEmpty()
        }
        return merged
    }

    // a YouTube watch link is handed to the YouTube app; there is no playlist to validate
    private fun youtubeValidations(resolved: Map<String, ResolvedLink>): Map<String, ValidationResult> =
        resolved.values
            .filter { it.kind == ResolvedLink.Kind.YOUTUBE }
            .associate { it.url to ValidationResult(ok = true, checkedAt = System.currentTimeMillis()) }

    private val ResolvedLink.sourceId: String
        get() = if (kind == ResolvedLink.Kind.YOUTUBE) {
            OfficialPageScanner.SOURCE_YOUTUBE
        } else {
            OfficialPageScanner.SOURCE_OFFICIAL
        }

    private fun ResolvedLink.toStream(canonicalId: String, now: Long, previous: ChannelStream?) = ChannelStream(
        canonicalId = canonicalId,
        url = url,
        position = 0,
        headers = headers.takeIf { it.isNotEmpty() },
        sourceId = sourceId,
        matchTier = MatchTier.EXACT_ID.code,
        matchScore = 1.0,
        // a fresh token: not validated yet, the player is about to try it
        status = if (kind == ResolvedLink.Kind.YOUTUBE) StreamStatus.OK.value else StreamStatus.UNTESTED.value,
        checkedAt = now,
        lastOkAt = previous?.lastOkAt ?: 0L,
        firstSeenAt = previous?.firstSeenAt ?: now,
    )

    // the registry as last fetched (or bundled), without touching the network
    private suspend fun cachedRegistry(config: LinkRegistryConfig): RegistryDocument? = withContext(Dispatchers.IO) {
        val url = "${currentBaseUrl(config)}/$REGISTRY_FILE"
        val text = fetcher.cachedFile(url)?.readText()
            ?: openBundled(REGISTRY_FILE)?.use { it.readBytes().decodeToString() }
            ?: return@withContext null
        runCatching { RegistryJson.decodeFromString(RegistryDocument.serializer(), text) }.getOrNull()
    }

    private suspend fun currentBaseUrl(config: LinkRegistryConfig): String =
        settings[PreferencesKeys.REGISTRY_BASE_URL]
            .ifBlank { config.defaultBaseUrl }
            .trim()
            .trimEnd('/')

    /**
     * Network first (with ETag revalidation and the cached copy on failure), then the bundled
     * snapshot. Returns the document and whether it came from a cache or the snapshot.
     */
    private suspend fun <T> loadDocument(
        name: String,
        url: String,
        deserializer: DeserializationStrategy<T>,
        offline: Boolean,
    ): Pair<T, Boolean> {
        if (!offline) {
            val result = fetcher.fetch(url)
            val parsed = result.file?.let { file ->
                runCatching { RegistryJson.decodeFromString(deserializer, file.readText()) }
                    .onFailure { timber.w(it, "cannot parse $url") }
                    .getOrNull()
            }
            if (parsed != null) return parsed to (result !is ConditionalFetcher.FetchResult.Fresh &&
                result !is ConditionalFetcher.FetchResult.NotModified)
            timber.w("falling back to the bundled $name")
        }
        val bundled = openBundled(name)?.use { input ->
            RegistryJson.decodeFromString(deserializer, input.readBytes().decodeToString())
        } ?: error("No $name available")
        return bundled to true
    }

    private suspend fun loadCandidates(
        config: LinkRegistryConfig,
        baseUrl: String,
        sources: SourcesDocument,
        request: LinkUpdateRequest,
        errors: MutableMap<String, String>,
        onProgress: suspend (done: Int, total: Int) -> Unit,
    ): List<StreamCandidate> {
        val selfTrust = sources.sources.firstOrNull { it.id == SELF_SOURCE_ID }?.trust ?: 1.0
        if (request.offline) {
            return openBundled(BUNDLED_PLAYLIST)
                ?.use { CandidateParser.parseM3u(SELF_SOURCE_ID, selfTrust, it) }
                .orEmpty()
        }
        val enabledSources = sources.sources.filter { it.enabled }
        val candidates = mutableListOf<StreamCandidate>()
        enabledSources.forEachIndexed { index, source ->
            val url = source.url.replace(LinkRegistry.BASE_URL_PLACEHOLDER, baseUrl)
            try {
                val result = fetcher.fetch(url)
                if (result is ConditionalFetcher.FetchResult.Stale) errors[source.id] = "cached: ${result.error}"
                val parsed = result.file?.inputStream()?.use { input -> parse(source, input) }
                    ?: if (source.id == SELF_SOURCE_ID) {
                        openBundled(BUNDLED_PLAYLIST)?.use { parse(source, it) }
                    } else {
                        null
                    }
                if (parsed == null) {
                    errors[source.id] = (result as? ConditionalFetcher.FetchResult.Failed)?.error ?: "unavailable"
                } else {
                    candidates += parsed
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (exception: Exception) {
                // one broken source never stops the others
                timber.w(exception, "source ${source.id} failed")
                errors[source.id] = exception.message ?: exception::class.simpleName.orEmpty()
            }
            onProgress(index + 1, enabledSources.size)
        }
        return candidates
    }

    private fun parse(source: SourceSpec, input: InputStream): List<StreamCandidate> = when (source.type) {
        SourceSpec.TYPE_IPTVORG_API -> CandidateParser.parseIptvOrgApi(source.id, source.trust, source.filterCountry, input)
        else -> CandidateParser.parseM3u(source.id, source.trust, input)
    }

    private fun openBundled(name: String): InputStream? {
        val directory = config?.bundledAssetDirectory ?: return null
        return runCatching { context.assets.open("$directory/$name") }.getOrNull()
    }

    /**
     * One transaction: registry rows, stream lists and the playable channel rows, updated in
     * place by canonical id so ids, favourites and history survive.
     */
    private suspend fun write(
        config: LinkRegistryConfig,
        registry: RegistryDocument,
        channels: List<RegistryChannelSpec>,
        plans: List<ChannelPlan>,
        fullScope: Boolean,
        now: Long,
    ) {
        val playlistUrl = LinkRegistry.PLAYLIST_URL
        val plansById = plans.associateBy { it.channelId }
        database.withTransaction {
            if (playlistDao.get(playlistUrl) == null) {
                // insert only; replacing the row would cascade-delete the registry tables
                playlistDao.insertOrReplace(
                    Playlist(
                        title = registry.title ?: config.defaultTitle,
                        url = playlistUrl,
                        source = DataSource.M3U,
                    )
                )
            }
            linkRegistryDao.upsertChannels(
                *channels.map { spec ->
                    RegistryChannel(
                        canonicalId = spec.id,
                        playlistUrl = playlistUrl,
                        rank = spec.rank,
                        officialLivePage = spec.officialLivePage,
                        stale = plansById[spec.id]?.stale == true,
                        updatedAt = now,
                    )
                }.toTypedArray()
            )
            if (fullScope) {
                val keep = channels.map { it.id }
                linkRegistryDao.deleteChannelsNotIn(playlistUrl, keep)
                linkRegistryDao.deleteChannelsOutside(playlistUrl, keep)
            }
            channels.forEach { spec ->
                val plan = plansById[spec.id] ?: return@forEach
                linkRegistryDao.replaceStreams(
                    spec.id,
                    plan.streams.mapIndexed { position, record -> record.toEntity(spec.id, position) },
                )
                val primary = plan.primary
                val headers = primary?.headers?.takeIf { it.isNotEmpty() }
                val updated = linkRegistryDao.updateChannelInPlace(
                    playlistUrl = playlistUrl,
                    canonicalId = spec.id,
                    url = primary?.url.orEmpty(),
                    headers = headers,
                    title = spec.name,
                    cover = spec.logo,
                    category = spec.group,
                )
                if (updated == 0) {
                    channelDao.insertOrReplaceAllRaw(
                        Channel(
                            url = primary?.url.orEmpty(),
                            category = spec.group,
                            title = spec.name,
                            cover = spec.logo,
                            playlistUrl = playlistUrl,
                            relationId = spec.id,
                            headers = headers,
                        )
                    )
                }
            }
            channelDao.synchronizeMetadataBases(channelDao.getByPlaylistUrl(playlistUrl))
            if (fullScope) channelDao.deleteOrphanedMetadata(playlistUrl)
        }
    }

    private fun ChannelStream.toRecord(
        trustBySource: Map<String, Double>,
        officialHosts: List<String>,
    ) = StreamRecord(
        url = url,
        headers = headers.orEmpty(),
        sourceId = sourceId,
        tier = MatchTier.of(matchTier),
        matchScore = matchScore,
        trust = LinkUpdatePlanner.hostTrust(url, trustBySource[sourceId] ?: DEFAULT_TRUST, officialHosts),
        status = StreamStatus.of(status),
        latencyMs = latencyMs,
        height = height,
        failureReason = failureReason,
        checkedAt = checkedAt,
        lastOkAt = lastOkAt,
        failureCount = failureCount,
        firstSeenAt = firstSeenAt,
    )

    private fun StreamRecord.toEntity(canonicalId: String, position: Int) = ChannelStream(
        canonicalId = canonicalId,
        url = url,
        position = position,
        headers = headers.takeIf { it.isNotEmpty() },
        sourceId = sourceId,
        matchTier = tier.code,
        matchScore = matchScore,
        status = status.value,
        latencyMs = latencyMs,
        height = height,
        failureReason = failureReason,
        checkedAt = checkedAt,
        lastOkAt = lastOkAt,
        failureCount = failureCount,
        firstSeenAt = firstSeenAt,
    )

    private fun ChannelStream.toDiagnostics() = StreamDiagnostics(
        url = url,
        position = position,
        sourceId = sourceId,
        tier = MatchTier.of(matchTier),
        score = matchScore,
        status = StreamStatus.of(status),
        latencyMs = latencyMs,
        height = height,
        failureReason = failureReason,
        checkedAt = checkedAt,
        lastOkAt = lastOkAt,
        failureCount = failureCount,
        hasHeaders = !headers.isNullOrEmpty(),
    )

    private companion object {
        const val REGISTRY_FILE = "channels.json"
        const val SOURCES_FILE = "sources.json"
        const val BUNDLED_PLAYLIST = "playlist.m3u"
        const val SELF_SOURCE_ID = "self"
        val OFFICIAL_SOURCES = setOf(OfficialPageScanner.SOURCE_OFFICIAL, OfficialPageScanner.SOURCE_YOUTUBE)
        const val DEFAULT_TRUST = 0.5
        val AUTO_UPDATE_INTERVAL_MILLIS = TimeUnit.HOURS.toMillis(24)
    }
}
