package net.wastu.binderclip

import net.wastu.binderclip.shizuku.ShizukuStatus

/** One next action at a time; every step re-derives from live state, so it advances on its own. */
enum class CaptureStep { Hidden, Install, Start, Update, Allow, Denied, Connecting, Working }

object CaptureSetup {
    fun step(paired: Boolean, rootCapture: Boolean, shizuku: ShizukuStatus, active: Boolean): CaptureStep = when {
        !paired || rootCapture -> CaptureStep.Hidden
        active -> CaptureStep.Working
        else -> when (shizuku) {
            ShizukuStatus.NotInstalled -> CaptureStep.Install
            ShizukuStatus.NotRunning -> CaptureStep.Start
            ShizukuStatus.Outdated -> CaptureStep.Update
            ShizukuStatus.NotAllowed -> CaptureStep.Allow
            ShizukuStatus.Denied -> CaptureStep.Denied
            ShizukuStatus.Ready -> CaptureStep.Connecting
        }
    }

    /** Steps 1–3 are install, start, allow; recovery states reuse the step they repair. */
    fun stepNumber(step: CaptureStep): Int? = when (step) {
        CaptureStep.Install -> 1
        CaptureStep.Start, CaptureStep.Update -> 2
        CaptureStep.Allow, CaptureStep.Denied -> 3
        else -> null
    }
}
