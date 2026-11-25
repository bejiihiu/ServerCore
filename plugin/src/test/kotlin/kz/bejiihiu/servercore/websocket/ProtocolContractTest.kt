package kz.bejiihiu.servercore.websocket

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kz.bejiihiu.servercore.locale.LocaleAckPayload
import kz.bejiihiu.servercore.locale.LocalePushPayload
import kz.bejiihiu.servercore.ws.AccountDeletedPayload
import kz.bejiihiu.servercore.ws.ApplicationApprovedPayload
import kz.bejiihiu.servercore.ws.ApplicationExpiredPayload
import kz.bejiihiu.servercore.ws.ApplicationRejectedPayload
import kz.bejiihiu.servercore.ws.ApplicationSubmittedPayload
import kz.bejiihiu.servercore.ws.AuthDecisionPayload
import kz.bejiihiu.servercore.ws.AuthRequestPayload
import kz.bejiihiu.servercore.ws.GuestEndedPayload
import kz.bejiihiu.servercore.ws.GuestStartedPayload
import kz.bejiihiu.servercore.ws.LinkTokenPayload
import kz.bejiihiu.servercore.ws.NetworkSnapshotPayload
import kz.bejiihiu.servercore.ws.PlayerJoinedPayload
import kz.bejiihiu.servercore.ws.PlayerLeftPayload
import kz.bejiihiu.servercore.ws.wsJson

/**
 * Cross-language protocol contract, plugin half. The manifest at
 * protocol/messages.json is the single source the backend suite checks too.
 * If a wire type, a required payload field or an optionality flips on either
 * side, one of these tests fails before the build ships.
 */
class ProtocolContractTest {

    @Test
    fun `the transport enum lists exactly the types the manifest carries`() {
        val carried = MessageType.entries
            .filter { it != MessageType.UNKNOWN }
            .map { it.wire }
            .toSet()
        val expected = manifest.messages
            .filter { it.pluginTransport != ABSENT }
            .map { it.type }
            .toSet()
        assertEquals(expected, carried, "MessageType drifted from the protocol manifest")
    }

    @Test
    fun `types the manifest marks absent are not carried by the transport`() {
        val absent = manifest.messages.filter { it.pluginTransport == ABSENT }
        assertTrue(absent.isNotEmpty(), "the manifest lost its reserved types")
        for (message in absent) {
            assertNull(
                MessageType.fromWire(message.type),
                "${message.type}: manifest says the plugin does not carry it, the enum disagrees",
            )
        }
    }

    @Test
    fun `no two constants share a wire name`() {
        val wires = MessageType.entries.map { it.wire }
        assertEquals(wires.size, wires.toSet().size, "duplicate wire name in MessageType")
    }

    @Test
    fun `every typed transport branch parses the manifest example`() {
        val typed = manifest.messages.filter { it.pluginTransport == TYPED }
        assertTrue(typed.isNotEmpty(), "the manifest carries no typed messages")
        for (message in typed) {
            val decoded = decode(message.type, message.example)
            assertEquals(MessageType.fromWire(message.type), decoded.envelope.type, message.type)
            val payload = decoded.envelope.payload
            assertFalse(
                payload is Payload.Raw,
                "${message.type}: marked typed in the manifest, the codec fell back to raw",
            )
            // a typed branch that silently drops a required field is drift too
            val emitted = codec.payloadAsJson(payload) as JsonObject
            assertRequired(emitted, message.fields, message.type)
        }
    }

    @Test
    fun `a typed payload missing a required field is rejected`() {
        val typed = manifest.messages.filter { it.pluginTransport == TYPED }
        for (message in typed) {
            for (field in message.fields.filter { it.required }) {
                val without = JsonObject(message.example - field.name)
                assertFailsWith<ProtocolException>(
                    "${message.type}: decoder accepted a payload without required field ${field.name}",
                ) {
                    decode(message.type, without)
                }
            }
        }
    }

    @Test
    fun `every raw transport type decodes as raw json with the wire name preserved`() {
        val raw = manifest.messages.filter { it.pluginTransport == RAW }
        assertTrue(raw.isNotEmpty(), "the manifest carries no raw messages")
        for (message in raw) {
            val decoded = decode(message.type, message.example)
            val payload = assertIs<Payload.Raw>(decoded.envelope.payload, message.type)
            assertEquals(message.type, payload.wireType, "${message.type}: wire name lost on the raw path")
            assertRequired(payload.fields, message.fields, message.type)
        }
    }

    @Test
    fun `the dto table covers exactly the manifest types that declare one`() {
        val declared = manifest.messages.filter { it.pluginDto != null }.map { it.type }.toSet()
        assertEquals(declared, dtoSerializers.keys, "the dto table drifted from the manifest")
        for (message in manifest.messages) {
            val dto = message.pluginDto ?: continue
            val serializer = dtoSerializers[message.type]
                ?: fail("${message.type}: manifest names $dto but no serializer is registered")
            assertEquals(
                dto,
                serializer.descriptor.serialName.substringAfterLast('.'),
                "${message.type}: pluginDto does not name the kotlinx type",
            )
        }
    }

    @Test
    fun `every dto declares the manifest fields with the same optionality`() {
        for (message in manifest.messages) {
            val serializer = dtoSerializers[message.type] ?: continue
            val descriptor = serializer.descriptor
            val dtoName = message.pluginDto

            for (field in message.fields) {
                val index = descriptor.getElementIndex(field.name)
                if (index == CompositeDecoder.UNKNOWN_NAME) {
                    assertFalse(
                        field.required,
                        "${message.type}.${field.name}: required in the manifest but absent from $dtoName",
                    )
                    continue
                }
                assertEquals(
                    !field.required,
                    descriptor.isElementOptional(index),
                    "${message.type}.${field.name}: optionality drifted in $dtoName",
                )
            }

            // and the other way round: nothing the dto demands that the manifest does not promise
            val manifestRequired = message.fields.filter { it.required }.map { it.name }.toSet()
            for (index in 0 until descriptor.elementsCount) {
                if (descriptor.isElementOptional(index)) continue
                val name = descriptor.getElementName(index)
                assertTrue(
                    name in manifestRequired,
                    "${message.type}: $dtoName requires \"$name\" which the manifest does not list as required",
                )
            }
        }
    }

    @Test
    fun `every dto parses the manifest example`() {
        for ((type, serializer) in dtoSerializers) {
            val message = byType[type] ?: fail("$type: dto registered for a type the manifest does not list")
            val decoded = runCatching { decodeDto(serializer, message.example) }
            assertTrue(
                decoded.isSuccess,
                "$type: ${message.pluginDto} cannot parse the manifest example (${decoded.exceptionOrNull()})",
            )
        }
    }

    // ---- plumbing ----

    private fun decode(wireType: String, payload: JsonObject): DecodedMessage {
        val type = MessageType.fromWire(wireType)
            ?: fail("$wireType: not carried by MessageType, so the manifest entry cannot be checked")
        val envelope = Envelope(FRAME_ID, type, NOW, Payload.Raw(wireType, payload))
        return codec.decode(codec.encode(envelope), NOW, MAX_SKEW_MS, MAX_PACKET_BYTES)
    }

    @Suppress("UNCHECKED_CAST")
    private fun decodeDto(serializer: KSerializer<*>, element: JsonObject): Any =
        wsJson.decodeFromJsonElement(serializer as KSerializer<Any>, element)

    private fun assertRequired(obj: JsonObject, fields: List<ManifestField>, where: String) {
        for (field in fields) {
            val value = obj[field.name]
            if (field.required) assertNotNull(value, "$where: required field ${field.name} is missing")
            if (field.type == "object") {
                val nested = value as? JsonObject ?: continue
                assertRequired(nested, field.fields.orEmpty(), "$where.${field.name}")
            }
            if (field.type == "array") {
                // the fixture exercises the element shape with its first sample
                val first = (value as? JsonArray)?.firstOrNull() as? JsonObject ?: continue
                assertRequired(first, field.items?.fields.orEmpty(), "$where.${field.name}[]")
            }
        }
    }

    /** every kotlinx type the ws layer decodes a payload into, by wire type */
    private val dtoSerializers: Map<String, KSerializer<*>> = mapOf(
        "PLAYER_JOINED" to PlayerJoinedPayload.serializer(),
        "PLAYER_LEFT" to PlayerLeftPayload.serializer(),
        "AUTH_REQUEST" to AuthRequestPayload.serializer(),
        "AUTH_APPROVED" to AuthDecisionPayload.serializer(),
        "AUTH_REJECTED" to AuthDecisionPayload.serializer(),
        "AUTH_EXPIRED" to AuthDecisionPayload.serializer(),
        "GUEST_STARTED" to GuestStartedPayload.serializer(),
        "GUEST_ENDED" to GuestEndedPayload.serializer(),
        "APPLICATION_SUBMITTED" to ApplicationSubmittedPayload.serializer(),
        "APPLICATION_APPROVED" to ApplicationApprovedPayload.serializer(),
        "APPLICATION_REJECTED" to ApplicationRejectedPayload.serializer(),
        "APPLICATION_EXPIRED" to ApplicationExpiredPayload.serializer(),
        "ACCOUNT_DELETED" to AccountDeletedPayload.serializer(),
        "LINK_TOKEN" to LinkTokenPayload.serializer(),
        "LOCALE_PUSH" to LocalePushPayload.serializer(),
        "LOCALE_ACK" to LocaleAckPayload.serializer(),
        "NETWORK_SNAPSHOT" to NetworkSnapshotPayload.serializer(),
    )

    private val codec = EnvelopeCodec(SECRET)
    private val byType = manifest.messages.associateBy { it.type }

    private companion object {
        const val TYPED = "typed"
        const val RAW = "raw"
        const val ABSENT = "absent"

        const val SECRET = "0123456789abcdef0123456789abcdef"
        const val FRAME_ID = "contract-0001"
        const val NOW = 1_700_000_000_500L
        const val MAX_SKEW_MS = 30_000L
        const val MAX_PACKET_BYTES = 262_144

        private val manifest: Manifest = ManifestJson.decodeFromString(locateManifest().readText())
        /** gradle runs tests from the module dir, ides from wherever; walk up until the repo root shows up */
        fun locateManifest(): File {
            val tried = mutableListOf<String>()
            var dir: File? = File(System.getProperty("user.dir")).absoluteFile
            while (dir != null) {
                val candidate = File(dir, "protocol/messages.json")
                tried += candidate.path
                if (candidate.isFile) return candidate
                dir = dir.parentFile
            }
            fail("protocol/messages.json not found; looked at: ${tried.joinToString()}")
        }
    }
}

/** shape of protocol/messages.json; only what the plugin half of the contract needs */
@Serializable
private data class Manifest(val version: Int = 0, val messages: List<ManifestMessage>)

@Serializable
private data class ManifestMessage(
    val type: String,
    val direction: String,
    val status: String,
    val pluginTransport: String,
    val pluginDto: String? = null,
    val summary: String = "",
    val fields: List<ManifestField>,
    val example: JsonObject,
)

@Serializable
private data class ManifestField(
    val name: String,
    val type: String,
    val required: Boolean,
    val values: List<String>? = null,
    val fields: List<ManifestField>? = null,
    /** array fields: the shape every element has to match */
    val items: ManifestItems? = null,
)

@Serializable
private data class ManifestItems(
    val type: String,
    val fields: List<ManifestField> = emptyList(),
)

private val ManifestJson = Json { ignoreUnknownKeys = true }
