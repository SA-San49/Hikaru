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
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
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
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Camera QR scanner, with a gallery fallback.
 *
 * Three constraints shaped this:
 *
 *  - **The camera must not keep running after the sheet closes.** CameraX is
 *    bound to the composable's lifecycle and unbound in [DisposableEffect].
 *    A scanner that keeps the sensor open drains the battery and, on some OEM
 *    builds, blocks the video player from starting.
 *  - **Analysis must not run on the main thread**, and must never queue frames:
 *    a slow device that falls behind should analyse *fewer* frames, not build a
 *    backlog that makes every subsequent frame stale.
 *  - **A gallery image is a first-class path**, not a workaround. Someone may be
 *    sent an invitation screenshot through a chat app, or be on a device whose
 *    camera cannot focus on another screen.
 */
@Composable
fun QrScanner(
    onScanned: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
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
                    onFrame = { text -> currentOnScanned(text) },
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
    onFrame: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    // One delivery per scan: the analyser sees the same code ~30 times a second.
    val delivered = remember { AtomicBoolean(false) }
    val currentOnFrame by rememberUpdatedState(onFrame)

    DisposableEffect(Unit) {
        onDispose { analysisExecutor.shutdown() }
    }

    AndroidView(
        modifier = modifier,
        factory = { viewContext ->
            val previewView = PreviewView(viewContext).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }

            val providerFuture = ProcessCameraProvider.getInstance(viewContext)
            providerFuture.addListener(
                {
                    val provider = runCatching { providerFuture.get() }.getOrNull() ?: return@addListener
                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }
                    val analysis = ImageAnalysis.Builder()
                        // Keep only the newest frame: stale frames are worse than
                        // no frames when the user is moving the phone.
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also { analysisUseCase ->
                            analysisUseCase.setAnalyzer(analysisExecutor) { imageProxy ->
                                try {
                                    if (!delivered.get()) {
                                        val text = imageProxy.decodeQr()
                                        if (text != null && delivered.compareAndSet(false, true)) {
                                            currentOnFrame(text)
                                        }
                                    }
                                } catch (e: Throwable) {
                                    logcat(LogPriority.WARN, e) { "WatchParty: frame analysis failed" }
                                } finally {
                                    // Must always close, or the pipeline stalls.
                                    imageProxy.close()
                                }
                            }
                        }

                    val bind = runCatching {
                        provider.unbindAll()
                        provider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            analysis,
                        )
                    }
                    if (bind.isFailure) {
                        logcat(LogPriority.ERROR, bind.exceptionOrNull()) {
                            "WatchParty: could not open the camera"
                        }
                    }
                },
                ContextCompat.getMainExecutor(context),
            )

            previewView
        },
    )
}

/**
 * Decodes a QR from a `YUV_420_888` image using only its Y plane.
 *
 * The Y plane is a greyscale image, which is exactly what a QR decoder wants;
 * converting to RGB first would cost more than the decode itself.
 */
private fun ImageProxy.decodeQr(): String? {
    val plane = planes.firstOrNull() ?: return null
    val buffer = plane.buffer
    val data = ByteArray(buffer.remaining())
    buffer.get(data)
    return QrCodec.decodeFrame(
        data = data,
        width = width,
        height = height,
        rowStride = plane.rowStride,
    )
}

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
