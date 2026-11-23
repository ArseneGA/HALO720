package fr.halo720.app

import android.Manifest
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.Surface
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import fr.halo720.app.ui.GalleryPage
import fr.halo720.app.ui.Halo
import fr.halo720.app.ui.HaloTheme
import fr.halo720.app.ui.OrientLock
import fr.halo720.app.ui.SettingsPage
import fr.halo720.app.ui.ShutterBus
import fr.halo720.app.ui.ViewfinderScreen
import fr.halo720.app.ui.cam
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private val shutterBus = ShutterBus()
    private var rotation by mutableIntStateOf(Surface.ROTATION_0)
    private var landscape by mutableStateOf(false)
    private var lock by mutableStateOf(OrientLock.AUTO)
    private var page by mutableStateOf<String?>(null)        // null = visée, "gal", "set"
    private var galStart by mutableStateOf<String?>(null)
    private var orientHint by mutableStateOf<Pair<String, Long>?>(null)
    private val app get() = application as HaloApp

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.BLACK), SystemBarStyle.dark(android.graphics.Color.BLACK))
        super.onCreate(savedInstanceState)
        // pas d'animation de toute l'interface à la rotation : la visée se recale sans clignoter
        window.attributes = window.attributes.apply {
            rotationAnimation = WindowManager.LayoutParams.ROTATION_ANIMATION_SEAMLESS
        }
        lock = when (getSharedPreferences("halo", MODE_PRIVATE).getString("lock", "auto")) {
            "land" -> OrientLock.LANDSCAPE; "port" -> OrientLock.PORTRAIT; else -> OrientLock.AUTO
        }
        applyLock(false)
        readRotation()
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        setContent {
            HaloTheme {
                Box(Modifier.fillMaxSize().background(Halo.Bg)) {
                    // la visée ne se met jamais en veille
                    LaunchedEffect(page) {
                        if (page == null) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                    ViewfinderScreen(
                        imageRot = imageRot(), landscape = landscape, lock = lock,
                        onLockTap = { lockTap() }, onLockLong = { changeLock(OrientLock.AUTO) },
                        onOpenGallery = { galStart = it; page = "gal" },
                        onOpenSettings = { page = "set" },
                        shutterBus = shutterBus, pagesOpen = page != null,
                    )
                    AnimatedVisibility(page == "set", enter = slideInVertically(tween(320)) { it }, exit = slideOutVertically(tween(260)) { it }) {
                        SettingsPage(onClose = { page = null }, landscape = landscape, onLock = { changeLock(it) })
                    }
                    AnimatedVisibility(page == "gal", enter = slideInVertically(tween(320)) { it }, exit = slideOutVertically(tween(260)) { it }) {
                        GalleryPage(onClose = { page = null }, startStem = galStart)
                    }
                    if (page != null) BackHandler { page = null }
                    // format affiché une seconde à chaque changement d'orientation
                    val h = orientHint
                    LaunchedEffect(h) { if (h != null) { delay(1100); if (orientHint === h) orientHint = null } }
                    AnimatedVisibility(h != null && page == null, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.Center)) {
                        Text(h?.first ?: "", style = cam(12.sp), modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Halo.GlassStrong).padding(horizontal = 11.dp, vertical = 6.dp))
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        app.link.start()
        app.sync.poke()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val before = landscape
        readRotation()
        if (before != landscape) orientHint = (if (landscape) "Paysage 16:9" else "Portrait 9:16") to System.nanoTime()
    }

    @Suppress("DEPRECATION")
    private fun readRotation() {
        rotation = windowManager.defaultDisplay.rotation
        landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    }

    /**
     * Rotation de l'image du capteur pour l'affichage (et orientation de la photo) :
     * la caméra tourne avec le téléphone ; en paysage « normal » (haut du téléphone à gauche),
     * l'image du capteur est droite.
     */
    private fun imageRot(): Int = when (rotation) {
        Surface.ROTATION_90 -> 0
        Surface.ROTATION_0 -> 90
        Surface.ROTATION_270 -> 180
        else -> 270
    }

    private fun lockTap() {
        when (lock) {
            OrientLock.AUTO -> changeLock(if (landscape) OrientLock.LANDSCAPE else OrientLock.PORTRAIT)
            OrientLock.LANDSCAPE -> changeLock(OrientLock.PORTRAIT)
            OrientLock.PORTRAIT -> changeLock(OrientLock.LANDSCAPE)
        }
    }

    private fun changeLock(l: OrientLock) {
        lock = l
        getSharedPreferences("halo", MODE_PRIVATE).edit().putString("lock",
            when (l) { OrientLock.LANDSCAPE -> "land"; OrientLock.PORTRAIT -> "port"; else -> "auto" }).apply()
        applyLock(true)
    }

    private fun applyLock(announce: Boolean) {
        requestedOrientation = when (lock) {
            OrientLock.LANDSCAPE -> if (rotation == Surface.ROTATION_270) ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            OrientLock.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            OrientLock.AUTO -> ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        }
        if (announce) orientHint = when (lock) {
            OrientLock.AUTO -> "Orientation auto"
            OrientLock.LANDSCAPE -> "Paysage verrouillé · 16:9"
            OrientLock.PORTRAIT -> "Portrait verrouillé · 9:16"
        } to System.nanoTime()
    }

    // touches volume = déclencheur (appui = déclenche, relâcher = fin de rafale / pose B)
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) && page == null && app.prefs.aids.value.volumeKeys) {
            if (event.repeatCount == 0) shutterBus.events.tryEmit(true)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) && page == null && app.prefs.aids.value.volumeKeys) {
            shutterBus.events.tryEmit(false)
            return true
        }
        return super.onKeyUp(keyCode, event)
    }
}
