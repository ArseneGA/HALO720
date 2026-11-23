package fr.halo720.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/* Petits éléments de la galerie, au style de la maquette (v3 : plus fin). */

internal enum class Ic { ChevronDown, Share, Trash, More, Camera, Check, Close, Info, Sliders, Raw, Stack }

/** Icône au trait fin (1,5 px), dessinée dans une grille de 24. */
@Composable
internal fun HIcon(ic: Ic, size: Dp = 16.dp, color: Color = Halo.Text, stroke: Float = 1.6f, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val k = this.size.width / 24f
        val st = Stroke(width = stroke * k * 24f / 18f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun p(vararg pts: Float): Path = Path().apply {
            moveTo(pts[0] * k, pts[1] * k)
            var i = 2
            while (i + 1 < pts.size) { lineTo(pts[i] * k, pts[i + 1] * k); i += 2 }
        }
        when (ic) {
            Ic.ChevronDown -> drawPath(p(6f, 9f, 12f, 15f, 18f, 9f), color, style = st)
            Ic.Share -> {
                drawPath(p(12f, 3f, 12f, 15f), color, style = st)
                drawPath(p(7f, 8f, 12f, 3f, 17f, 8f), color, style = st)
                drawPath(p(5f, 14f, 5f, 20f, 19f, 20f, 19f, 14f), color, style = st)
            }
            Ic.Trash -> {
                drawPath(p(4f, 7f, 20f, 7f), color, style = st)
                drawPath(p(9f, 7f, 9f, 4f, 15f, 4f, 15f, 7f), color, style = st)
                drawPath(p(6f, 7f, 7f, 20f, 17f, 20f, 18f, 7f), color, style = st)
            }
            Ic.More -> for (x in listOf(6f, 12f, 18f)) drawCircle(color, 1.6f * k, Offset(x * k, 12f * k))
            Ic.Camera -> {
                drawPath(p(3f, 8f, 3f, 19f, 21f, 19f, 21f, 8f, 16f, 8f, 14.5f, 5f, 9.5f, 5f, 8f, 8f, 3f, 8f), color, style = st)
                drawCircle(color, 3.5f * k, Offset(12f * k, 13f * k), style = st)
            }
            Ic.Check -> drawPath(p(5f, 12.5f, 10f, 17.5f, 19f, 7f), color, style = st)
            Ic.Close -> { drawPath(p(6f, 6f, 18f, 18f), color, style = st); drawPath(p(18f, 6f, 6f, 18f), color, style = st) }
            Ic.Info -> {
                drawCircle(color, 9f * k, Offset(12f * k, 12f * k), style = st)
                drawPath(p(12f, 11f, 12f, 16.5f), color, style = st)
                drawCircle(color, 1.1f * k, Offset(12f * k, 7.8f * k))
            }
            Ic.Sliders -> {
                drawPath(p(4f, 7f, 20f, 7f), color, style = st); drawCircle(Halo.Page, 2.2f * k, Offset(9f * k, 7f * k)); drawCircle(color, 2.2f * k, Offset(9f * k, 7f * k), style = st)
                drawPath(p(4f, 17f, 20f, 17f), color, style = st); drawCircle(Halo.Page, 2.2f * k, Offset(15f * k, 17f * k)); drawCircle(color, 2.2f * k, Offset(15f * k, 17f * k), style = st)
            }
            Ic.Raw -> {
                drawRoundRect(color, Offset(3f * k, 6f * k), Size(18f * k, 12f * k), androidx.compose.ui.geometry.CornerRadius(2f * k), style = st)
            }
            Ic.Stack -> {
                drawRoundRect(color, Offset(4f * k, 8f * k), Size(13f * k, 12f * k), androidx.compose.ui.geometry.CornerRadius(2f * k), style = st)
                drawPath(p(8f, 5f, 20f, 5f, 20f, 16f), color, style = st)
            }
        }
    }
}

/** Étiquette sur l'image : « ISO 400 · 1/250 », « RAW ✓ ». */
@Composable
internal fun Tag(text: String, color: Color = Halo.Text, modifier: Modifier = Modifier) {
    Box(
        modifier.height(18.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xAA000000)).padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = mono(8.5.sp, FontWeight.Bold, color), maxLines = 1) }
}

/** Bouton rond de retour (chevron vers le bas), 30 px. */
@Composable
internal fun BackRound(onClick: () -> Unit, ic: Ic = Ic.ChevronDown) {
    Box(
        Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(30.dp).clip(CircleShape).background(Color(0x0DFFFFFF)), contentAlignment = Alignment.Center) {
            HIcon(ic, 14.dp, stroke = 2.2f)
        }
    }
}

/** Bouton d'action (34 px, coins 9 px). */
@Composable
internal fun ActionButton(
    text: String?, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = false,
    ic: Ic? = null, enabled: Boolean = true, danger: Boolean = false,
) {
    val fg = when { primary -> Color.Black; danger -> Halo.Ir; else -> Halo.Text }
    Row(
        modifier.height(34.dp).clip(RoundedCornerShape(9.dp))
            .background(if (primary) Halo.Text else Color(0x0DFFFFFF))
            .clickable(enabled = enabled, onClick = onClick).alpha(if (enabled) 1f else 0.4f)
            .padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        if (ic != null) HIcon(ic, 15.dp, fg, stroke = 2f)
        if (ic != null && text != null) Spacer(Modifier.size(6.dp))
        if (text != null) Text(text, style = sans(12.sp, FontWeight.SemiBold, fg), maxLines = 1)
    }
}

/** Barre de progression fine. */
@Composable
internal fun ThinBar(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.height(4.dp)) {
        val r = androidx.compose.ui.geometry.CornerRadius(size.height / 2)
        drawRoundRect(Color(0x12FFFFFF), cornerRadius = r)
        drawRoundRect(color, size = Size(size.width * fraction.coerceIn(0f, 1f), size.height), cornerRadius = r)
    }
}

/** Fenêtre sombre au style de l'app (pas d'alerte système). */
@Composable
internal fun HaloDialog(onDismiss: () -> Unit, title: String, subtitle: String? = null, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.padding(horizontal = 28.dp).widthIn(max = 380.dp).fillMaxWidth()
                .clip(RoundedCornerShape(12.dp)).background(Halo.Surface)
                .border(1.dp, Color(0x14FFFFFF), RoundedCornerShape(12.dp)),
        ) {
            Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 10.dp)) {
                Text(title, style = sans(14.sp, FontWeight.SemiBold))
                if (subtitle != null) Text(subtitle, style = sans(11.sp, color = Halo.Dim), modifier = Modifier.padding(top = 3.dp))
            }
            content()
        }
    }
}

/** Ligne de choix (42 px) dans une fenêtre. */
@Composable
internal fun DialogRow(text: String, onClick: () -> Unit, danger: Boolean = false, enabled: Boolean = true, note: String? = null, bold: Boolean = false) {
    Column {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x0DFFFFFF)))
        Row(
            Modifier.fillMaxWidth().height(42.dp).clickable(enabled = enabled, onClick = onClick).alpha(if (enabled) 1f else 0.4f)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text, style = sans(13.sp, if (bold) FontWeight.SemiBold else FontWeight.Normal, if (danger) Halo.Ir else Halo.Text), modifier = Modifier.weight(1f))
            if (note != null) Text(note, style = mono(10.sp, color = Halo.Dim))
        }
    }
}

/** Ligne libellé / valeur des infos. */
@Composable
internal fun InfoRow(k: String, v: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(k.uppercase(), style = label(8.5.sp), modifier = Modifier.weight(1f))
        Text(v, style = mono(10.5.sp, color = Halo.Text), textAlign = TextAlign.End)
    }
}

/** Zone cliquable sans effet visuel. */
@Composable
internal fun Modifier.plainClick(onClick: () -> Unit): Modifier =
    this.clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick)

@Composable
internal fun RowScope.Grow() = Spacer(Modifier.weight(1f))

internal fun DrawScope.ruleOfThirds(color: Color) {
    for (i in 1..2) {
        drawLine(color, Offset(size.width * i / 3f, 0f), Offset(size.width * i / 3f, size.height), 1f)
        drawLine(color, Offset(0f, size.height * i / 3f), Offset(size.width, size.height * i / 3f), 1f)
    }
}
