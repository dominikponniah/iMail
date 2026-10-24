package ch.imail.app.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.imail.app.ImailApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Company logo of the sender's domain; grey initials like iOS Contacts for freemail senders or when no logo exists. */
@Composable
fun SenderAvatar(address: String, name: String?, size: Dp = 40.dp, modifier: Modifier = Modifier, lookupLogo: Boolean = true) {
    val app = LocalContext.current.applicationContext as ImailApp
    var logo by remember(address) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(address, lookupLogo) {
        if (lookupLogo) logo = withContext(Dispatchers.IO) { app.logos.logoFor(address)?.let { decodeDefensively(it.bytes) }?.asImageBitmap() }
    }
    val shape = CircleShape
    val bitmap = logo
    if (bitmap != null) {
        Box(modifier.size(size).clip(shape).background(Color.White).padding(size * 0.12f)) {
            Image(bitmap, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(size))
        }
    } else {
        Box(
            modifier.size(size).clip(shape).background(Brush.verticalGradient(avatarGradient(address))),
            contentAlignment = Alignment.Center,
        ) { Text(initials(name, address), color = Color.White, fontSize = (size.value * 0.4f).sp) }
    }
}

/**
 * A vivid colour per sender: derived from a hash of the address, so it looks random but stays the same
 * for the same sender on every screen and after every restart.
 */
internal fun avatarGradient(key: String): List<Color> {
    val h = (key.lowercase().hashCode().toLong() and 0x7fffffff) % 360
    return listOf(Color.hsv(h.toFloat(), 0.55f, 0.92f), Color.hsv(h.toFloat(), 0.75f, 0.74f))
}

internal fun initials(name: String?, address: String): String {
    val source = name?.takeIf { it.isNotBlank() } ?: address.substringBefore('@')
    val parts = source.split(' ', '.', '_', '-').filter { it.isNotBlank() }
    return (parts.take(2).mapNotNull { it.firstOrNull()?.uppercaseChar() }.joinToString("")).ifEmpty { "?" }
}

/** Bounds-checks dimensions before decoding so a hostile image cannot exhaust memory. */
private fun decodeDefensively(bytes: ByteArray): Bitmap? = try {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth > 4096 || bounds.outHeight > 4096) null
    else {
        var sample = 1
        while (bounds.outWidth / sample > 256) sample *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }
} catch (_: Throwable) { null }
