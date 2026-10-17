package com.m3u.tv

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import androidx.hilt.work.HiltWorkerFactory
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.work.Configuration
import androidx.work.WorkManager
import com.m3u.data.repository.linkupdate.LinkUpdateRepository
import com.m3u.data.worker.ExtensionPluginBootstrapWorker
import com.m3u.data.worker.PersistedUriPermissionCleanupWorker
import com.m3u.data.worker.ProviderCredentialRecoveryWorker
import com.m3u.data.worker.ProviderSessionCleanupWorker
import com.m3u.data.worker.initializePersistedUriPermissionLeases
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

@HiltAndroidApp
class M3UApplication : Application(), Configuration.Provider, ImageLoaderFactory {
    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var linkUpdateRepository: LinkUpdateRepository

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
        .build()

    private companion object {
        val IMAGE_USER_AGENT =
            "TR-TV/${BuildConfig.VERSION_NAME} (Android TV IPTV player; +https://github.com/sal-keskin/iptv)"
    }
}
