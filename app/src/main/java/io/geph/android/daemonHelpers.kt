package io.geph.android

import android.content.Context
import android.util.Log
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.yaml.snakeyaml.Yaml

@Serializable
data class ProxyArgs(
    val autoconf: Boolean,
    @SerialName("listen_all")
    val listenAll: Boolean,
    @SerialName("socks5_port")
    val socks5Port: Int,
    @SerialName("http_port")
    val httpPort: Int,
)

@Serializable
data class DaemonArgs(
    val secret: String,
    val metadata: JsonElement,

    @SerialName("app_whitelist")
    val appWhitelist: List<String>,

    @SerialName("prc_whitelist")
    val prcWhitelist: Boolean,

    val exit: JsonElement,

    // null = no local proxy listeners at all
    val proxy: ProxyArgs? = null,
    @SerialName("allow_direct")
    val allowDirect: Boolean,

    // Defaults to true so DaemonArgs persisted by older app versions (which
    // predate the setting) keep Geph's original allow-LAN behavior.
    @SerialName("allow_lan")
    val allowLan: Boolean = true,

) {
    fun toConfig(ctx: Context): JsonElement {
        return buildJsonObject {
            for ((originalKey, originalValue) in configTemplate(ctx)) {
                put(originalKey, originalValue)
            }

            when (exit) {
                is JsonObject -> {
                    putJsonObject("exit_constraint") {
                        putJsonArray("country_city") {
                            add(exit.get("country")!!)
                            add(exit.get("city")!!)
                        }
                    }
                }
                else -> {}
            }

            // Omitting the listen keys entirely means the engine binds no
            // proxy ports.
            if (proxy != null) {
                val host = if (proxy.listenAll) "0.0.0.0" else "127.0.0.1"
                put("socks5_listen", "$host:${proxy.socks5Port}")
                put("http_proxy_listen", "$host:${proxy.httpPort}")
            }

            put("sess_metadata", metadata)
            put("dry_run", false)
            put("passthrough_china", prcWhitelist)
            put("allow_direct", allowDirect)
            put("allow_lan", allowLan)
            if (prcWhitelist) {
                put("spoof_dns", true)
            } else {
                put("spoof_dns", false)
            }
            put("cache", ctx.filesDir.toString() + "/cache_" + secret)
            putJsonObject("credentials") {
                Log.e("SECRET", secret)
                put("secret", secret)
            }
        }
    }
}

// Desktop-only keys in the shared template: geph5-app binds its proxy/control
// listeners on fixed localhost TCP ports, while Android sets them per-daemon
// (a Unix control socket, and proxy ports only when the user enables them).
private val DESKTOP_ONLY_TEMPLATE_KEYS =
    setOf("socks5_listen", "http_proxy_listen", "pac_listen", "control_listen")

/**
 * The engine config template, loaded from the same default-config.yaml that
 * geph5-app embeds (symlinked from the geph5 submodule into assets), so the
 * broker routes and keys cannot drift between platforms. Mirroring geph5-app's
 * supervisor, callers override the dynamic fields (credentials, exit, cache,
 * listen addresses) on top of this. The template itself is inert (dry_run) so
 * the fallback daemon can use it as-is.
 */
fun configTemplate(ctx: Context): JsonObject {
    val yamlText =
        ctx.assets.open("default-config.yaml").bufferedReader().use { it.readText() }
    val template = yamlToJson(Yaml().load<Any?>(yamlText)).jsonObject
    return buildJsonObject {
        for ((key, value) in template) {
            if (key in DESKTOP_ONLY_TEMPLATE_KEYS) continue
            put(key, value)
        }
        put("dry_run", true)
    }
}

private fun yamlToJson(node: Any?): JsonElement = when (node) {
    null -> JsonNull
    is Map<*, *> -> buildJsonObject {
        for ((k, v) in node) put(k.toString(), yamlToJson(v))
    }
    is List<*> -> buildJsonArray { for (v in node) add(yamlToJson(v)) }
    is Boolean -> JsonPrimitive(node)
    is Number -> JsonPrimitive(node)
    is String -> JsonPrimitive(node)
    else -> JsonPrimitive(node.toString())
}
