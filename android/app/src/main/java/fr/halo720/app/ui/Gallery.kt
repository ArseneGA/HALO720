package fr.halo720.app.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.halo720.app.HaloApp
import fr.halo720.app.data.Photo
import fr.halo720.app.data.RawState
import fr.halo720.app.net.Link
import fr.halo720.app.sync.SyncState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Galerie plein écran (monte par-dessus la visée ; l'appelant anime l'entrée).
 * startStem : ouvre directement le visualiseur sur cette photo (vignette de la visée).
 */
@Composable
fun GalleryPage(onClose: () -> Unit, startStem: String? = null, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as HaloApp
    val photosMap by app.store.photos.collectAsState()
    val sync by app.sync.state.collectAsState()
    val link by app.link.link.collectAsState()
    val online = link == Link.ONLINE
    val photos = remember(photosMap) { app.store.sorted() }
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    var viewerStem by rememberSaveable { mutableStateOf(startStem) }
    var editStem by rememberSaveable { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var expanded by rememberSaveable { mutableStateOf(listOf<String>()) }
    var confirmDelete by remember { mutableStateOf<List<String>?>(null) }
    var shareAsk by remember { mutableStateOf<List<String>?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(startStem) { if (startStem != null) viewerStem = startStem }
    LaunchedEffect(error) { if (error != null) { delay(3500); error = null } }

    BackHandler {
        when {
            editStem != null -> editStem = null
            viewerStem != null -> viewerStem = null
            selected.isNotEmpty() -> selected = emptySet()
            else -> onClose()
        }
    }

    fun share(stems: List<String>, withRaw: Boolean) {
        scope.launch {
            busy = "Préparation"
            try {
                val ps = stems.mapNotNull { app.store.photos.value[it] }
                val uris = GalleryImages.shareUris(ctx, ps, withRaw) { t, f -> busy = if (f != null) "$t ${(f * 100).toInt()} %" else t }
                if (uris.isEmpty()) error = "Rien à partager hors ligne" else GalleryImages.launchShare(ctx, uris, !withRaw)
                selected = emptySet()
            } catch (e: Exception) {
                error = "Partage impossible · ${e.message ?: "erreur"}"
            } finally { busy = null }
        }
    }

    fun askShare(stems: List<String>) {
        val ps = stems.mapNotNull { app.store.photos.value[it] }
        if (ps.any { it.dngUri != null || GalleryImages.rawReachable(ctx, it) }) shareAsk = stems else share(stems, false)
    }

    Box(modifier.fillMaxSize().background(Halo.Page)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            if (selected.isEmpty()) {
                Row(Modifier.fillMaxWidth().padding(start = 7.dp, end = 12.dp, top = 4.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    BackRound(onClose)
                    Text("Galerie", style = sans(17.sp, FontWeight.SemiBold), modifier = Modifier.padding(start = 2.dp).weight(1f))
                    Text(countLabel(photos.size), style = mono(8.5.sp, color = Halo.Dim).copy(letterSpacing = label().letterSpacing))
                }
            } else {
                SelectBar(
                    n = selected.size,
                    onCancel = { selected = emptySet() },
                    onShare = { askShare(selected.toList()) },
                    onDelete = { confirmDelete = selected.toList() },
                )
            }
            SyncStrip(sync, online)
            if (photos.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        HIcon(Ic.Camera, 22.dp, Halo.Faint)
                        Text("Aucune photo", style = sans(13.sp, color = Halo.Dim), modifier = Modifier.padding(top = 8.dp))
                        Text(if (online) "Les photos de la caméra apparaissent ici" else "Caméra non connectée",
                            style = sans(11.sp, color = Halo.Faint), modifier = Modifier.padding(top = 2.dp))
                    }
                }
            } else {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val cols = if (maxWidth > maxHeight) 5 else 3
                    val items = remember(photos, expanded) { buildGrid(photos, expanded.toSet()) }
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(cols),
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 16.dp),
                        modifier = Modifier.fillMaxSize().navigationBarsPadding(),
                    ) {
                        items(items, key = { it.key }, span = { if (it is GItem.Day) GridItemSpan(maxLineSpan) else GridItemSpan(1) }) { it ->
                            when (it) {
                                is GItem.Day -> DayHeader(it)
                                is GItem.Tile -> {
                                    val stems = it.members
                                    val isSel = if (it.count > 1 && it.series !in expanded) stems.all { s -> s in selected } else it.photo.stem in selected
                                    Tile(
                                        it, isSel, online, selecting = selected.isNotEmpty(),
                                        onClick = {
                                            if (selected.isNotEmpty()) {
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                val group = if (it.count > 1 && it.series !in expanded) stems else listOf(it.photo.stem)
                                                selected = if (isSel) selected - group.toSet() else selected + group
                                            } else if (it.count > 1 && it.series != null && it.series !in expanded) {
                                                expanded = expanded + it.series
                                            } else {
                                                viewerStem = it.photo.stem
                                            }
                                        },
                                        onLongClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            val group = if (it.count > 1 && it.series !in expanded) stems else listOf(it.photo.stem)
                                            selected = selected + group
                                        },
                                        onCollapse = { if (it.series != null) expanded = expanded - it.series },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = viewerStem != null,
            enter = fadeIn(tween(180)) + slideInVertically(tween(260)) { it / 6 },
            exit = fadeOut(tween(180)) + slideOutVertically(tween(240)) { it / 6 },
        ) {
            val stem = viewerStem
            if (stem != null || photos.isNotEmpty()) {
                GalleryViewer(
                    photos = photos,
                    startStem = stem ?: photos.first().stem,
                    onClose = { viewerStem = null },
                    onCurrent = { s -> if (viewerStem != null) viewerStem = s },
                    onEdit = { s -> editStem = s },
                    onShare = { s -> askShare(listOf(s)) },
                    onDelete = { s -> confirmDelete = listOf(s) },
                )
            }
        }

        AnimatedVisibility(
            visible = editStem != null,
            enter = fadeIn(tween(160)) + slideInVertically(tween(260)) { it / 8 },
            exit = fadeOut(tween(160)) + slideOutVertically(tween(220)) { it / 8 },
        ) {
            val p = editStem?.let { photosMap[it] }
            if (p != null) EditorPage(p, onClose = { editStem = null })
        }

        busy?.let { BusyPill(it, Modifier.align(Alignment.BottomCenter)) }
        error?.let { ErrorPill(it, Modifier.align(Alignment.BottomCenter)) }
    }

    confirmDelete?.let { stems ->
        val ps = stems.mapNotNull { photosMap[it] }
        val anyLocal = ps.any { it.jpgUri != null || it.dngUri != null || it.versions.isNotEmpty() }
        val anyCam = ps.any { it.onCamera }
        val n = ps.size
        HaloDialog(
            onDismiss = { confirmDelete = null },
            title = if (n > 1) "Supprimer $n photos" else "Supprimer la photo",
            subtitle = if (anyCam && !online) "Caméra non connectée : suppression sur le téléphone seulement" else null,
        ) {
            fun go(everywhere: Boolean) {
                confirmDelete = null
                scope.launch {
                    busy = "Suppression"
                    try {
                        GalleryImages.delete(ctx, stems, everywhere)
                        selected = emptySet()
                        if (viewerStem in stems && (everywhere || app.store.photos.value[viewerStem!!] == null)) viewerStem = null
                    } catch (e: Exception) {
                        error = "Suppression impossible · ${e.message ?: "erreur"}"
                    } finally { busy = null }
                }
            }
            DialogRow("Supprimer sur le téléphone seulement", { go(false) }, enabled = anyLocal,
                note = if (!anyLocal) "rien ici" else null)
            DialogRow("Supprimer partout", { go(true) }, danger = true, enabled = !anyCam || online,
                note = if (anyCam && !online) "hors ligne" else null)
            DialogRow("Annuler", { confirmDelete = null }, bold = true)
        }
    }

    shareAsk?.let { stems ->
        HaloDialog(onDismiss = { shareAsk = null }, title = "Partager", subtitle = if (stems.size > 1) "${stems.size} photos" else null) {
            DialogRow("JPEG", { shareAsk = null; share(stems, false) }, bold = true)
            DialogRow("JPEG + RAW", { shareAsk = null; share(stems, true) }, note = "DNG ≈ 15 Mo")
            DialogRow("Annuler", { shareAsk = null })
        }
    }
}

private fun countLabel(n: Int) = if (n <= 1) "$n PHOTO" else "$n PHOTOS"

// ---------------------------------------------------------------------------- barre de copie

@Composable
private fun SyncStrip(s: SyncState, online: Boolean) {
    Column(Modifier.fillMaxWidth()) {
        if (s.phoneFull) {
            Row(
                Modifier.padding(start = 12.dp, end = 12.dp, top = 4.dp).fillMaxWidth().clip(RoundedCornerShape(9.dp))
                    .background(Halo.IrSoft).padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Téléphone plein", style = sans(10.5.sp, FontWeight.SemiBold, Halo.Ir))
                Text(" · copie arrêtée, rien n'est effacé de la caméra", style = sans(10.5.sp, color = Halo.Text), maxLines = 1)
            }
        }
        if (s.rawPending > 0) {
            Row(
                Modifier.padding(start = 12.dp, end = 12.dp, top = 4.dp).fillMaxWidth().clip(RoundedCornerShape(9.dp))
                    .background(Halo.Surface).padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("RAW ${s.rawPending}", style = mono(10.5.sp, color = Halo.Warn))
                ThinBar(if (s.current?.endsWith(".dng") == true) s.progress else 0f, Halo.Warn, Modifier.weight(1f).padding(horizontal = 10.dp))
                Text(
                    when {
                        !online -> "en pause"
                        s.etaS != null -> "≈ " + eta(s.etaS!!)
                        else -> "en attente"
                    },
                    style = sans(10.5.sp, color = Halo.Dim),
                )
            }
        } else if (!online) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(5.dp).clip(CircleShape).background(Halo.Faint))
                Text("CAMÉRA NON CONNECTÉE · COPIES DU TÉLÉPHONE", style = label(8.5.sp), modifier = Modifier.padding(start = 6.dp))
            }
        }
    }
}

internal fun eta(s: Int): String = when {
    s < 60 -> "$s s"
    s < 3600 -> "${(s + 30) / 60} min"
    else -> "${s / 3600} h ${(s % 3600) / 60} min"
}

// ---------------------------------------------------------------------------- sélection

@Composable
private fun SelectBar(n: Int, onCancel: () -> Unit, onShare: () -> Unit, onDelete: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 7.dp, end = 12.dp, top = 4.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        BackRound(onCancel, Ic.Close)
        Text(if (n > 1) "$n sélectionnées" else "1 sélectionnée", style = sans(15.sp, FontWeight.SemiBold), modifier = Modifier.padding(start = 2.dp).weight(1f))
        ActionButton("Partager", onShare, primary = true, ic = Ic.Share)
        Spacer(Modifier.width(6.dp))
        ActionButton(null, onDelete, ic = Ic.Trash, danger = true, modifier = Modifier.width(40.dp))
    }
}

// ---------------------------------------------------------------------------- grille

internal sealed class GItem(val key: String) {
    class Day(val label: String, val time: String, k: String) : GItem("d:$k")
    class Tile(val photo: Photo, val count: Int, val series: String?, val members: List<String>, val firstOfExpanded: Boolean) : GItem("p:${photo.stem}")
}

private fun dayKey(t: Double): String = SimpleDateFormat("yyyyMMdd", Locale.FRANCE).format(Date((t * 1000).toLong()))

private fun dayLabel(t: Double): String {
    val c = Calendar.getInstance()
    val today = SimpleDateFormat("yyyyMMdd", Locale.FRANCE).format(c.time)
    c.add(Calendar.DAY_OF_YEAR, -1)
    val yest = SimpleDateFormat("yyyyMMdd", Locale.FRANCE).format(c.time)
    val k = dayKey(t)
    val d = Date((t * 1000).toLong())
    val sameYear = SimpleDateFormat("yyyy", Locale.FRANCE).format(d) == today.take(4)
    return when (k) {
        today -> "Aujourd'hui"
        yest -> "Hier"
        else -> SimpleDateFormat(if (sameYear) "d MMM" else "d MMM yyyy", Locale.FRANCE).format(d)
    }
}

/** Dernière photo d'abord, groupée par jour ; les séries forment une pile (sauf si dépliées). */
internal fun buildGrid(photos: List<Photo>, expanded: Set<String>): List<GItem> {
    val members = photos.filter { it.info.series != null }.groupBy { it.info.series!! }
    val out = ArrayList<GItem>()
    val seen = HashSet<String>()
    var lastDay: String? = null
    for (p in photos) {
        val s = p.info.series
        val group = s?.let { members[it] }
        val stacked = group != null && group.size > 1 && s !in expanded
        if (stacked && !seen.add(s!!)) continue
        val dk = dayKey(p.time)
        if (dk != lastDay) {
            lastDay = dk
            out += GItem.Day(dayLabel(p.time).uppercase(Locale.FRANCE), SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date((p.time * 1000).toLong())), dk)
        }
        if (stacked) {
            out += GItem.Tile(p, group!!.size, s, group.map { it.stem }, false)
        } else {
            val first = group != null && group.size > 1 && group.first().stem == p.stem
            out += GItem.Tile(p, if (group != null && group.size > 1) -group.size else 1, s, listOf(p.stem), first)
        }
    }
    return out
}

@Composable
private fun DayHeader(d: GItem.Day) {
    Row(Modifier.fillMaxWidth().padding(start = 2.dp, end = 2.dp, top = 10.dp, bottom = 5.dp)) {
        Text(d.label, style = label(8.5.sp), modifier = Modifier.weight(1f))
        Text(d.time, style = mono(8.5.sp, color = Halo.Dim))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Tile(
    t: GItem.Tile, selected: Boolean, online: Boolean, selecting: Boolean,
    onClick: () -> Unit, onLongClick: () -> Unit, onCollapse: () -> Unit,
) {
    val ctx = LocalContext.current
    val p = t.photo
    val bmp = produceState<Bitmap?>(null, p.stem, p.jpgUri, p.lookShown, p.versions.size) {
        var tries = 0
        while (true) {
            val b = withContext(Dispatchers.IO) { GalleryImages.tile(ctx, p) }
            if (b != null) { value = b; break }
            if (++tries > 40) break
            delay(1500)
        }
    }.value
    val unavailable = p.cameraOnly && !online
    Box(
        Modifier.aspectRatio(1f).clip(RoundedCornerShape(3.dp)).background(Halo.Surface)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        if (bmp != null) {
            Image(bmp.asImageBitmap(), null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().alpha(if (unavailable) 0.45f else 1f))
        }
        // état : RAW ✓ / RAW (attente) / caméra seulement
        Row(Modifier.align(Alignment.BottomStart).padding(3.dp), verticalAlignment = Alignment.CenterVertically) {
            if (p.cameraOnly) {
                Box(Modifier.size(18.dp).clip(RoundedCornerShape(5.dp)).background(Color(0xAA000000)), contentAlignment = Alignment.Center) {
                    HIcon(Ic.Camera, 11.dp, if (online) Halo.Text else Halo.Dim, stroke = 1.8f)
                }
                Spacer(Modifier.width(3.dp))
            }
            when (p.rawState) {
                RawState.COPIED -> Tag("RAW ✓", Halo.Ok)
                RawState.WAITING -> Tag("RAW", Halo.Warn)
                RawState.NONE -> {}
            }
        }
        if (t.count > 1) {
            Tag("×${t.count}", modifier = Modifier.align(Alignment.TopEnd).padding(4.dp))
        } else if (t.firstOfExpanded) {
            Box(Modifier.align(Alignment.TopEnd).padding(4.dp).plainClick(onCollapse)) { Tag("×${-t.count} −", Halo.Text) }
        } else if (t.count < 0) {
            Box(Modifier.align(Alignment.TopEnd).padding(5.dp).size(5.dp).clip(CircleShape).background(Halo.Text.copy(alpha = 0.7f)))
        }
        if (selecting) {
            Box(
                Modifier.align(Alignment.TopStart).padding(4.dp).size(16.dp).clip(CircleShape)
                    .background(if (selected) Halo.Ir else Color(0x55000000))
                    .border(1.2.dp, if (selected) Halo.Ir else Color(0xCCFFFFFF), CircleShape),
                contentAlignment = Alignment.Center,
            ) { if (selected) HIcon(Ic.Check, 11.dp, Color.White, stroke = 2.4f) }
        }
        if (selected) Box(Modifier.fillMaxSize().border(2.dp, Halo.Ir, RoundedCornerShape(3.dp)))
    }
}

// ---------------------------------------------------------------------------- pastilles d'état

@Composable
internal fun BusyPill(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier.navigationBarsPadding().padding(bottom = 18.dp).clip(RoundedCornerShape(12.dp)).background(Halo.GlassStrong)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.CircularProgressIndicator(Modifier.size(12.dp), color = Halo.Text, strokeWidth = 1.5.dp)
        Text(text, style = sans(11.5.sp, color = Halo.Text), modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
internal fun ErrorPill(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier.navigationBarsPadding().padding(bottom = 18.dp, start = 16.dp, end = 16.dp).clip(RoundedCornerShape(12.dp))
            .background(Halo.GlassStrong).border(1.dp, Halo.IrSoft, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) { Text(text, style = sans(11.5.sp, color = Halo.Text)) }
}

