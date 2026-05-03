package io.geph.android

import android.content.Context
import android.util.Log
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

@Serializable
data class DaemonArgs(
    val secret: String,
    val metadata: JsonElement,

    @SerialName("app_whitelist")
    val appWhitelist: List<String>,

    @SerialName("prc_whitelist")
    val prcWhitelist: Boolean,

    val exit: JsonElement,


    @SerialName("listen_all")
    val listenAll: Boolean,
    @SerialName("allow_direct")
    val allowDirect: Boolean,

) {
    fun toConfig(ctx: Context): JsonElement {
        return buildJsonObject {
            for ((originalKey, originalValue) in configTemplate()) {
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

            if (listenAll) {
                put("socks5_listen", "0.0.0.0:9909")
                put("http_proxy_listen", "0.0.0.0:9910")
            }

            put("sess_metadata", metadata)
            put("dry_run", false)
            put("passthrough_china", prcWhitelist)
            put("allow_direct", allowDirect)
            if (prcWhitelist || !(metadata is JsonNull)) {
                put("spoof_dns", true)
            }
            put("cache", ctx.filesDir.toString() + "/cache_" + secret)
            putJsonObject("credentials") {
                Log.e("SECRET", secret)
                put("secret", secret)
            }
        }
    }
}

fun configTemplate(): JsonObject {
    return buildJsonObject {
        put("exit_constraint", "auto")
        put("bridge_mode", "Auto")
        put("cache", JsonNull)

        putJsonObject("broker") {
            putJsonObject("priority_race") {
                putJsonObject("1500") {
                    putJsonObject("aws_lambda") {
                        put("function_name", "geph-lambda-bouncer")
                        put("region", "us-east-1")
                        put("obfs_key", "855MJGAMB58MCPJBB97NADJ36D64WM2T:C4TN2M1H68VNMRVCCH57GDV2C5VN6V3RB8QMWP235D0P4RT2ACV7GVTRCHX3EC37")
                    }
                }
                putJsonObject("300") {
                    putJsonObject("fronted") {
                        put("front", "https://kubernetes.io/")
                        put("host", "svitania-naidallszei-2.netlify.app")
                        put("override_dns", buildJsonArray {
                            add("75.2.60.5:443")
                        })
                    }
                }
                putJsonObject("0") {
                    putJsonObject("fronted") {
                        put("front", "https://kubernetes.io/")
                        put("host", "svitania-naidallszei-2.netlify.app")
                    }
                }
            }
        }

        putJsonObject("tunneled_broker") {
            put("direct", "https://broker.geph.io")
        }

        putJsonObject("broker_keys") {
            put("master", "88c1d2d4197bed815b01a22cadfc6c35aa246dddb553682037a118aebfaa3954")
            put("mizaru_free", "0558216cbab7a9c46f298f4c26e171add9af87d0694988b8a8fe52ee932aa754")
            put("mizaru_plus", "cf6f58868c6d9459b3a63bc2bd86165631b3e916bad7f62b578cd9614e0bcb3b")
            put("mizaru_bw", "3082010a0282010100d0ae53a794ea37bf2e100cb3a872177ec6c11e8375fdcbf92960ce0293465674eb1426a1841b7622a58979a5ff3f8aa2301a621545e9b90bb39d1a6bfda19d6ca1aae74a3192ddfd2b9558eb652c3c2c22f42bdde272852fb67d93cae5846213512c474bf799844aee019bf718f6fa64223be06364459fc8dec66796b141d450d730c4fffe1cac7df8f05591560afa44bcf274f6c0e2303b39c21ab09d19b459ee594512b8341f3d407c026e2509f42c6d89f82f6a3a36fd5c05ad423cd99ad39089403eb9122ea60ef6648afff65438e8e26ce41fa55b9b18741965c77a627bae947bd38fc345e9adab42d6c458f6e194e4232cfd3f04924d5a5e932fe769610203010001")
        }

        put("vpn", false)
        put("spoof_dns", false)
        put("passthrough_china", false)
        put("dry_run", true)
        put("allow_direct", false)

        putJsonObject("credentials") {
            put("secret", "")
        }

        put("sess_metadata", JsonNull)
        put("task_limit", JsonNull)
    }
}
