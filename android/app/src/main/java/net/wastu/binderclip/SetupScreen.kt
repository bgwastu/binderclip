package net.wastu.binderclip

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.wastu.binderclip.shizuku.ShizukuAccess
import net.wastu.binderclip.shizuku.ShizukuSetup

private enum class StepMark { Done, Current, Waiting }

private class SetupStep(
    val title: Int,
    val body: Int? = null,
    val mark: StepMark,
    val busy: Boolean = false,
    val action: Int? = null,
    val onAction: () -> Unit = {},
)

/** Full-screen setup for Phone to Mac copying. Every step re-derives from live state, so it advances on its own. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SetupScreen(state: AppState, onBack: () -> Unit, onCheckAgain: () -> Unit) {
    val context = LocalContext.current
    val shizuku by ShizukuAccess.status.collectAsState()
    val rootCapture = state.rootAvailable && state.automaticClipboardEnabled
    val step = CaptureSetup.step(
        paired = true,
        rootCapture = rootCapture,
        shizuku = shizuku,
        active = state.shizukuCaptureActive,
    )
    val done = rootCapture || step == CaptureStep.Working
    val steps = when {
        state.rootAvailable -> listOf(
            SetupStep(R.string.setup_root_title, R.string.setup_root_desc, StepMark.Done),
            SetupStep(
                R.string.setup_background_title, R.string.setup_background_desc,
                if (state.automaticClipboardEnabled) StepMark.Done else StepMark.Current,
                busy = !state.automaticClipboardEnabled,
                action = R.string.setup_check_again, onAction = onCheckAgain,
            ),
        )
        else -> shizukuSteps(context, step, state.shizukuCapturePaused, done)
    }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.capture_row_title)) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.back))
                }
            },
        )
    }) { insets ->
        Box(Modifier.fillMaxSize().padding(insets)) {
            Column(
                Modifier
                    .align(Alignment.TopCenter)
                    .widthIn(max = 600.dp)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                StatusCard(done)
                Card(
                    Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                ) {
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 20.dp)) {
                        steps.forEachIndexed { index, item -> StepRow(index + 1, item, last = index == steps.lastIndex) }
                    }
                }
                if (done) {
                    Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.close)) }
                } else if (!state.rootAvailable) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            stringResource(R.string.setup_root_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedButton(onClick = onCheckAgain) { Text(stringResource(R.string.setup_check_again)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusCard(done: Boolean) {
    val container = if (done) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer
    val content = if (done) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
    ) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = content.copy(alpha = 0.12f), modifier = Modifier.size(40.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        if (done) Icons.Outlined.CheckCircle else Icons.Outlined.ContentPaste,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (done) {
                    Text(stringResource(R.string.setup_done_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
                Text(stringResource(if (done) R.string.setup_done_desc else R.string.setup_intro), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

private fun shizukuSteps(context: Context, step: CaptureStep, paused: Boolean, done: Boolean): List<SetupStep> {
    val current = if (done) 4 else CaptureSetup.stepNumber(step) ?: if (paused) 2 else 4
    fun mark(number: Int) = when {
        number < current -> StepMark.Done
        number == current -> StepMark.Current
        else -> StepMark.Waiting
    }
    val connecting = step == CaptureStep.Connecting
    return listOf(
        SetupStep(R.string.setup_step_install, R.string.capture_install_desc.takeIf { current == 1 }, mark(1),
            action = R.string.capture_install_action.takeIf { current == 1 }, onAction = { perform(context, CaptureStep.Install) }),
        when {
            current == 2 && step == CaptureStep.Update ->
                SetupStep(R.string.capture_update_title, R.string.capture_update_desc, StepMark.Current,
                    action = R.string.capture_open_shizuku, onAction = { perform(context, CaptureStep.Update) })
            current == 2 && paused ->
                SetupStep(R.string.capture_paused_title, R.string.capture_paused_card_desc, StepMark.Current,
                    action = R.string.capture_open_shizuku, onAction = { perform(context, CaptureStep.Start) })
            else -> SetupStep(R.string.setup_step_start, R.string.capture_start_desc.takeIf { current == 2 }, mark(2),
                action = R.string.capture_open_shizuku.takeIf { current == 2 }, onAction = { perform(context, CaptureStep.Start) })
        },
        when {
            current == 3 && step == CaptureStep.Denied ->
                SetupStep(R.string.capture_denied_title, R.string.capture_denied_desc, StepMark.Current,
                    action = R.string.capture_open_shizuku, onAction = { perform(context, CaptureStep.Denied) })
            else -> SetupStep(R.string.setup_step_allow, R.string.capture_allow_desc.takeIf { current == 3 }, mark(3),
                action = R.string.capture_allow_action.takeIf { current == 3 }, onAction = { perform(context, CaptureStep.Allow) })
        },
    ).let { list ->
        if (connecting) list + SetupStep(R.string.capture_connecting_title, R.string.capture_connecting_desc, StepMark.Current, busy = true)
        else list
    }
}

@Composable
private fun StepRow(number: Int, step: SetupStep, last: Boolean) {
    val line = MaterialTheme.colorScheme.outlineVariant
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val container = when (step.mark) {
                StepMark.Done -> MaterialTheme.colorScheme.primary
                StepMark.Current -> MaterialTheme.colorScheme.primaryContainer
                StepMark.Waiting -> MaterialTheme.colorScheme.surfaceContainerHighest
            }
            Surface(shape = CircleShape, color = container, modifier = Modifier.size(32.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    when {
                        step.mark == StepMark.Done -> Icon(
                            Icons.Outlined.Check, contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(18.dp),
                        )
                        step.busy -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else -> Text(
                            number.toString(), style = MaterialTheme.typography.labelLarge,
                            color = if (step.mark == StepMark.Current) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (!last) Box(Modifier.width(2.dp).weight(1f).padding(vertical = 4.dp).background(line))
        }
        Column(
            Modifier.weight(1f).padding(bottom = if (last) 0.dp else 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(step.title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 4.dp),
                color = if (step.mark == StepMark.Waiting) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
            if (step.mark == StepMark.Current) {
                step.body?.let {
                    Text(stringResource(it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                step.action?.let {
                    FilledTonalButton(onClick = step.onAction) { Text(stringResource(it)) }
                }
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
