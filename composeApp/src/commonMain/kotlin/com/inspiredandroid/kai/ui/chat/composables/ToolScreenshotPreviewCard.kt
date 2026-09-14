package com.inspiredandroid.kai.ui.chat.composables

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.inspiredandroid.kai.ui.components.LocalShowFullScreenImage
import com.inspiredandroid.kai.ui.handCursor

/**
 * Inline preview of the most recent tool screenshot ([com.inspiredandroid.kai.data.ToolScreenshotPreview]),
 * assistant-side, tap to open fullscreen. Seeing a capture never requires
 * leaving Kai; the image is not part of the model context.
 */
@Composable
internal fun ToolScreenshotPreviewCard(image: ImageBitmap) {
    val showFullScreen = LocalShowFullScreenImage.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Image(
            bitmap = image,
            contentDescription = null,
            contentScale = ContentScale.FillWidth,
            modifier = Modifier
                .widthIn(max = 260.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable { showFullScreen(image) }
                .handCursor(),
        )
    }
}
