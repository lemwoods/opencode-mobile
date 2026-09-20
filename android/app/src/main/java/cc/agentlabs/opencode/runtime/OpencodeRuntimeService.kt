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
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Process
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
    const val EXTRA_LOG_LINES = "lines"
    const val ACTION_STATE_CHANGED = "cc.agentlabs.opencode.runtime.STATE_CHANGED"
    const val ACTION_LOG = "cc.agentlabs.opencode.runtime.LOG"
    /** Sent by the RN module to request a graceful shutdown. */
    const val ACTION_STOP = "cc.agentlabs.opencode.runtime.STOP"

    private const val CHANNEL_ID = "opencode_runtime"
    private const val NOTIFICATION_ID = 40961
    private const val LOG_RING_SIZE = 400
    /** Coalesce window for JS-facing log broadcasts (see [broadcastLog]). */
    private const val LOG_BROADCAST_MS = 300L
    /**
     * Scheduler priority (nice) applied to the server and its LSP children so
     * a busy agent starves UI threads in the shared cpuset — the app stays
     * responsive; the agent just yields CPU under contention.
     */
    private const val CHILD_NICE = 10

    /** Shared across the process so the RN module can read it synchronously. */
    @Volatile
    var state: State = State.IDLE
      private set

    @Volatile
    var port: Int = 4096
      private set

    @Volatile
    private var process: java.lang.Process? = null

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
    fun spawn(context: Context, command: List<String>, cwd: File? = null): java.lang.Process {
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
      return START_NOT_STICKY
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

    // Spawn off the main thread: forking a 180MB ELF (plus the cold Bun
    // runtime doing JSC module-graph evaluation) stalls the app's main
    // thread badly enough to freeze the phone UI while the process warms up.
    Thread({
      Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
      try {
        val proc = spawn(
          this,
          listOf(bin.absolutePath, "serve", "--hostname", "127.0.0.1", "--port", port.toString()),
        )
        process = proc
        // Best-effort child hardening: java.lang.Process has no pid() on
        // Android, so reach into android.os.ProcessManager$ProcessImpl.
        val pid = try {
          proc.javaClass.getMethod("getPid").invoke(proc) as Int
        } catch (_: Throwable) {
          -1
        }
        if (pid > 0) {
          // 1) OOM: under memory pressure the (heavy) server is the preferred
          //    LMK victim instead of the app/system thrashing.
          try {
            File("/proc/$pid/oom_score_adj").writeText("400")
          } catch (e: Throwable) {
            log("could not set oom_score_adj: ${e.message}")
          }
          // 2) CPU: renice the server + any LSP children it spawns so they
          //    yield to UI threads in the shared cpuset.
          reniceTree(pid)
          for (delayMs in longArrayOf(2_000, 6_000, 15_000)) {
            eventsHandler.postDelayed({ reniceTree(pid) }, delayMs)
          }
          log("child pid $pid: oom_score_adj=400, renice=$CHILD_NICE (+descendants)")
        }

        // Pump stdout+stderr (merged) into the ring buffer and broadcasts.
        Thread({
          Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
          for (line in proc.inputStream.bufferedReader().lines()) {
            if (line.isBlank()) continue
            pushLog(line)
            broadcastLog(line)
          }
        }, "opencode-stdout").start()

        // Watch for process exit; publish the terminal state.
        Thread({
          Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
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
      } catch (e: Exception) {
        log("failed to spawn: ${e.message}")
        transition(State.ERROR)
        stopSelf()
      }
    }, "opencode-spawn").start()

    return START_NOT_STICKY
  }

  override fun onDestroy() {
    // Deliver any coalesced log lines before the events thread goes away.
    eventsHandler.post { flushLogs() }
    eventsThread.quitSafely()
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

  /**
   * Single background thread for JS-facing event work: log batching, renice
   * scans, and anything that must never run on the main thread.
   */
  private val eventsThread = HandlerThread("opencode-events").apply { start() }
  private val eventsHandler = Handler(eventsThread.looper)
  private val pendingLines = ArrayList<String>()
  @Volatile
  private var flushScheduled = false

  /**
   * Batches log lines: one broadcast per 300ms window carrying a String list,
   * instead of one broadcast (→ one main-thread onReceive → one bridge event)
   * per line. A chatty server can no longer wake the UI thread per line.
   */
  private fun broadcastLog(line: String) {
    eventsHandler.post {
      pendingLines.add(line)
      if (!flushScheduled) {
        flushScheduled = true
        eventsHandler.postDelayed({ flushLogs() }, LOG_BROADCAST_MS)
      }
    }
  }

  private fun flushLogs() {
    flushScheduled = false
    if (pendingLines.isEmpty()) return
    val lines = ArrayList(pendingLines)
    pendingLines.clear()
    sendBroadcast(
      Intent(ACTION_LOG).setPackage(packageName).putStringArrayListExtra(EXTRA_LOG_LINES, lines),
    )
  }

  /**
   * Applies [CHILD_NICE] to the server process and its direct children
   * (opencode spawns LSP servers). Uses toybox `renice` from /system/bin —
   * android.system.Os has no setpriority in the public SDK, and raising nice
   * on own-uid pids needs no privileges. Best-effort by design.
   */
  private fun reniceTree(rootPid: Int) {
    if (rootPid <= 0) return
    val targets = ArrayList<Int>(4)
    targets.add(rootPid)
    try {
      val procDir = File("/proc")
      for (f in procDir.listFiles() ?: return) {
        val pid = f.name.toIntOrNull() ?: continue
        try {
          val stat = File(f, "stat").readText()
          val close = stat.lastIndexOf(')')
          if (close < 0) continue
          val fields = stat.substring(close + 2).split(" ")
          if (fields.size > 1 && fields[1].toIntOrNull() == rootPid) targets.add(pid)
        } catch (_: Throwable) {
          // Not our process or gone — skip.
        }
      }
    } catch (_: Throwable) {
    }
    for (pid in targets) {
      try {
        ProcessBuilder("renice", "-n", CHILD_NICE.toString(), "-p", pid.toString())
          .redirectErrorStream(true)
          .start()
      } catch (_: Throwable) {
        // renice unavailable or pid gone — skip.
      }
    }
  }

  private fun transition(next: State) {
    state = next
    sendBroadcast(
      Intent(ACTION_STATE_CHANGED).setPackage(packageName).putExtra(EXTRA_STATE, next.name),
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
