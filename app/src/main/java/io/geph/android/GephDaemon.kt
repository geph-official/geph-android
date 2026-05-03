package io.geph.android

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * A daemon class that uses a [JsonObject] as its configuration.
 * The constructor immediately writes the config to disk and starts the daemon process.
 *
 * The daemon process is spawned with one of two stdio configurations:
 *  - API 26+: stdin is set to ProcessBuilder.Redirect.INHERIT so the parent's
 *    fd 0 (which can be dup2'd to a TUN fd by the caller) is visible to the
 *    child. This is how the VPN packets are exchanged.
 *  - API <26: ProcessBuilder.Redirect was added in API 26, so we cannot make
 *    the child inherit our fd 0. Instead the child is spawned with --stdio-vpn,
 *    which has it speak length-framed VPN packets on its own stdio (which the
 *    caller pumps through Java I/O).
 *
 * Control RPC is always served by the daemon over a Unix-domain socket (set
 * via the `control_listen_unix` field in the config). Callers connect via
 * `android.net.LocalSocket`. There is no longer a stdio-RPC mode.
 */
class GephDaemon(
    context: Context,
    config: JsonObject,
) {
    companion object {
        // Matches common ANSI CSI/OSC escape sequences so relayed logs stay readable.
        private val ANSI_ESCAPE_REGEX =
            Regex("""(?:\[[0-?]*[ -/]*[@-~]|\][^]*(?:|\\))""")

        private fun configureNoColor(processBuilder: ProcessBuilder): ProcessBuilder {
            processBuilder.environment().apply {
                put("NO_COLOR", "1")
                put("CLICOLOR", "0")
                put("CLICOLOR_FORCE", "0")
                put("TERM", "dumb")
            }
            return processBuilder
        }

        private fun stripAnsi(text: String): String = text.replace(ANSI_ESCAPE_REGEX, "")
    }

    private val configFile: File
    private val daemonProcess: Process
    private var errorReaderThread: Thread? = null

    init {
        val configString = Json.encodeToString(JsonObject.serializer(), config)
        Log.d("GephDaemon", "starting with $config")
        configFile = File.createTempFile("geph_config_", ".json", context.cacheDir).apply {
            deleteOnExit()
        }
        configFile.writeText(configString)

        val command = mutableListOf(
            context.applicationInfo.nativeLibraryDir + "/libgeph.so",
            "--config",
            configFile.absolutePath,
        )
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            command.add("--stdio-vpn")
        }

        daemonProcess = try {
            val builder = configureNoColor(ProcessBuilder(command))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                builder.redirectInput(ProcessBuilder.Redirect.INHERIT)
            }
            builder.start()
        } catch (e: Exception) {
            throw RuntimeException("Failed to start Geph daemon process", e)
        }

        errorReaderThread = Thread {
            daemonProcess.errorStream.bufferedReader().use { errorReader ->
                try {
                    var line: String?
                    while (errorReader.readLine().also { line = it } != null) {
                        Log.d("GephDaemon", stripAnsi(line!!))
                    }
                } catch (e: Exception) {
                    Log.d("GephDaemon", "exited with $e")
                }
            }
        }.apply { start() }
    }

    fun uploadVpn(arr: ByteArray, len: Int) {
        if (len <= 0) {
            return
        }
        daemonProcess.outputStream.write(len / 256)
        daemonProcess.outputStream.write(len % 256)
        daemonProcess.outputStream.write(arr, 0, len)
        daemonProcess.outputStream.flush()
    }

    fun downloadVpn(bts: ByteArray): Int {
        val a = daemonProcess.inputStream.read()
        val b = daemonProcess.inputStream.read()
        if (a < 0 || b < 0) {
            return -1
        }
        val len = a * 256 + b
        var n = 0
        while (n < len) {
            val read = daemonProcess.inputStream.read(bts, n, len - n)
            if (read < 0) {
                return -1
            }
            n += read
        }
        return n
    }

    val isAlive: Boolean
        get() = try {
            daemonProcess.exitValue()
            false
        } catch (_: IllegalThreadStateException) {
            true
        }

    fun waitForExit(): Int = daemonProcess.waitFor()

    fun stopDaemon() {
        daemonProcess.destroyForcibly()
        errorReaderThread?.interrupt()
        configFile.delete()
    }
}
