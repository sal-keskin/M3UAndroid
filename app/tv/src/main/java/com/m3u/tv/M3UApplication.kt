package com.m3u.tv

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import androidx.hilt.work.HiltWorkerFactory
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.work.Configuration
import androidx.work.WorkManager
import com.m3u.data.repository.linkupdate.LinkUpdateRepository
import com.m3u.data.service.PlayerManager
import com.m3u.data.worker.ExtensionPluginBootstrapWorker
import com.m3u.data.worker.PersistedUriPermissionCleanupWorker
import com.m3u.data.worker.ProviderCredentialRecoveryWorker
import com.m3u.data.worker.ProviderSessionCleanupWorker
import com.m3u.data.worker.initializePersistedUriPermissionLeases
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

@HiltAndroidApp
class M3UApplication : Application(), Configuration.Provider, ImageLoaderFactory {
    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var linkUpdateRepository: LinkUpdateRepository

    @Inject
    lateinit var playerManager: PlayerManager

    override fun onCreate() {
        super.onCreate()
        initializePersistedUriPermissionLeases(this)
        PersistedUriPermissionCleanupWorker.enqueueRecovery(
            WorkManager.getInstance(this)
        )
        ProviderCredentialRecoveryWorker.enqueue(WorkManager.getInstance(this))
        ProviderSessionCleanupWorker.enqueue(
            workManager = WorkManager.getInstance(this),
        )
        ExtensionPluginBootstrapWorker.enqueue(WorkManager.getInstance(this))
        // first launch: seed the lineup from the bundled registry snapshot, then keep links fresh
        ProcessLifecycleOwner.get().lifecycleScope.launch {
            linkUpdateRepository.bootstrap()
            linkUpdateRepository.scheduleAutomaticUpdates()
        }
        // TR TV has no downloads. Builds up to 1.0.1-trtv.1 kept timeshift segments in the shared
        // download cache (up to 2 GB); empty it. It stays empty afterwards, so this is cheap.
        ProcessLifecycleOwner.get().lifecycleScope.launch(Dispatchers.IO) {
            playerManager.clearCache()
        }
    }

    override val workManagerConfiguration: Configuration by lazy {
        Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
    }

    // channel logos often live on Wikimedia, which rejects anonymous client user agents
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient {
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    chain.proceed(
                        chain.request()
                            .newBuilder()
                            .header("User-Agent", IMAGE_USER_AGENT)
                            .build()
                    )
                }
                .build()
        }
        // logos only: a small cap instead of Coil's default of 2 % of the disk
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDir.resolve("image_cache"))
                .maxSizeBytes(IMAGE_DISK_CACHE_BYTES)
                .build()
        }
        .build()

    private companion object {
        const val IMAGE_DISK_CACHE_BYTES = 32L * 1024 * 1024

        val IMAGE_USER_AGENT =
            "TR-TV/${BuildConfig.VERSION_NAME} (Android TV IPTV player; +https://github.com/sal-keskin/iptv)"
    }
}
