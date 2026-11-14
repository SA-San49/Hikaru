package eu.kanade.tachiyomi.ui.player.watchparty.p2p

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.zxing.BarcodeFormat
import com.google.zxing.ResultPoint
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.BarcodeView
import com.journeyapps.barcodescanner.DefaultDecoderFactory

/**
 * Camera QR scanner backed by ZXing Embedded, with a gallery fallback.
 *
 *  - **The camera is paused and released in [DisposableEffect]** when the sheet closes.
 *  - **Only QR codes are decoded** via [DefaultDecoderFactory].
 *  - **A gallery image is a first-class path** when a camera is unavailable.
 */
@Composable
fun QrScanner(
    onScanned: (String) -> Unit,
    modifier: Modifier = Modifier,
    paused: Boolean = false,
) {
    val context = LocalContext.current
    val currentOnScanned by rememberUpdatedState(onScanned)
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var galleryError by remember { mutableStateOf<String?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? ->
        if (uri != null) {
            val text = decodeFromUri(context, uri)
            if (text == null) {
                galleryError = "No Hikaru pairing code was found in that image."
            } else {
                galleryError = null
                currentOnScanned(text)
            }
        }
    }

    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (hasPermission) {
                CameraPreview(
                    paused = paused,
                    onFrame = { text ->
                        galleryError = null
                        currentOnScanned(text)
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Camera access is needed to scan a pairing code.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                        Text("Allow camera")
                    }
                }
            }
        }

        galleryError?.let { error ->
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        // Always available: a broken camera must not make the feature unusable.
        TextButton(
            onClick = {
                galleryLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Scan from a screenshot instead")
        }
    }
}

@Composable
private fun CameraPreview(
    paused: Boolean,
    onFrame: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentPaused by rememberUpdatedState(paused)
    val currentOnFrame by rememberUpdatedState(onFrame)

    val scanGate = remember {
        object {
            var lastText: String? = null
            var lastDeliveredAtMs: Long = 0L
        }
    }

    val barcodeView = remember(context) {
        BarcodeView(context).apply {
            decoderFactory = DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE))
            cameraSettings = cameraSettings.apply {
                isAutoFocusEnabled = true
                isContinuousFocusEnabled = true
            }
            isUseTextureView = true
            decodeContinuous(
                object : BarcodeCallback {
                    override fun barcodeResult(result: BarcodeResult) {
                        if (currentPaused) return
                        val text = result.text?.takeIf { it.isNotBlank() } ?: return
                        val now = System.currentTimeMillis()
                        val isDuplicateWithinCooldown =
                            text == scanGate.lastText &&
                                now - scanGate.lastDeliveredAtMs < SAME_FRAME_COOLDOWN_MS
                        if (!isDuplicateWithinCooldown) {
                            scanGate.lastText = text
                            scanGate.lastDeliveredAtMs = now
                            currentOnFrame(text)
                        }
                    }

                    override fun possibleResultPoints(resultPoints: List<ResultPoint>) = Unit
                },
            )
        }
    }

    LaunchedEffect(paused, barcodeView) {
        if (paused) {
            barcodeView.pause()
        } else {
            barcodeView.resume()
        }
    }

    DisposableEffect(lifecycleOwner, barcodeView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> if (!currentPaused) barcodeView.resume()
                Lifecycle.Event.ON_PAUSE -> barcodeView.pause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            barcodeView.stopDecoding()
            barcodeView.pause()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { barcodeView },
    )
}

private const val SAME_FRAME_COOLDOWN_MS = 650L

/**
 * Reads a QR out of a picked image.
 *
 * Downsampled when the bitmap is very large: an unscaled 48 MP photo decodes
 * just as well at a quarter of the size and uses a fraction of the memory.
 */
private fun decodeFromUri(context: Context, uri: Uri): String? = runCatching {
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sampleSize = 1
    while (bounds.outWidth / sampleSize > MAX_GALLERY_DIMENSION ||
        bounds.outHeight / sampleSize > MAX_GALLERY_DIMENSION
    ) {
        sampleSize *= 2
    }

    val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    val bitmap: Bitmap = resolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, options)
    } ?: return null

    QrCodec.decodeBitmap(bitmap)
}.getOrNull()

/** Longest edge kept when decoding a picked photo. */
private const val MAX_GALLERY_DIMENSION = 2048
