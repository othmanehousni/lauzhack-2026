package ch.lauzhack.app.link

import ch.lauzhack.app.Heard
import ch.lauzhack.app.MoleLine
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64

/** The laptop serves the phone on this TCP port and sends its beacon, "the-mole <port>", to this UDP port. Over USB: `adb reverse tcp:47474 tcp:47474`. */
const val PORT = 47474
internal const val BEACON = "the-mole"
internal const val PATH = "/phone"

/** One JSON text frame on the link. */
@Serializable
sealed interface Link {
    // Laptop to phone.
    @Serializable @SerialName("line") data class Line(val line: MoleLine) : Link
    @Serializable @SerialName("screen") data class Screen(val name: String = "", @Serializable(Base64Bytes::class) val thumbnail: ByteArray? = null) : Link // a small PNG or JPEG
    @Serializable @SerialName("lead") data class Lead(val phone: Boolean = false, val metres: Float = SEATED_M) : Link
    @Serializable @SerialName("meeting") data class Meeting(val title: String) : Link

    // Phone to laptop.
    @Serializable @SerialName("level") data class Level(val db: Float?, val speech: Boolean) : Link
    @Serializable @SerialName("mic") data class Mic(val ready: Boolean) : Link
    @Serializable @SerialName("words") data class Words(val heard: Heard) : Link
    @Serializable @SerialName("pick") data class Pick(val index: Int) : Link
}

internal fun Link.encode(): String = Json.encodeToString(Link.serializer(), this)

internal fun decode(text: String): Link? = runCatching { Json.decodeFromString(Link.serializer(), text) }.getOrNull()

private object Base64Bytes : KSerializer<ByteArray> {
    override val descriptor = PrimitiveSerialDescriptor("Base64Bytes", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: ByteArray) = encoder.encodeString(Base64.encode(value))
    override fun deserialize(decoder: Decoder) = Base64.decode(decoder.decodeString())
}
