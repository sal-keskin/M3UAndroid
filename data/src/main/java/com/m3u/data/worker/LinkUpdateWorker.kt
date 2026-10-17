package com.m3u.data.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.m3u.data.repository.linkupdate.LinkUpdateProgress
import com.m3u.data.repository.linkupdate.LinkUpdateReport
import com.m3u.data.repository.linkupdate.LinkUpdateRepository
import com.m3u.data.repository.linkupdate.LinkUpdateRequest
import com.m3u.data.repository.linkupdate.LinkUpdateTrigger
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import timber.log.Timber

/**
 * Runs a registry link update (every channel) or a repair (one channel) and publishes progress.
 */
@HiltWorker
class LinkUpdateWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: LinkUpdateRepository,
) : CoroutineWorker(context, params) {
    private val timber = Timber.tag("LinkUpdateWorker")

    override suspend fun doWork(): Result {
        val trigger = inputData.getString(KEY_TRIGGER)
            ?.let { name -> LinkUpdateTrigger.entries.firstOrNull { it.name == name } }
            ?: LinkUpdateTrigger.MANUAL
        val channelId = inputData.getString(KEY_CHANNEL_ID)
        return try {
            val report = repository.run(LinkUpdateRequest(trigger = trigger, channelId = channelId)) { progress ->
                setProgress(
                    workDataOf(
                        KEY_PHASE to progress.phase.name,
                        KEY_DONE to progress.done,
                        KEY_TOTAL to progress.total,
                        KEY_CHANNEL_ID to channelId,
                    )
                )
            }
            Result.success(workDataOf(KEY_REPORT to Json.encodeToString(LinkUpdateReport.serializer(), report)))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (exception: Exception) {
            timber.e(exception, "link update failed")
            if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val TAG = "link-update"
        const val UNIQUE_UPDATE = "link-update"
        const val UNIQUE_WEEKLY = "link-update-weekly"
        const val UNIQUE_REPAIR_PREFIX = "link-repair:"

        private const val KEY_TRIGGER = "trigger"
        private const val KEY_CHANNEL_ID = "channel-id"
        private const val KEY_PHASE = "phase"
        private const val KEY_DONE = "done"
        private const val KEY_TOTAL = "total"
        private const val KEY_REPORT = "report"
        private const val MAX_RETRIES = 2

        fun input(trigger: LinkUpdateTrigger, channelId: String? = null): Data = workDataOf(
            KEY_TRIGGER to trigger.name,
            KEY_CHANNEL_ID to channelId,
        )

        fun reportOf(info: WorkInfo): LinkUpdateReport? = info.outputData.getString(KEY_REPORT)
            ?.let { runCatching { Json.decodeFromString(LinkUpdateReport.serializer(), it) }.getOrNull() }

        fun channelIdOf(info: WorkInfo): String? = info.progress.getString(KEY_CHANNEL_ID)

        fun progressOf(info: WorkInfo): LinkUpdateProgress.Running {
            val progress = info.progress
            val phase = progress.getString(KEY_PHASE)
                ?.let { name -> LinkUpdateProgress.Phase.entries.firstOrNull { it.name == name } }
                ?: LinkUpdateProgress.Phase.FETCHING
            return LinkUpdateProgress.Running(
                phase = phase,
                done = progress.getInt(KEY_DONE, 0),
                total = progress.getInt(KEY_TOTAL, 0),
                channelId = progress.getString(KEY_CHANNEL_ID),
            )
        }
    }
}
