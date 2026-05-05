package com.tuapp.translatoroverlay

import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.*
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class OverlayService : Service(),
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {

    // --- Lifecycle boilerplate para ComposeView fuera de Activity ---
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: ComposeView
    private var translatedText by mutableStateOf<String?>(null)
    private var isLocked by mutableStateOf(true)
    private var activePanel by mutableStateOf<String?>(null)

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate() {
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        super.onCreate()

        // Iniciar foreground inmediatamente (obligatorio Android 8+)
        startForeground(CaptureManager.NOTIFICATION_ID, CaptureManager.buildForegroundNotification(this))

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createOverlayWindow()
        startCapture()
    }

    private fun createOverlayWindow() {
        val params = WindowManager.LayoutParams(
            320.dpToPx(), 320.dpToPx(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 100
        params.y = 300

        overlayView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@OverlayService)
            setViewTreeViewModelStoreOwner(this@OverlayService)
            setViewTreeSavedStateRegistryOwner(this@OverlayService)
            setContent {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    FloatingCircleUI(
                        isLocked = isLocked,
                        activePanel = activePanel,
                        translatedText = translatedText,
                        onLockToggle = { isLocked = !isLocked },
                        onPanelToggle = { panel ->
                            activePanel = if (activePanel == panel) null else panel
                        },
                        onDrag = { dx, dy ->
                            if (!isLocked) {
                                params.x = (params.x + dx).toInt().coerceAtLeast(0)
                                params.y = (params.y + dy).toInt().coerceAtLeast(0)
                                windowManager.updateViewLayout(overlayView, params)
                            }
                        }
                    )
                }
            }
        }

        windowManager.addView(overlayView, params)
    }

    private fun startCapture() {
        CaptureManager.startPeriodicCapture { bitmap ->
            CoroutineScope(Dispatchers.IO).launch {
                val result = TranslationEngine.processFrame(bitmap)
                if (result != null) {
                    translatedText = result
                }
            }
        }
    }

    private fun Int.dpToPx() = (this * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        CaptureManager.stopCapture()
        TranslationEngine.close()
        if (::overlayView.isInitialized) {
            windowManager.removeView(overlayView)
        }
        store.clear()
        super.onDestroy()
    }
}

@Composable
fun FloatingCircleUI(
    isLocked: Boolean,
    activePanel: String?,
    translatedText: String?,
    onLockToggle: () -> Unit,
    onPanelToggle: (String) -> Unit,
    onDrag: (Float, Float) -> Unit
) {
    Box(
        modifier = Modifier
            .size(320.dp)
            .pointerInput(isLocked) {
                if (!isLocked) {
                    detectDragGestures { _, dragAmount ->
                        onDrag(dragAmount.x, dragAmount.y)
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        // Anillo principal
        Box(
            modifier = Modifier
                .size(280.dp)
                .background(Color(0xCC0A0A0F), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            // Centro
            Box(
                modifier = Modifier
                    .size(100.dp)
                    .background(Color(0xFF111115), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text("👆", fontSize = 24.sp)
            }

            // Botones posicionados en 12, 4 y 8
            ActionButton(clockPos = 12, icon = "🌐", label = "Traducir") { onPanelToggle("translate") }
            ActionButton(clockPos = 4, icon = "⚙️", label = "Ajustes") { onPanelToggle("settings") }
            ActionButton(
                clockPos = 8,
                icon = if (isLocked) "🔒" else "🔓",
                label = if (isLocked) "Bloqueado" else "Libre",
                isActive = !isLocked
            ) { onLockToggle() }
        }

        // Paneles
        if (activePanel == "translate") {
            PanelCard(
                title = "Traducción",
                content = translatedText ?: "Esperando texto en pantalla...",
                onClose = { onPanelToggle("translate") }
            )
        } else if (activePanel == "settings") {
            PanelCard(
                title = "Ajustes",
                content = "Idioma destino: Español\nCaptura cada: 2.5s",
                onClose = { onPanelToggle("settings") }
            )
        }
    }
}

@Composable
fun ActionButton(
    clockPos: Int,
    icon: String,
    label: String,
    isActive: Boolean = false,
    onClick: () -> Unit
) {
    val offsetX = when (clockPos) { 12 -> 0f; 4 -> 110f; 8 -> -110f; else -> 0f }
    val offsetY = when (clockPos) { 12 -> -130f; 4 -> 65f; 8 -> 65f; else -> 0f }

    Box(
        modifier = Modifier
            .offset(offsetX.dp, offsetY.dp)
            .size(48.dp)
            .background(
                if (isActive) Color(0xFF2A2A3F) else Color(0xFF1A1A25),
                CircleShape
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(icon, fontSize = 18.sp)
            Text(label, color = Color.Gray, fontSize = 8.sp)
        }
    }
}

@Composable
fun PanelCard(title: String, content: String, onClose: () -> Unit) {
    Surface(
        modifier = Modifier
            .width(280.dp)
            .offset(y = (-230).dp),
        shape = MaterialTheme.shapes.medium,
        color = Color(0xEE15151A),
        shadowElevation = 12.dp
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(
                    "✕",
                    color = Color.Gray,
                    fontSize = 14.sp,
                    modifier = Modifier.clickable { onClose() }
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(content, color = Color.LightGray, fontSize = 12.sp)
        }
    }
}
