package fr.halo720.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import fr.halo720.app.ui.GalleryPage
import fr.halo720.app.ui.HaloTheme

/** Temporaire (copie galerie) : affiche la galerie seule, pour compiler et essayer. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { HaloTheme { GalleryPage(onClose = { finish() }) } }
    }
}
