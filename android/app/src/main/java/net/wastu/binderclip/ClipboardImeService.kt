package net.wastu.binderclip

import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

object ImeBridge {
    @Volatile var onAvailabilityChanged: (() -> Unit)? = null

    fun isEnabled(context: Context): Boolean = runCatching {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return false
        val list = imm.enabledInputMethodList ?: return false
        list.any { it.packageName == context.packageName }
    }.getOrDefault(false)

    fun isSelected(context: Context): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= 34) {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.currentInputMethodInfo?.packageName == context.packageName
        } else {
            val expected = ComponentName(context, ClipboardImeService::class.java).flattenToString()
            val current = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ComponentName.unflattenFromString(current ?: "")?.flattenToString() == expected
        }
    }.getOrDefault(false)

    fun openImeSettings(context: Context) {
        val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }

    fun openImePicker(context: Context) {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showInputMethodPicker()
    }
}

/**
 * Optional Input Method Editor (IME) helper for non-root devices.
 * Android OS explicitly grants the default/active IME full background clipboard access
 * (including OnPrimaryClipChangedListener and primaryClip reading) without root or ADB.
 */
class ClipboardImeService : InputMethodService() {
    private lateinit var clipboard: ClipboardManager
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener(::forwardClipboard)

    override fun onCreate() {
        super.onCreate()
        clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.addPrimaryClipChangedListener(clipListener)
        BinderClipService.startIfPaired(this)
        ImeBridge.onAvailabilityChanged?.invoke()
    }

    override fun onDestroy() {
        if (::clipboard.isInitialized) {
            clipboard.removePrimaryClipChangedListener(clipListener)
        }
        ImeBridge.onAvailabilityChanged?.invoke()
        super.onDestroy()
    }

    override fun onStartInput(attribute: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        ImeBridge.onAvailabilityChanged?.invoke()
    }

    private fun forwardClipboard() {
        android.util.Log.d("BinderClipIME", "forwardClipboard triggered! hasClip=${clipboard.hasPrimaryClip()}")
        val clip = clipboard.primaryClip
        android.util.Log.d("BinderClipIME", "clip=$clip, text=${clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text}")
        val payload = ImageClipboard.read(this, clipboard)?.let(AccessibilityClipboard::Image)
            ?: clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
                ?.takeIf { it.isNotBlank() }?.let(AccessibilityClipboard::Text)
        payload?.let {
            android.util.Log.i("BinderClipIME", "Forwarding clipboard from IME: $it")
            AccessibilityClipboardBridge.onClipboard?.invoke(it)
        }
    }

    override fun onCreateInputView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF1E1E1E.toInt())
            setPadding(dp(16), dp(12), dp(16), dp(16))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(12))
        }

        val icon = ImageView(this).apply {
            setImageResource(R.drawable.ic_binder_clip)
            setColorFilter(0xFF80CBC4.toInt())
            layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).apply {
                marginEnd = dp(8)
            }
        }

        val title = TextView(this).apply {
            text = "BinderClip Sync Keyboard"
            setTextColor(Color.WHITE)
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val switchBtn = Button(this).apply {
            text = "Switch Keyboard"
            textSize = 12f
            setTextColor(Color.WHITE)
            background = roundedDrawable(0xFF37474F.toInt(), dp(16))
            setPadding(dp(12), dp(4), dp(12), dp(4))
            setOnClickListener {
                if (Build.VERSION.SDK_INT >= 28) {
                    switchToNextInputMethod(false)
                } else {
                    ImeBridge.openImePicker(this@ClipboardImeService)
                }
            }
        }

        header.addView(icon)
        header.addView(title)
        header.addView(switchBtn)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val sendBtn = Button(this).apply {
            text = "Send to Mac"
            textSize = 13f
            setTextColor(Color.WHITE)
            background = roundedDrawable(0xFF00695C.toInt(), dp(12))
            layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                marginEnd = dp(8)
            }
            setOnClickListener {
                forwardClipboard()
                ContextCompat.startForegroundService(
                    this@ClipboardImeService,
                    Intent(this@ClipboardImeService, BinderClipService::class.java)
                        .setAction(BinderClipService.ACTION_SEND_CURRENT),
                )
            }
        }

        val pasteBtn = Button(this).apply {
            text = "Paste from Clipboard"
            textSize = 13f
            setTextColor(Color.WHITE)
            background = roundedDrawable(0xFF263238.toInt(), dp(12))
            layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f)
            setOnClickListener {
                val clipText = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
                if (!clipText.isNullOrEmpty()) {
                    currentInputConnection?.commitText(clipText, 1)
                }
            }
        }

        actions.addView(sendBtn)
        actions.addView(pasteBtn)

        root.addView(header)
        root.addView(actions)
        return root
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun roundedDrawable(color: Int, radius: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.toFloat()
        }
}
