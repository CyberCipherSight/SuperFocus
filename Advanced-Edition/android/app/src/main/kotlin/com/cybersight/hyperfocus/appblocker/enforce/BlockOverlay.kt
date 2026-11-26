package com.cybersight.hyperfocus.appblocker.enforce

import com.cybersight.hyperfocus.appblocker.BuildConfig
import com.cybersight.hyperfocus.appblocker.PlayPrivacy

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.util.Log
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.cybersight.hyperfocus.appblocker.MainActivity
import com.cybersight.hyperfocus.appblocker.protection.ProtectionPolicy
import com.cybersight.hyperfocus.appblocker.session.SessionStore
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

/** Native artwork does not require a running Flutter engine or network. */
object BlockOverlay {
    enum class Reason { SESSION, NIGHT, APP_LIMIT }

    @Volatile private var view: View? = null
    @Volatile private var shownFor: String? = null
    private var shownReason: Reason? = null
    private var atlas: Bitmap? = null
    private val handler = Handler(Looper.getMainLooper())
    private var tick: Runnable? = null
    private var shownAt = 0L
    private var launcherPackage: String? = null
    private var restingOnLauncher = false

    fun show(callerContext: Context, blockedPackage: String, reason: Reason = Reason.SESSION) {
        val context = callerContext.applicationContext
        if (!PlayPrivacy.permitted(context, "overlay") || !Settings.canDrawOverlays(context)) return
        restingOnLauncher = false
        if (shownFor == blockedPackage && shownReason == reason && view != null) return
        hide(context)
        launcherPackage = context.packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0
        )?.activityInfo?.packageName
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val preferences = context.getSharedPreferences("focusbro.appearance", Context.MODE_PRIVATE)
        val mode = preferences.getString("mode", "dark")
        val dark = mode == "dark" || (mode == "system" &&
            context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES)
        val ink = Color.parseColor(if (dark) "#F5F1FF" else "#201C35")
        val muted = Color.parseColor(if (dark) "#C6C0D7" else "#595268")
        val accent = Color.parseColor(if (dark) "#BEABFF" else "#6742C5")
        fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()
        fun text(size: Float, color: Int) = TextView(context).apply {
            textSize = size; setTextColor(color); gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(8))
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(dp(28), dp(36), dp(28), dp(36))
        }
        val root = ScrollView(context).apply {
            isFillViewport = true
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                if (dark) intArrayOf(Color.rgb(16, 19, 34), Color.rgb(35, 26, 57), Color.rgb(16, 32, 37))
                else intArrayOf(Color.rgb(250, 248, 255), Color.rgb(236, 229, 251), Color.rgb(227, 243, 237)))
            addView(content, FrameLayout.LayoutParams(-1, -1))
        }
        val appLabel = runCatching {
            context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(blockedPackage, 0)).toString()
        }.getOrDefault("This app")
        content.addView(text(12f, accent).apply { text = "F O C U S B R O  /  TAKE A MOMENT" })
        val illustration = Portrait(context)
        content.addView(illustration, LinearLayout.LayoutParams(dp(220), dp(220)).apply { gravity = Gravity.CENTER })
        val title = text(27f, ink).apply { setTypeface(typeface, android.graphics.Typeface.BOLD) }
        val body = text(16f, muted)
        val deadline = text(24f, accent).apply { setTypeface(typeface, android.graphics.Typeface.BOLD) }
        content.addView(title); content.addView(body); content.addView(deadline)
        content.addView(text(13f, muted).apply { text = "$appLabel · Other active focus and night rules still apply." })
        content.addView(Button(context).apply {
            text = "Back to FocusBro"; isAllCaps = false; setTextColor(if (dark) Color.rgb(32, 24, 55) else Color.WHITE)
            background = GradientDrawable().apply { setColor(accent); cornerRadius = dp(18).toFloat() }
            setPadding(dp(20), dp(14), dp(20), dp(14))
            setOnClickListener {
                runCatching { context.startActivity(Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)) }
            }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24) })

        fun time(until: Long): String {
            val date = Date(until)
            val today = Calendar.getInstance()
            val target = Calendar.getInstance().apply { timeInMillis = until }
            val sameDay = today.get(Calendar.YEAR) == target.get(Calendar.YEAR) &&
                today.get(Calendar.DAY_OF_YEAR) == target.get(Calendar.DAY_OF_YEAR)
            return (if (sameDay) "" else "${DateFormat.getDateInstance(DateFormat.SHORT).format(date)} · ") +
                DateFormat.getTimeInstance(DateFormat.SHORT).format(date)
        }
        fun update() {
            when (reason) {
                Reason.NIGHT -> {
                    title.text = "Let the day settle."
                    body.text = "$appLabel is resting for the night. Night blocking ends at"
                    deadline.text = time(Calendar.getInstance().apply {
                        set(Calendar.HOUR_OF_DAY, ProtectionPolicy.NIGHT_END_HOUR)
                        set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                        if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
                    }.timeInMillis)
                    illustration.index = 3
                }
                Reason.SESSION -> {
                    title.text = "Stay with your intention."
                    val remaining = SessionStore.remainingMs(context).coerceAtLeast(0)
                    val seconds = (remaining + 999) / 1000
                    body.text = "$appLabel is paused during your focus session."
                    deadline.text = "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')} left"
                    illustration.index = 1
                }
                Reason.APP_LIMIT -> {
                    val p = context.getSharedPreferences("focusbro.appLimits", Context.MODE_PRIVATE)
                    val state = BlockPresentation.limits(System.currentTimeMillis(),
                        p.getLong("exhausted:$blockedPackage", 0), p.getLong("until:$blockedPackage", 0),
                        p.getLong("breakUntil:$blockedPackage", 0))
                    title.text = state.title; body.text = state.message
                    deadline.text = if (state.until > System.currentTimeMillis()) time(state.until) else ""
                    illustration.index = state.portrait
                }
            }
            illustration.invalidate()
        }
        update()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        val params = WindowManager.LayoutParams(-1, -1, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.OPAQUE)
        runCatching { wm.addView(root, params) }.onFailure {
            Log.w("FocusBroOverlay", "Could not attach block illustration", it)
        }.onSuccess {
            if (!BuildConfig.PLAY_EDITION) Log.d("FocusBroOverlay", "Shown for $blockedPackage: $reason")
            view = root; shownFor = blockedPackage; shownReason = reason
            shownAt = SystemClock.elapsedRealtime()
            val reduced = preferences.getBoolean("reduceMotion", false) || !ValueAnimator.areAnimatorsEnabled()
            if (!reduced) illustration.apply {
                alpha = 0f; translationY = dp(10).toFloat()
                animate().alpha(1f).translationY(0f).setDuration(350).start()
            }
            tick = object : Runnable {
                override fun run() {
                    if (view !== root) return
                    if (restingOnLauncher && !BlockPresentation.retainOnLauncher(true,
                            SystemClock.elapsedRealtime() - shownAt)) {
                        hide(context)
                        return
                    }
                    update(); handler.postDelayed(this, 1000)
                }
            }.also { handler.postDelayed(it, 1000) }
        }
    }

    /** Keep the illustrated notice briefly after ejection, with safe apps taking priority. */
    fun onAllowedForeground(context: Context, packageName: String, className: String? = null) {
        if (view == null) return
        // Adding our ScrollView emits a window event attributed to FocusBro.
        // It is not an activity switch. Real activity resumes are also observed
        // independently by ForegroundWatcher, which supplies no className.
        if (packageName == context.packageName && className != null &&
            className != MainActivity::class.java.name) return
        restingOnLauncher = packageName == launcherPackage
        if (!BuildConfig.PLAY_EDITION) Log.d("FocusBroOverlay", "Allowed foreground=$packageName launcher=$launcherPackage age=${SystemClock.elapsedRealtime() - shownAt}")
        if (!BlockPresentation.retainOnLauncher(restingOnLauncher,
                SystemClock.elapsedRealtime() - shownAt)) hide(context)
    }

    fun hide(callerContext: Context) {
        tick?.let { handler.removeCallbacks(it) }; tick = null
        val current = view ?: return
        current.animate().cancel()
        val context = callerContext.applicationContext
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        runCatching { wm.removeView(current) }
        view = null; shownFor = null; shownReason = null
        restingOnLauncher = false
    }

    private class Portrait(context: Context) : View(context) {
        var index: Int = 1
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        init {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            if (atlas == null) atlas = runCatching {
                context.assets.open("flutter_assets/assets/illustrations/companion-atlas.png").use {
                    BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 2 })
                }
            }.getOrNull()
        }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val image = atlas ?: return
            val w = image.width / 3; val h = image.height / 2
            val source = Rect(index % 3 * w, index / 3 * h, (index % 3 + 1) * w, (index / 3 + 1) * h)
            val side = minOf(width, height).toFloat()
            val target = RectF((width - side) / 2, (height - side) / 2, (width + side) / 2, (height + side) / 2)
            canvas.drawBitmap(image, source, target, paint)
        }
    }
}
