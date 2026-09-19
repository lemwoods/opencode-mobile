import { create } from "zustand"
import {
  getStatus,
  getVersion,
  startServer,
  stopServer,
  getRecentLogs,
  onRuntimeLog,
  onRuntimeState,
  type RuntimeState,
} from "../lib/native-runtime"
import { addBreadcrumb } from "../lib/sentry"

const MAX_LOG_LINES = 400
/** Coalesce window for batched log ingestion (see attach()). */
const LOG_FLUSH_MS = 300

export interface RuntimeStore {
  // Mirror of the native service state; "UNAVAILABLE" means the build
  // doesn't bundle the embedded binary (non-Android or wrong ABI).
  state: RuntimeState | "UNAVAILABLE"
  port: number
  version: string | null
  logs: string[]
  busy: boolean
  lastError: string | null
  // Wire up native event listeners once; safe to call repeatedly.
  attach: () => void
  refresh: () => Promise<void>
  start: (port?: number) => Promise<{ ok: boolean; port: number }>
  stop: () => Promise<void>
}

let listenersAttached = false

export const useRuntime = create<RuntimeStore>((set, get) => ({
  state: "UNAVAILABLE",
  port: 0,
  version: null,
  logs: [],
  busy: false,
  lastError: null,

  attach: () => {
    if (listenersAttached) return
    listenersAttached = true

    onRuntimeState((state) => {
      // String coercion is the last line of defense: an object payload here
      // would poison every state comparison downstream (v1 crash).
      set({ state: (typeof state === "string" ? state : String(state)) as RuntimeState })
    })

    // Log batching: the server can emit hundreds of lines during cold Bun
    // startup; pushing each one through set() re-renders the screen per line
    // and pegs the JS thread (reported as near-freeze on-device). Buffer and
    // flush coalesced at most every LOG_FLUSH_MS instead.
    let pending: string[] = []
    let flushTimer: ReturnType<typeof setTimeout> | null = null
    const flush = () => {
      flushTimer = null
      if (pending.length === 0) return
      const batch = pending
      pending = []
      const merged = [...get().logs, ...batch]
      set({
        logs: merged.length > MAX_LOG_LINES ? merged.slice(merged.length - MAX_LOG_LINES) : merged,
      })
    }
    onRuntimeLog((line) => {
      // Only strings may enter the log ring — objects crashed React (v2).
      const text = typeof line === "string" ? line : String(line)
      if (!text) return
      pending.push(text)
      if (pending.length >= 100) {
        if (flushTimer) clearTimeout(flushTimer)
        flush()
        return
      }
      if (flushTimer == null) {
        flushTimer = setTimeout(flush, LOG_FLUSH_MS)
      }
    })

    // Initial sync so a freshly opened app reflects a server that is
    // already running in the background (e.g. after process death of the
    // JS runtime while the foreground service kept the agent alive).
    void get().refresh()
  },

  refresh: async () => {
    const status = await getStatus()
    if (!status.available) {
      set({ state: "UNAVAILABLE" })
      return
    }
    set({ state: status.state, port: status.port })
    if (status.state === "RUNNING" && get().version === null) {
      const v = await getVersion()
      if (v) set({ version: v })
    }
    const recent = (await getRecentLogs()).filter((l): l is string => typeof l === "string")
    if (recent.length > 0) set({ logs: recent })
  },

  start: async (portArg) => {
    const port = portArg ?? 4096
    if (get().state === "UNAVAILABLE") {
      return { ok: false, port }
    }
    set({ busy: true, lastError: null, state: "STARTING" })
    addBreadcrumb({ category: "runtime", message: `starting embedded server on port ${port}` })
    try {
      const result = await startServer(port)
      if (!result) {
        set({ busy: false, state: "ERROR", lastError: "Native module unavailable" })
        return { ok: false, port }
      }
      set({ busy: false, port: result.port, state: result.alreadyRunning ? "RUNNING" : "STARTING" })
      return { ok: true, port: result.port }
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error)
      set({ busy: false, state: "ERROR", lastError: message })
      return { ok: false, port }
    }
  },

  stop: async () => {
    set({ busy: true })
    try {
      await stopServer()
      set({ state: "STOPPED", busy: false })
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error)
      set({ busy: false, lastError: message })
    }
  },
}))
