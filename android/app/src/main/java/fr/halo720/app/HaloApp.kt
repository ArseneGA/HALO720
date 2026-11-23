package fr.halo720.app

import android.app.Application
import fr.halo720.app.data.PhotoStore
import fr.halo720.app.data.Profiles
import fr.halo720.app.net.CameraLink
import fr.halo720.app.net.MjpegStream
import fr.halo720.app.sync.SyncEngine
import fr.halo720.app.sync.SyncService

class HaloApp : Application() {
    lateinit var link: CameraLink
    lateinit var store: PhotoStore
    lateinit var sync: SyncEngine
    lateinit var stream: MjpegStream
    lateinit var profiles: Profiles
    lateinit var prefs: Prefs

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        link = CameraLink(this)
        store = PhotoStore(this)
        sync = SyncEngine(this, link, store)
        stream = MjpegStream(link)
        profiles = Profiles(this)
        SyncService.channel(this)
        link.start()
        sync.start()
    }
}
