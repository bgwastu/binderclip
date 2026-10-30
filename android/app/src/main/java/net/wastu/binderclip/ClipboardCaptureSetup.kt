package net.wastu.binderclip

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.wastu.binderclip.shizuku.ShizukuAccess
import net.wastu.binderclip.shizuku.ShizukuSetup
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

    private const val PREFS = "capture_setup"
    private const val DISMISSED = "dismissed"
    fun isDismissed(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(DISMISSED, false)
    fun setDismissed(context: Context, dismissed: Boolean) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(DISMISSED, dismissed).apply()
}

@Composable
internal fun ClipboardCaptureSetup(state: AppState, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val shizuku by ShizukuAccess.status.collectAsState()
    val step = CaptureSetup.step(
        paired = state.peer != null,
        rootCapture = state.rootAvailable && state.automaticClipboardEnabled,
        shizuku = shizuku,
        active = state.shizukuCaptureActive,
    )
    var dismissed by remember { mutableStateOf(CaptureSetup.isDismissed(context)) }
    when {
        // Once capture works the card simply goes away; there is nothing left to do.
        step == CaptureStep.Hidden || step == CaptureStep.Working -> Unit
        // A paused capture is never hidden behind "Not now"; it worked before and the owner expects it.
        dismissed && !state.shizukuCapturePaused -> ListItem(
            modifier = modifier,
            leadingContent = { Icon(Icons.Outlined.ContentPaste, contentDescription = null) },
            headlineContent = { Text(stringResource(R.string.capture_off_title)) },
            supportingContent = { Text(stringResource(R.string.capture_off_desc)) },
            trailingContent = {
                TextButton(onClick = { dismissed = false; CaptureSetup.setDismissed(context, false) }) {
                    Text(stringResource(R.string.capture_set_up))
                }
            },
        )
        else -> SetupCard(step, state.shizukuCapturePaused, modifier, onDismiss = {
            dismissed = true
            CaptureSetup.setDismissed(context, true)
        })
    }
}

@Composable
private fun SetupCard(step: CaptureStep, paused: Boolean, modifier: Modifier, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val (title, body, action) = when (step) {
        CaptureStep.Install -> Triple(R.string.capture_install_title, R.string.capture_install_desc, R.string.capture_install_action)
        CaptureStep.Start -> if (paused) Triple(R.string.capture_paused_title, R.string.capture_paused_card_desc, R.string.capture_open_shizuku)
            else Triple(R.string.capture_start_title, R.string.capture_start_desc, R.string.capture_open_shizuku)
        CaptureStep.Update -> Triple(R.string.capture_update_title, R.string.capture_update_desc, R.string.capture_open_shizuku)
        CaptureStep.Allow -> Triple(R.string.capture_allow_title, R.string.capture_allow_desc, R.string.capture_allow_action)
        CaptureStep.Denied -> Triple(R.string.capture_denied_title, R.string.capture_denied_desc, R.string.capture_open_shizuku)
        else -> Triple(R.string.capture_connecting_title, R.string.capture_connecting_desc, null)
    }
    OutlinedCard(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CaptureSetup.stepNumber(step)?.takeUnless { paused }?.let { number ->
                Text(
                    stringResource(R.string.capture_step_of, number, 3),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(stringResource(body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (action == null) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Button(onClick = { perform(context, step) }) { Text(stringResource(action)) }
                }
                Spacer(Modifier.weight(1f))
                if (!paused) TextButton(onClick = onDismiss) { Text(stringResource(R.string.capture_not_now)) }
            }
        }
    }
}

private fun perform(context: Context, step: CaptureStep) {
    when (step) {
        CaptureStep.Install -> {
            val store = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${ShizukuSetup.PACKAGE}"))
            val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/"))
            runCatching { context.startActivity(store) }.recoverCatching { context.startActivity(web) }
        }
        CaptureStep.Allow -> ShizukuAccess.requestPermission()
        else -> ShizukuAccess.launchIntent(context)?.let { runCatching { context.startActivity(it) } }
    }
}
