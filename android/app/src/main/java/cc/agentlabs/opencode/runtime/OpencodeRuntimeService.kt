package cc.agentlabs.opencode.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import java.io.File
import java.util.ArrayDeque

/**
 * Foreground service owning the embedded opencode server process
 * (`libopencode.so serve --hostname 127.0.0.1 --port N`).
 *
 * Foreground is required so Android does not kill the agent mid-task when
 * the UI goes to the background; the notification also gives the user a
 * visible "local agent is running" affordance.
 *
 * All process environment setup lives in [spawn]; the service publishes
 * state and log lines through app-local broadcasts consumed by
 * [OpencodeRuntimeModule] (they never leave the device).
 */
class OpencodeRuntimeService : Service() {

  companion object {
    private const val TAG = "OpencodeRuntime"
    const val EXTRA_PORT = "port"
    const val EXTRA_STATE = "state"
    const val EXTRA_LOG_LINE = "line"
    const val ACTION_STATE_CHANGED = "cc.agentlabs.opencode.runtime.STATE_CHANGED"
    const val ACTION_LOG = "cc.agentlabs.opencode.runtime.LOG"
    /** Sent by the RN module to request a graceful shutdown. */
    const val ACTION_STOP = "cc.agentlabs.opencode.runtime.STOP"

    private const val CHANNEL_ID = "opencode_runtime"
    private const val NOTIFICATION_ID = 40961
    private const val LOG_RING_SIZE = 400

    /** Shared across the process so the RN module can read it synchronously. */
    @Volatile
    var state: State = State.IDLE
      private set

    @Volatile
    var port: Int = 4096
      private set

    @Volatile
    private var process: Process? = null

    private val logRing = ArrayDeque<String>(LOG_RING_SIZE)

    fun recentLogs(): List<String> = synchronized(logRing) { logRing.toList() }

    private fun pushLog(line: String) {
      synchronized(logRing) {
        if (logRing.size >= LOG_RING_SIZE) logRing.removeFirst()
        logRing.addLast(line)
      }
    }

    /**
     * Builds the child environment for the native binary:
     * - HOME under our (writable, non-executable) files dir — opencode keeps
     *   config/auth/Bun cache there;
     * - PATH/LD_LIBRARY_PATH include nativeLibraryDir so any companion .so
     *   (e.g. libopencode-crhandler.so) resolves without a Termux prefix;
     * - TERM=dumb + NO_COLOR keep `serve` headless.
     */
    fun spawn(context: Context, command: List<String>, cwd: File? = null): Process {
      val appInfo = context.applicationInfo
      val home = File(context.filesDir, "opencode-home").apply { mkdirs() }
      val tmp = File(home, "tmp").apply { mkdirs() }
      val workspace = File(home, "workspace").apply { mkdirs() }
      val libDir = appInfo.nativeLibraryDir ?: ""

      val pb = ProcessBuilder(command).apply {
        redirectErrorStream(true)
        environment().apply {
          put("HOME", home.absolutePath)
          put("TMPDIR", tmp.absolutePath)
          put("LD_LIBRARY_PATH", libDir)
          put("PATH", listOf(libDir, "/system/bin", "/system/xbin").joinToString(":"))
          put("TERM", "dumb")
          put("NO_COLOR", "1")
        }
        if (cwd != null) directory(cwd) else directory(workspace)
      }
      return pb.start()
    }
  }

  enum class State { IDLE, STARTING, RUNNING, STOPPED, ERROR }

  // ------------------------------------------------------------ service

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    // Graceful stop path: the module sends ACTION_STOP instead of calling
    // stopService() — a foreground service ignores stopService until it
    // leaves the foreground itself.
    if (intent?.action == ACTION_STOP) {
      process?.destroy()
      process = null
      transition(State.STOPPED)
      stopForeground(STOP_FOREGROUND_REMOVE)
      stopSelf()
      return START_NOT_STICKY
    }

    val requestedPort = intent?.getIntExtra(EXTRA_PORT, 4096) ?: 4096
    if (process?.isAlive == true) {
      // Already running: keep the current process (port unchanged).
      return START_STICKY
    }
    createChannel()
    startAsForeground()

    val bin = File(applicationInfo.nativeLibraryDir ?: "", "libopencode.so")
    if (!bin.exists()) {
      transition(State.ERROR)
      stopSelf()
      return START_NOT_STICKY
    }

    port = requestedPort
    transition(State.STARTING)
    log("starting opencode serve on 127.0.0.1:$port")

    try {
      val proc = spawn(
        this,
        listOf(bin.absolutePath, "serve", "--hostname", "127.0.0.1", "--port", port.toString()),
      )
      process = proc
    } catch (e: Exception) {
      log("failed to spawn: ${e.message}")
      transition(State.ERROR)
      stopSelf()
      return START_NOT_STICKY
    }

    // Pump stdout+stderr (merged) into the ring buffer and broadcasts.
    Thread({
      for (line in proc.inputStream.bufferedReader().lines()) {
        pushLog(line)
        broadcastLog(line)
      }
    }, "opencode-stdout").start()

    // Watch for process exit; publish the terminal state.
    Thread({
      val exit = proc.waitFor()
      log("opencode serve exited with code $exit")
      transition(if (exit == 0) State.STOPPED else State.ERROR)
      stopSelf()
    }, "opencode-exit-watch").start()

    // Mark RUNNING once the process survived its first moment; the JS layer
    // additionally probes /health before flipping its own UI to "connected".
    Thread({
      try {
        Thread.sleep(1500)
        if (proc.isAlive) transition(State.RUNNING)
      } catch (_: InterruptedException) {
      }
    }, "opencode-run-marker").start()

    return START_STICKY
  }

  override fun onDestroy() {
    process?.destroy()
    process = null
    if (state == State.RUNNING || state == State.STARTING) transition(State.STOPPED)
    super.onDestroy()
  }

  override fun onTaskRemoved(rootIntent: Intent?) {
    // Deliberately keep running: the point of the runtime is background agents.
    super.onTaskRemoved(rootIntent)
  }

  // ------------------------------------------------------------ plumbing

  private fun transition(next: State) {
    state = next
    sendBroadcast(
      Intent(ACTION_STATE_CHANGED).setPackage(packageName).putExtra(EXTRA_STATE, next.name),
    )
  }

  private fun broadcastLog(line: String) {
    sendBroadcast(
      Intent(ACTION_LOG).setPackage(packageName).putExtra(EXTRA_LOG_LINE, line),
    )
  }

  private fun log(line: String) {
    Log.d(TAG, line)
    pushLog(line)
    broadcastLog(line)
  }

  private fun createChannel() {
    if (Build.VERSION.SDK_INT >= 26) {
      val channel = NotificationChannel(
        CHANNEL_ID,
        "On-device runtime",
        NotificationManager.IMPORTANCE_LOW,
      ).apply {
        description = "Embedded opencode server status"
        setShowBadge(false)
      }
      getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
  }

  private fun startAsForeground() {
    val launch = packageManager.getLaunchIntentForPackage(packageName)
    val pending = PendingIntent.getActivity(
      this, 0, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val builder = if (Build.VERSION.SDK_INT >= 26) {
      Notification.Builder(this, CHANNEL_ID)
    } else {
      @Suppress("DEPRECATION")
      Notification.Builder(this)
    }
    val notification = builder
      .setContentTitle("OpenCode agent running")
      .setContentText("Local server on 127.0.0.1:$port")
      .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
      .setOngoing(true)
      .setContentIntent(pending)
      .build()

    if (Build.VERSION.SDK_INT >= 29) {
      startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    } else {
      startForeground(NOTIFICATION_ID, notification)
    }
  }
}
