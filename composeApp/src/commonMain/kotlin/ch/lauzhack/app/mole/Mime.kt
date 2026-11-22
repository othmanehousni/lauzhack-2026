package ch.lauzhack.app.mole

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import lauzhack2026.composeapp.generated.resources.Res
import lauzhack2026.composeapp.generated.resources.mime
import lauzhack2026.composeapp.generated.resources.mime_dark
import org.jetbrains.compose.resources.painterResource

/** What Molière is doing: presenting, where it puts on the shared screen what you ask for, or miming, where you mouth
 * words at the camera and the lip reader says them aloud in your voice. */
enum class Mode { Present, Mime }

/** The mole as a mime, in its beret, the light one on a light bar and the grey one on a dark bar. */
@Composable
internal fun MimeMole(modifier: Modifier) = Image(
    painterResource(if (LocalMoleColors.current == MoleColors.Dark) Res.drawable.mime_dark else Res.drawable.mime),
    null,
    modifier,
    Alignment.BottomCenter,
    ContentScale.Fit,
)
