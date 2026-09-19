package cc.agentlabs.opencode.runtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.content.ContextCompat
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.modules.core.DeviceEventManagerModule

/**
 * Bridges the embedded on-device opencode runtime (single-file bionic ELF,
 * packaged as libopencode.so under nativeLibraryDir) to JS.
 *
 * Why nativeLibraryDir: since Android 10, apps targeting API 29+ may no longer
 * exec() files in their writable data directory, so a Termux-style prefix
 * inside filesDir is impossible. Binaries shipped as jniLibs entries are
 * extracted by the installer into the read-only, executable nativeLibraryDir.
 * This requires legacy lib packaging (expo.useLegacyPackaging=true) so the
 * .so files are actually extracted to disk instead of mmap'd from the APK.
 *
 * The server process itself is owned by [OpencodeRuntimeService] (a foreground
 * service so Android won't kill the agent mid-task); this module only talks to
 * the service's shared state holder and starts/stops it.
 */
class OpencodeRuntimeModule(private val reactContext: ReactApplicationContext) :
  ReactContextBaseJavaModule(reactContext) {

  companion object {
    const val NAME = "OpencodeRuntime"
    const val EVENT_LOG = "OpencodeRuntimeLog"
    const val EVENT_STATE = "OpencodeRuntimeState"
    private const val DEFAULT_PORT = 4096
  }

  override fun getName(): String = NAME

  // ---------------------------------------------------------------- paths

  /** Absolute path of the embedded binary, or null if not bundled for this ABI. */
  private fun binaryPath(): String? {
    val libDir = reactContext.applicationInfo.nativeLibraryDir ?: return null
    val candidate = java.io.File(libDir, "libopencode.so")
    return if (candidate.exists()) candidate.absolutePath else null
  }

  /** Isolated HOME for the runtime: $filesDir/opencode-home (writable, no exec). */
  private fun homeDir(): java.io.File =
    java.io.File(reactContext.filesDir, "opencode-home").apply { mkdirs() }

  override fun getConstants(): Map<String, Any> = mapOf(
    "defaultPort" to DEFAULT_PORT,
  )

  // ---------------------------------------------------------------- state

  private val stateReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
      val state = intent?.getStringExtra(OpencodeRuntimeService.EXTRA_STATE) ?: return
      emitState(state)
    }
  }

  private val logReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
      val line = intent?.getStringExtra(OpencodeRuntimeService.EXTRA_LOG_LINE) ?: return
      emitLog(line)
    }
  }

  private var receiversRegistered = false

  private fun ensureReceivers() {
    if (receiversRegistered) return
    if (Build.VERSION.SDK_INT >= 33) {
      ContextCompat.registerReceiver(
        reactContext, stateReceiver, IntentFilter(OpencodeRuntimeService.ACTION_STATE_CHANGED),
        ContextCompat.RECEIVER_NOT_EXPORTED,
      )
      ContextCompat.registerReceiver(
        reactContext, logReceiver, IntentFilter(OpencodeRuntimeService.ACTION_LOG),
        ContextCompat.RECEIVER_NOT_EXPORTED,
      )
    } else {
      reactContext.registerReceiver(stateReceiver, IntentFilter(OpencodeRuntimeService.ACTION_STATE_CHANGED))
      reactContext.registerReceiver(logReceiver, IntentFilter(OpencodeRuntimeService.ACTION_LOG))
    }
    receiversRegistered = true
  }

  private fun emitState(state: String) {
    // Emit a bare string: the JS side's DeviceEventEmitter listener passes
    // params straight through, and wrapping in a WritableMap ({state: ...})
    // would leak the map object into store state (v1/v2 crash root cause).
    reactContext
      .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
      ?.emit(EVENT_STATE, state)
  }

  private fun emitLog(line: String) {
    reactContext
      .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
      ?.emit(EVENT_LOG, line)
  }

  override fun invalidate() {
    if (receiversRegistered) {
      reactContext.unregisterReceiver(stateReceiver)
      reactContext.unregisterReceiver(logReceiver)
      receiversRegistered = false
    }
    super.invalidate()
  }

  // ---------------------------------------------------------------- methods

  @ReactMethod
  fun isAvailable(promise: Promise) {
    promise.resolve(binaryPath() != null)
  }

  /**
   * Runs `libopencode.so --version` synchronously (bounded to ~20s) and
   * resolves with the trimmed first line. Also serves as the cold-start
   * warmup: the first run populates Bun's compile cache under
   * $HOME/.bun, so later `serve` starts are fast (see opencode-termux
   * docs/tui-common-fix.md — cold cache makes first start slow).
   */
  @ReactMethod
  fun version(promise: Promise) {
    val bin = binaryPath()
    if (bin == null) {
      promise.reject("UNAVAILABLE", "libopencode.so is not bundled for this ABI")
      return
    }
    Thread {
      try {
        val proc = OpencodeRuntimeService.spawn(reactContext, listOf(bin, "--version"))
        val output = StringBuilder()
        proc.inputStream.bufferedReader().forEachLine { line ->
          if (output.isEmpty()) output.append(line.trim()) else output.append('\n').append(line)
        }
        val finished = proc.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)
        if (!finished) {
          proc.destroyForcibly()
          promise.reject("TIMEOUT", "opencode --version timed out")
          return@Thread
        }
        if (proc.exitValue() != 0) {
          promise.reject("EXIT_${proc.exitValue()}", "opencode --version exited with ${proc.exitValue()}")
          return@Thread
        }
        promise.resolve(output.toString().substringBefore('\n'))
      } catch (e: Exception) {
        promise.reject("VERSION_FAILED", e.message ?: e.javaClass.simpleName)
      }
    }.start()
  }

  @ReactMethod
  fun startServer(port: Double, promise: Promise) {
    val bin = binaryPath()
    if (bin == null) {
      promise.reject("UNAVAILABLE", "libopencode.so is not bundled for this ABI")
      return
    }
    val safePort = port.toInt().takeIf { it in 1..65535 } ?: DEFAULT_PORT
    if (OpencodeRuntimeService.state == OpencodeRuntimeService.State.RUNNING) {
      promise.resolve(startResult(OpencodeRuntimeService.port, true))
      return
    }
    ensureReceivers()
    val intent = Intent(reactContext, OpencodeRuntimeService::class.java).apply {
      putExtra(OpencodeRuntimeService.EXTRA_PORT, safePort)
    }
    ContextCompat.startForegroundService(reactContext, intent)
    promise.resolve(startResult(safePort, false))
  }

  private fun startResult(port: Int, alreadyRunning: Boolean) =
    Arguments.createMap().apply {
      putInt("port", port)
      putBoolean("alreadyRunning", alreadyRunning)
    }

  @ReactMethod
  fun stopServer(promise: Promise) {
    // Deliver ACTION_STOP via startService so the running service receives
    // onStartCommand and performs the graceful foreground stop itself
    // (stopService() alone cannot stop a foreground service).
    val intent = Intent(reactContext, OpencodeRuntimeService::class.java).apply {
      action = OpencodeRuntimeService.ACTION_STOP
    }
    try {
      reactContext.startService(intent)
    } catch (_: IllegalStateException) {
      // Service not running (app in background without the service alive) — nothing to stop.
    }
    promise.resolve(true)
  }

  @ReactMethod
  fun getState(promise: Promise) {
    ensureReceivers()
    val result = Arguments.createMap().apply {
      putString("state", OpencodeRuntimeService.state.name)
      putInt("port", OpencodeRuntimeService.port)
      putBoolean("available", binaryPath() != null)
      putString("homePath", homeDir().absolutePath)
    }
    promise.resolve(result)
  }

  /** Returns the most recent log lines (ring buffer owned by the service). */
  @ReactMethod
  fun getRecentLogs(promise: Promise) {
    val lines = OpencodeRuntimeService.recentLogs()
    val array = com.facebook.react.bridge.Arguments.createArray().apply {
      lines.forEach { pushString(it) }
    }
    promise.resolve(array)
  }

  @ReactMethod
  fun warmup(promise: Promise) {
    // Kept for JS symmetry with version(); warmup == version probe.
    version(promise)
  }

  @ReactMethod
  fun addListener(eventName: String) {
    // Required for RN event emitter bookkeeping (no-op).
  }

  @ReactMethod
  fun removeListeners(count: Double) {
    // Required for RN event emitter bookkeeping (no-op).
  }
}
