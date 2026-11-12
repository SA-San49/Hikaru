package eu.kanade.tachiyomi.ui.player.watchparty.p2p

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.ui.player.watchparty.PairingState

/**
 * Renders a pairing code as a QR image.
 *
 * ZXing work is keyed on the payload string so recomposition does not re-encode
 * an unchanged code: encoding a dense QR takes milliseconds and this composable
 * sits inside a screen that also ticks a countdown every second.
 */
@Composable
fun PairingQr(
    payload: String,
    modifier: Modifier = Modifier,
    size: Int = 720,
) {
    val bitmap by remember(payload, size) {
        mutableStateOf(QrCodec.encode(payload, size))
    }
    Box(
        modifier = modifier
            // White surround: a QR code must not sit on a dark background or the
            // quiet zone is lost and scanners fail.
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White)
            .padding(12.dp),
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        if (image == null) {
            Text(
                text = "Could not render the code",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.Black,
            )
        } else {
            Image(
                bitmap = image.asImageBitmap(),
                contentDescription = if (payload.startsWith(ChunkedPayload.MAGIC)) {
                    "Pairing code, part of a set"
                } else {
                    "Pairing code"
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f),
            )
        }
    }
}

/**
 * The invitation countdown, as `MM:SS`.
 *
 * Ticks locally from a deadline rather than counting down a stored integer:
 * backgrounding the app would pause the latter, which would hand a guest extra
 * time that the host does not believe they have.
 */
@Composable
fun InvitationCountdown(
    expiresAtMs: Long,
    nowMs: () -> Long,
    modifier: Modifier = Modifier,
) {
    // Recompute from the absolute deadline once a second, matching the pattern
    // the ready-check countdown already uses. A stored integer would stop
    // advancing while the app is backgrounded, handing the guest free time.
    var remainingMs by remember(expiresAtMs) {
        mutableStateOf((expiresAtMs - nowMs()).coerceAtLeast(0L))
    }
    LaunchedEffect(expiresAtMs) {
        while (true) {
            remainingMs = (expiresAtMs - nowMs()).coerceAtLeast(0L)
            if (remainingMs <= 0L) break
            kotlinx.coroutines.delay(250)
        }
    }
    val totalSeconds = (remainingMs + 999L) / 1_000L
    val label = "%02d:%02d".format(totalSeconds / 60, totalSeconds % 60)
    val expired = remainingMs <= 0L

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            // Monospace so the digits do not jitter as they change.
            fontFamily = FontFamily.Monospace,
            color = if (expired) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Text(
            text = if (expired) "expired" else "left",
            style = MaterialTheme.typography.bodySmall,
            color = if (expired) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

/**
 * Shows the human-checkable code both people compare out loud.
 *
 * This is the defence against scanning someone else's screen in a crowded room:
 * the host says six characters, the guest confirms them, and a mismatched pairing
 * is caught before anyone joins the wrong party.
 */
@Composable
fun VerificationCodeBadge(
    code: String,
    modifier: Modifier = Modifier,
    label: String = "Check code",
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                text = code.chunked(3).joinToString(" "),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Human explanation for each pairing state, so the UI never shows an enum. */
fun PairingState.explanation(role: PairingPayload.Role): String = when (this) {
    PairingState.IDLE -> "Not connected yet."
    PairingState.CREATING_INVITATION -> "Preparing your invitation…"
    PairingState.CREATING_ANSWER -> "Reading the invitation…"
    PairingState.WAITING_FOR_GUEST -> "Show this code to your guest."
    PairingState.WAITING_FOR_ANSWER -> "Scan this code on the host's device."
    PairingState.VALIDATING_ANSWER -> "Checking the answer…"
    PairingState.ESTABLISHING -> "Connecting directly…"
    PairingState.CONNECTED -> "Connected."
    PairingState.INVITATION_EXPIRED ->
        if (role == PairingPayload.Role.OFFER) {
            "That code has expired. Create a new one."
        } else {
            "That invitation expired before it was used. Ask the host for a new code."
        }
    PairingState.FAILED -> "Could not connect."
}

/** Shown while a step is in progress. */
@Composable
fun PairingProgress(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        Text(text = text, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * A failure, stated plainly.
 *
 * Deliberately not a toast and not transient: a failed pairing is the whole
 * outcome, so it stays on screen with the advice attached.
 */
@Composable
fun PairingFailure(reason: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "Could not connect",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                text = reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

/** Keeps the QR list from growing past what fits on screen. */
@Composable
fun ChunkPager(
    chunks: List<String>,
    modifier: Modifier = Modifier,
) {
    var index by remember(chunks.size) { mutableStateOf(0) }
    if (chunks.isEmpty()) return
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        PairingQr(payload = chunks[index.coerceIn(chunks.indices)], size = 640)
        Text(
            text = "Code ${index + 1} of ${chunks.size}",
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = LocalContentColor.current,
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            androidx.compose.material3.TextButton(
                onClick = { index = (index - 1).mod(chunks.size) },
                enabled = chunks.size > 1,
            ) { Text("Previous") }
            androidx.compose.material3.TextButton(
                onClick = { index = (index + 1).mod(chunks.size) },
                enabled = chunks.size > 1,
            ) { Text("Next") }
        }
    }
}

/** Convenience for call sites that hold a `Bitmap?`. */
@Composable
fun QrImageOrFallback(bitmap: Bitmap?, modifier: Modifier = Modifier) {
    if (bitmap == null) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text("Code unavailable", style = MaterialTheme.typography.bodySmall)
        }
    } else {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Pairing code",
            modifier = modifier,
        )
    }
}
