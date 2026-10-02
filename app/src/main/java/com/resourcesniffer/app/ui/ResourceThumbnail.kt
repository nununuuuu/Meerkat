package com.resourcesniffer.app.ui

import android.webkit.CookieManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import coil.decode.SvgDecoder
import coil.request.ImageRequest
import com.resourcesniffer.app.core.Resource
import com.resourcesniffer.app.core.ResourceType
import java.io.File

@Composable
fun ResourceThumbnail(resource: Resource, onClick: () -> Unit, placeholder: @Composable () -> Unit) {
    val context = LocalContext.current
    val imageUrl = if (resource.type == ResourceType.IMAGE) resource.finalUrl ?: resource.url else resource.thumbnailUrl
    val localFile = if (resource.type == ResourceType.IMAGE) resource.localCachePath else null
    val request = remember(imageUrl, localFile, resource.referer, resource.userAgent, resource.cookie) {
        if (imageUrl == null && localFile == null) null else ImageRequest.Builder(context)
            .data(localFile?.let(::File) ?: imageUrl)
            .decoderFactory(SvgDecoder.Factory())
            .apply {
                resource.referer?.takeIf { it.isNotBlank() }?.let { addHeader("Referer", it) }
                resource.userAgent?.takeIf { it.isNotBlank() }?.let { addHeader("User-Agent", it) }
                // A video poster may be on a different host: use only its own cookies.
                val cookies = imageUrl?.let { CookieManager.getInstance().getCookie(it) }
                    ?: resource.cookie.takeIf { resource.type == ResourceType.IMAGE && imageUrl == resource.url }
                cookies?.takeIf { it.isNotBlank() }?.let { addHeader("Cookie", it) }
            }
            .crossfade(true)
            .build()
    }
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.primaryContainer) {
        Box(Modifier.size(72.dp).clickable(enabled = request != null, onClick = onClick), contentAlignment = Alignment.Center) {
            if (request == null) placeholder() else SubcomposeAsyncImage(
                model = request,
                contentDescription = "資源縮圖",
                modifier = Modifier.size(72.dp),
                contentScale = ContentScale.Crop,
                loading = { Box(contentAlignment = Alignment.Center) { placeholder() } },
                error = { Box(contentAlignment = Alignment.Center) { placeholder() } },
            )
        }
    }
}
