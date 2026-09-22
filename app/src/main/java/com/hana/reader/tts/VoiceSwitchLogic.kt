package com.hana.reader.tts

/**
 * Pure helpers for Voices select / download / warm-prepare decisions (unit-tested, no JNI).
 */
object VoiceSwitchLogic {
    enum class SelectAction {
        /** Pack on disk — tear down old engine and prepare the new voice. */
        SwitchVoice,
        /** Pack missing — start download only; do not touch OfflineTts. */
        DownloadOnly,
    }

    fun onSelectAction(packReady: Boolean): SelectAction =
        if (packReady) SelectAction.SwitchVoice else SelectAction.DownloadOnly

    /** After a pack download finishes, only warm-prepare if it is the selected voice's pack. */
    fun shouldWarmPrepareAfterDownload(
        finishedStorageKey: String,
        selectedVoiceId: String?,
    ): Boolean {
        val pack = TtsPacks.packForVoice(selectedVoiceId) ?: return false
        return pack.storageKey == finishedStorageKey
    }

    /** Stale synth results must be dropped when synthEpoch advances. */
    fun acceptSynthResult(epochAtStart: Int, epochNow: Int): Boolean =
        epochAtStart == epochNow

    /** Queue fill may only synth for the active pack. */
    fun maySynthForQueue(queuePackId: String?, activePackId: String): Boolean =
        queuePackId == null || queuePackId == activePackId
}
