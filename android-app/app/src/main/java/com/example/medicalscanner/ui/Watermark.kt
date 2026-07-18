package com.example.medicalscanner.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.medicalscanner.R

/**
 * The small logo badge shown next to the back arrow in every screen's top bar. `medical_assist_logo`
 * has an opaque white background (no alpha channel), so it sits in a deliberate white badge rather
 * than floating directly on the top bar — otherwise it shows as a stray white box in dark mode.
 */
@Composable
fun TopBarLogo(size: androidx.compose.ui.unit.Dp = 28.dp) {
    Image(
        painter = painterResource(id = R.drawable.medical_assist_logo),
        contentDescription = "Logo",
        modifier = Modifier
            .height(size)
            .width(size)
            .clip(RoundedCornerShape(6.dp))
            .background(Color.White, RoundedCornerShape(6.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp))
    )
}

/**
 * Faint, centered Medical Assist logo watermark drawn behind a screen's own content — append
 * this to a screen's ROOT content modifier chain (the Column/Box built directly inside
 * `Scaffold { innerPadding -> ... }`), after any `.background(...)` call so the watermark
 * paints on top of the flat background color but underneath the actual content.
 *
 * `medical_assist_logo.jpg` has an opaque white background (no alpha channel — it's a photo-
 * style JPG, not something this codebase can turn transparent without an image editor).
 * BlendMode.Multiply makes that white a no-op wherever it lands (white × anything = that same
 * thing unchanged), so the white square disappears against whatever's behind it in both light
 * and dark themes; only the logo's own colored pixels tint faintly through.
 */
@Composable
fun Modifier.appWatermark(alpha: Float = 0.06f): Modifier {
    val context = LocalContext.current
    val bitmap = remember {
        BitmapFactory.decodeResource(context.resources, R.drawable.medical_assist_logo).asImageBitmap()
    }
    return this.drawBehind {
        val target = size.minDimension * 0.85f
        if (target <= 0f || bitmap.width <= 0) return@drawBehind
        val scale = target / bitmap.width.toFloat()
        val dstSize = IntSize(
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1)
        )
        val dstOffset = IntOffset(
            ((size.width - dstSize.width) / 2f).toInt(),
            ((size.height - dstSize.height) / 2f).toInt()
        )
        drawImage(
            image = bitmap,
            dstOffset = dstOffset,
            dstSize = dstSize,
            alpha = alpha,
            blendMode = BlendMode.Multiply
        )
    }
}
