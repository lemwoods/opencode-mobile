// Embedded on-device opencode runtime bridge.
//
// The Android app ships the community "native mainline" opencode build
// (single bionic ELF, zero glibc deps) as libopencode.so in jniLibs and
// execs it from nativeLibraryDir via the OpencodeRuntimeService. This module
// is the typed JS surface of that Kotlin native module.
//
// On iOS, Expo Go, or any build where the native module is absent, every
// call degrades to an explicit "unavailable" result instead of throwing.
import { NativeModules, DeviceEventEmitter, EmitterSubscription, Platform } from "react-native"

export type RuntimeState = "IDLE" | "STARTING" | "RUNNING" | "STOPPED" | "ERROR"

export interface RuntimeStatus {
  state: RuntimeState
  port: number
  available: boolean
  homePath: string
}

export interface StartResult {
  port: number
  alreadyRunning: boolean
}

interface OpencodeRuntimeNativeModule {
  isAvailable(): Promise<boolean>
  version(): Promise<string>
  startServer(port: number): Promise<StartResult>
  stopServer(): Promise<boolean>
  getState(): Promise<RuntimeStatus>
  getRecentLogs(): Promise<string[]>
  warmup(): Promise<string>
  addListener(event: string): void
  removeListeners(count: number): void
}

const native: OpencodeRuntimeNativeModule | undefined =
  Platform.OS === "android" ? (NativeModules.OpencodeRuntime as OpencodeRuntimeNativeModule | undefined) : undefined

/** True when the embedded runtime binary is bundled for this device. */
export function isRuntimeAvailable(): boolean {
  return native !== undefined
}

export async function getStatus(): Promise<RuntimeStatus> {
  if (!native) {
    return { state: "IDLE", port: 0, available: false, homePath: "" }
  }
  return native.getState()
}

export async function getVersion(): Promise<string | null> {
  if (!native) return null
  try {
    return await native.version()
  } catch {
    return null
  }
}

export async function startServer(port: number): Promise<StartResult | null> {
  if (!native) return null
  return native.startServer(port)
}

export async function stopServer(): Promise<boolean> {
  if (!native) return false
  return native.stopServer()
}

export async function getRecentLogs(): Promise<string[]> {
  if (!native) return []
  return native.getRecentLogs()
}

// The Kotlin module re-broadcasts service state/log lines through
// RCTDeviceEventEmitter; both events are device-local only.
export const RUNTIME_LOG_EVENT = "OpencodeRuntimeLog"
export const RUNTIME_STATE_EVENT = "OpencodeRuntimeState"

export function onRuntimeLog(listener: (line: string) => void): EmitterSubscription {
  return DeviceEventEmitter.addListener(RUNTIME_LOG_EVENT, listener)
}

export function onRuntimeState(listener: (state: string) => void): EmitterSubscription {
  return DeviceEventEmitter.addListener(RUNTIME_STATE_EVENT, listener)
}

/**
 * Static i18n key for a runtime state. Uses a fixed map instead of dynamic
 * key composition (`t(`runtime.state.${state.toLowerCase()}`)`) so every key
 * is statically analyzable, i18next-catalog-verifiable, and immune to
 * undefined-state edge cases at render time.
 */
const STATE_LABEL_KEYS: Record<string, string> = {
  IDLE: "runtime.state.idle",
  STARTING: "runtime.state.starting",
  RUNNING: "runtime.state.running",
  STOPPED: "runtime.state.stopped",
  ERROR: "runtime.state.error",
  UNAVAILABLE: "runtime.state.unavailable",
}

export function runtimeStateLabelKey(state: string | undefined | null): string {
  return STATE_LABEL_KEYS[state ?? "UNAVAILABLE"] ?? STATE_LABEL_KEYS.UNAVAILABLE
}
