import { useCallback, useEffect, useRef } from "react"
import {
  View,
  Text,
  TouchableOpacity,
  ScrollView,
  StyleSheet,
  useColorScheme,
  ActivityIndicator,
} from "react-native"
import { router } from "expo-router"
import { Ionicons } from "@expo/vector-icons"
import { useTranslation } from "react-i18next"
import { useRuntime } from "../src/stores/runtime"
import { runtimeStateLabelKey } from "../src/lib/native-runtime"
import { useConnections } from "../src/stores/connections"

const ON_DEVICE_PORT = 4096
const ON_DEVICE_URL = `http://127.0.0.1:${ON_DEVICE_PORT}`
const ON_DEVICE_ID_HINT = "ondevice"

function stateColor(state: string, isDark: boolean): string {
  switch (state) {
    case "RUNNING":
      return "#22c55e"
    case "STARTING":
      return "#f59e0b"
    case "ERROR":
      return "#ef4444"
    default:
      return isDark ? "#5a5a5a" : "#a3a3a3"
  }
}

export default function RuntimeScreen() {
  const isDark = useColorScheme() === "dark"
  const { t } = useTranslation()
  const runtime = useRuntime()
  const { connections, setActiveConnection, addConnection, testConnection } = useConnections()
  const logListRef = useRef<ScrollView>(null)

  useEffect(() => {
    runtime.attach()
    // Re-sync state when the screen opens (covers the case where the
    // service transitioned while no listener was attached).
    void runtime.refresh()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  useEffect(() => {
    logListRef.current?.scrollToEnd({ animated: false })
  }, [runtime.logs.length])

  const start = useCallback(async () => {
    const result = await runtime.start(ON_DEVICE_PORT)
    if (result.ok) void runtime.refresh()
  }, [runtime])

  const stop = useCallback(async () => {
    await runtime.stop()
    void runtime.refresh()
  }, [runtime])

  const connect = useCallback(async () => {
    const existing = connections.find((c) => c.url === ON_DEVICE_URL)
    const connection = existing ?? {
      name: t("runtime.connectionName"),
      type: "local" as const,
      url: ON_DEVICE_URL,
    }
    if (!existing) {
      await addConnection({ ...connection, directory: undefined })
    }
    // addConnection leaves `active` unset; activating selects the client.
    const added = useConnections
      .getState()
      .connections.find((c) => c.url === ON_DEVICE_URL || c.id.includes(ON_DEVICE_ID_HINT))
    const target = added ?? existing
    if (target) {
      const test = await testConnection(target, "onboarding")
      if (test.ok) {
        await setActiveConnection(target.id)
        router.push("/")
      } else {
        void start()
      }
    }
  }, [connections, addConnection, testConnection, setActiveConnection, t, start])

  const unavailable = runtime.state === "UNAVAILABLE"
  const isRunning = runtime.state === "RUNNING"
  const isStarting = runtime.state === "STARTING" || runtime.busy

  const colors = {
    text: isDark ? "#ffffff" : "#0a0a0a",
    subtext: isDark ? "#a3a3a3" : "#666666",
    card: isDark ? "#141414" : "#f5f5f5",
    border: isDark ? "#2a2a2a" : "#e5e5e5",
  }

  return (
    <View style={[styles.container, { backgroundColor: isDark ? "#0a0a0a" : "#ffffff" }]}>
      <ScrollView contentContainerStyle={styles.scroll}>
        {/* Status card */}
        <View style={[styles.card, { backgroundColor: colors.card, borderColor: colors.border }]}>
          <View style={styles.statusRow}>
            <View style={[styles.statusDot, { backgroundColor: stateColor(runtime.state, isDark) }]} />
            <Text style={[styles.statusText, { color: colors.text }]}>
              {t(runtimeStateLabelKey(runtime.state))}
            </Text>
          </View>
          {runtime.version ? (
            <Text style={[styles.meta, { color: colors.subtext }]}>
              {t("runtime.version")} {runtime.version}
            </Text>
          ) : null}
          {isRunning ? (
            <Text style={[styles.meta, { color: colors.subtext }]}>{ON_DEVICE_URL}</Text>
          ) : null}
          {runtime.lastError ? (
            <Text style={styles.errorText}>{runtime.lastError}</Text>
          ) : null}
        </View>

        {unavailable ? (
          <View style={[styles.card, { backgroundColor: colors.card, borderColor: colors.border }]}>
            <Text style={[styles.unavailableTitle, { color: colors.text }]}>
              {t("runtime.unavailable.title")}
            </Text>
            <Text style={[styles.unavailableBody, { color: colors.subtext }]}>
              {t("runtime.unavailable.body")}
            </Text>
          </View>
        ) : (
          <View style={styles.actions}>
            <TouchableOpacity
              style={[styles.primaryButton, (isRunning || isStarting) && styles.buttonDisabled]}
              disabled={isRunning || isStarting}
              onPress={() => void start()}
            >
              {isStarting ? (
                <ActivityIndicator size="small" color="#ffffff" />
              ) : (
                <>
                  <Ionicons name="play" size={18} color="#ffffff" />
                  <Text style={styles.primaryButtonText}>{t("runtime.start")}</Text>
                </>
              )}
            </TouchableOpacity>

            <TouchableOpacity
              style={[styles.secondaryButton, !isRunning && styles.buttonDisabled, { borderColor: colors.border }]}
              disabled={!isRunning}
              onPress={() => void stop()}
            >
              <Ionicons name="stop" size={18} color={isDark ? "#ffffff" : "#0a0a0a"} />
              <Text style={[styles.secondaryButtonText, { color: colors.text }]}>{t("runtime.stop")}</Text>
            </TouchableOpacity>

            <TouchableOpacity
              style={[styles.primaryButton, styles.connectButton, !isRunning && styles.buttonDisabled]}
              disabled={!isRunning}
              onPress={() => void connect()}
            >
              <Ionicons name="arrow-forward" size={18} color="#ffffff" />
              <Text style={styles.primaryButtonText}>{t("runtime.connect")}</Text>
            </TouchableOpacity>
          </View>
        )}

        {/* Log tail */}
        {!unavailable ? (
          <View style={styles.logSection}>
            <Text style={[styles.logTitle, { color: colors.subtext }]}>{t("runtime.logs")}</Text>
            <View style={[styles.logBox, { backgroundColor: colors.card, borderColor: colors.border }]}>
              <ScrollView contentContainerStyle={styles.logContent} ref={logListRef}>
                {runtime.logs.length === 0 ? (
                  <Text style={[styles.logEmpty, { color: colors.subtext }]}>{t("runtime.logsEmpty")}</Text>
                ) : (
                  runtime.logs.map((line, index) => (
                    <Text key={index} style={[styles.logLine, { color: colors.subtext }]}>
                      {line}
                    </Text>
                  ))
                )}
              </ScrollView>
            </View>
          </View>
        ) : null}
      </ScrollView>
    </View>
  )
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
  },
  scroll: {
    padding: 16,
    paddingBottom: 48,
  },
  card: {
    borderRadius: 12,
    borderWidth: 1,
    padding: 16,
    gap: 8,
  },
  statusRow: {
    flexDirection: "row",
    alignItems: "center",
    gap: 10,
  },
  statusDot: {
    width: 10,
    height: 10,
    borderRadius: 5,
  },
  statusText: {
    fontSize: 17,
    fontWeight: "600",
  },
  meta: {
    fontSize: 13,
  },
  errorText: {
    fontSize: 13,
    color: "#ef4444",
  },
  actions: {
    gap: 10,
    marginTop: 16,
  },
  primaryButton: {
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "center",
    gap: 8,
    padding: 14,
    borderRadius: 10,
    backgroundColor: "#0a0a0a",
  },
  connectButton: {
    backgroundColor: "#6366f1",
  },
  primaryButtonText: {
    fontSize: 15,
    fontWeight: "600",
    color: "#ffffff",
  },
  secondaryButton: {
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "center",
    gap: 8,
    padding: 14,
    borderRadius: 10,
    borderWidth: 1,
  },
  secondaryButtonText: {
    fontSize: 15,
    fontWeight: "600",
  },
  buttonDisabled: {
    opacity: 0.5,
  },
  unavailableTitle: {
    fontSize: 15,
    fontWeight: "600",
  },
  unavailableBody: {
    fontSize: 13,
    lineHeight: 20,
  },
  logSection: {
    marginTop: 24,
    gap: 6,
  },
  logTitle: {
    fontSize: 12,
    textTransform: "uppercase",
    letterSpacing: 0.5,
  },
  logBox: {
    borderRadius: 12,
    borderWidth: 1,
    maxHeight: 260,
  },
  logContent: {
    padding: 12,
  },
  logLine: {
    fontFamily: "monospace",
    fontSize: 11,
    lineHeight: 16,
  },
  logEmpty: {
    fontSize: 12,
  },
})
