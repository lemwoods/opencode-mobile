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
      set({ state: state as RuntimeState })
    })
    onRuntimeLog((line) => {
      const logs = [...get().logs, line]
      set({ logs: logs.length > MAX_LOG_LINES ? logs.slice(logs.length - MAX_LOG_LINES) : logs })
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
    const recent = await getRecentLogs()
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
