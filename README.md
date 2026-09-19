# OpenCode Mobile — Embedded On-Device Runtime Fork

> **This fork** ([lemwoods/opencode-mobile](https://github.com/lemwoods/opencode-mobile)) adds an
> **embedded, on-device opencode server**: the app bundles a native Android build of the
> opencode runtime and runs it directly on your phone — **no laptop, no VPS, no tunnel needed**.
> Forked from [dzianisv/opencode-mobile](https://github.com/dzianisv/opencode-mobile) (upstream
> attribution fully preserved below).

**The open-source Android client for the [opencode](https://github.com/sst/opencode) AI coding agent.**
AI-assisted coding from your phone — either against your own self-hosted server, or **fully
on-device** with the embedded runtime.

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Embedded runtime](https://img.shields.io/badge/on--device_runtime-opencode_1.18.30-6366f1)](docs/EMBEDDED-RUNTIME.md)
[![Upstream](https://img.shields.io/badge/fork_of-dzianisv%2Fopencode--mobile-8b8b8b)](https://github.com/dzianisv/opencode-mobile)

> **Not affiliated with opencode.** OpenCode Mobile is an independent, community-built client and is
> not made by, endorsed by, or affiliated with the opencode / Anomaly team. It talks to an opencode
> server you run yourself (or, in this fork, one that runs inside the app), using opencode's open HTTP API.

---

## 📱 What this fork adds: on-device opencode (中文说明)

本 fork 在原版基础上**内置了可在手机上原生运行的 opencode 服务器**：

- 集成社区 [opencode-termux](https://github.com/Hope2333/opencode-termux) 的 **native 主线单文件
  Bionic ELF**（零 glibc 依赖，Android API ≥ 28），以 `libopencode.so` 形式打入 `jniLibs`
- Connections 页出现**本机运行时**状态卡片 → 进入运行时页面 → **启动服务** → `opencode serve`
  在 `127.0.0.1:4096` 监听 → **Connect**，即可以手机为算力跑 agent
- 前台服务保活（锁屏不中断）、实时服务日志、启动/停止/一键连接
- 技术约束与设计细节见 [docs/EMBEDDED-RUNTIME.md](docs/EMBEDDED-RUNTIME.md)（为什么必须走
  jniLibs/nativeLibraryDir、为什么不用 Termux 前缀、内存与 LMK 策略等）

**已知限制**：arm64-v8a 设备；运行时进程约 1-2GB 内存占用（180MB 二进制的固有代价，已做
OOM 保护优先保 app）；冷启动有 1-2 秒 CPU 峰值；本 fork 的 APK 为自签证书（直装可用）。

---

## How the embedded runtime works

```
┌─────────────────────────────────────────────┐
│            OpenCode Mobile (this fork)      │
│   React Native UI  ←── HTTP + SSE ──┐       │
│                                     │       │
│  ┌──────────────────────────────────▼────┐  │
│  │ OpencodeRuntimeService (FGS, Kotlin)  │  │
│  │  exec libopencode.so serve            │  │
│  │  HOME=app-files  LD_LIBRARY_PATH=…    │  │
│  │  oom_score_adj=400  log ring buffer   │  │
│  └───────────────────────────────────────┘  │
└─────────────────────────┬───────────────────┘
                          │ 127.0.0.1:4096 only
                          ▼
             embedded opencode (single bionic ELF,
             github.com/Hope2333/opencode-termux)
                          │
                          ▼
                Your AI provider (your keys)
```

All model calls go directly from the on-device (or remote) server to your provider; the app never
proxies code or conversations through third-party servers.

### Build the embedded runtime from source

```bash
git clone https://github.com/lemwoods/opencode-mobile.git
cd opencode-mobile

# 1. fetch the pinned native runtime (~180MB, SHA-256 verified)
scripts/fetch-opencode-native.sh        # v1.18.30 pinned; pass a tag to override

# 2. install JS deps + build the APK (needs JDK 17 + Android SDK 35/36)
npm ci
cd android && JAVA_HOME=$JDK17 ./gradlew assembleRelease --no-daemon
# → app/build/outputs/apk/release/app-release.apk (~101MB)
```

`expo.useLegacyPackaging=true` (already set) is required — the ELF must land in the extracted
`nativeLibraryDir` to be executable under the targetSdk 29+ data-dir exec ban. Full background:
**[docs/EMBEDDED-RUNTIME.md](docs/EMBEDDED-RUNTIME.md)**.

---


**New: tap "Try a Demo" in the app to see the agent fix a real bug — reasoning, a grep, a diff, a permission prompt — in about 30 seconds, no server needed.**

---

## Install (Android)

> **This fork** does not ship through the upstream channels below (they belong to the original
> project). Install it by **building from source** — see
> [Build the embedded runtime from source](#build-the-embedded-runtime-from-source).
> The upstream build (without the embedded runtime) is still available as:

1. **Google Play (upstream)** — **https://play.google.com/store/apps/details?id=cc.agentlabs.opencode**

2. **F-Droid, self-hosted repo (upstream)** — add the upstream repo to any F-Droid client:
   ```
   https://dzianisv.github.io/opencode-mobile/fdroid/repo
   ```

3. **Direct signed APK (upstream)** — **https://github.com/dzianisv/opencode-mobile/releases/latest**

> iOS is not available (see [Roadmap](#roadmap)). IzzyOnDroid submission is pending (upstream).

---

OpenCode Mobile is a React Native / Expo app that brings the power of the [opencode](https://github.com/sst/opencode) AI coding agent to your phone. Connect to your own self-hosted opencode server over your local network, a Cloudflare Tunnel, ngrok, or Tailscale — and write, review, and ship code from anywhere. The mobile client is **free and open-source** under the MIT license. There is no feature gate, no telemetry you did not opt into, and no ad network.

---

<p align="center">
  <img src="distribution/demo.gif" width="240" alt="OpenCode Mobile demo — connect to your server, browse sessions, and watch the AI agent stream a reply" />
</p>

<sub>Real on-device capture: add a connection, browse sessions, and watch the agent stream a response. Verified end-to-end on an Android emulator against a live opencode server (build cc.agentlabs.opencode).</sub>

---

## Features

- **🆕 Embedded on-device runtime (this fork)** — start a real opencode server inside the app with one tap; no external machine required ([docs](docs/EMBEDDED-RUNTIME.md))
- **Offline demo mode** — tap "Try a Demo" to see a full bug-fix walkthrough (reasoning → grep → diff → permission prompt) with zero setup, right from the empty state
- **Multi-connection** — manage multiple opencode servers (local network, Cloudflare Tunnel, ngrok, or Tailscale)
- **Biometric unlock** — Face ID, Touch ID, or Android fingerprint protects the app and individual message sends
- **Streaming chat** — token-by-token streaming responses directly from your opencode server
- **Diff viewer** — inline side-by-side diffs of every file change the agent makes
- **Tool call approval** — review and approve (or reject) tool calls before the agent executes them
- **Secure credential storage** — server credentials stored in the Android Keystore via `expo-secure-store`
- **Session management** — browse, create, and resume coding sessions

---

## Get OpenCode Mobile

Package: `cc.agentlabs.opencode` · Android only · current version v0.4.7

| Channel | Status | How |
|---|---|---|
| **Google Play** | **Live** | [play.google.com/store/apps/details?id=cc.agentlabs.opencode](https://play.google.com/store/apps/details?id=cc.agentlabs.opencode) |
| **F-Droid (self-hosted repo)** | **Live** | Add [`https://dzianisv.github.io/opencode-mobile/fdroid/repo`](https://dzianisv.github.io/opencode-mobile/fdroid/repo) in your F-Droid client |
| **Direct APK** | **Live** | [github.com/dzianisv/opencode-mobile/releases/latest](https://github.com/dzianisv/opencode-mobile/releases/latest) |
| IzzyOnDroid | Submission pending | Not live yet |
| Apple App Store / iOS | Not available | See [Roadmap](#roadmap) |

> The three live, supported install channels are **Google Play**, the **F-Droid self-hosted repo**, and the **direct signed APK**, all Android. IzzyOnDroid is pending, and there is no iOS build.

---

## Quick Start

**Don't have a server yet?** Install the app and tap **Try a Demo** on the Sessions screen first — no setup required. It plays back a scripted bug-fix session through the app's real chat, diff, and permission-approval UI, offline, in about 30 seconds.

**Step 1 — Start opencode on your machine**

```bash
# Install opencode (if you haven't already)
npm install -g opencode-ai

# Run opencode in server mode
OPENCODE_SERVER_PASSWORD=yourpassword opencode serve --hostname 0.0.0.0 --port 4096
```

**Step 2 — Install OpenCode Mobile** via [Google Play, F-Droid, or a direct APK](#install-android) (or build from source — see [CONTRIBUTING.md](CONTRIBUTING.md)).

**Step 3 — Add a connection in the app**

Open the app, tap **Add Connection**, and choose your connection type:

- **Local network** — your machine's LAN IP, e.g. `http://192.168.1.100:4096`
- **Tunnel** — a Cloudflare Tunnel or ngrok URL, e.g. `https://my-opencode.trycloudflare.com`
- **Tailscale** — your machine's Tailscale IP, e.g. `http://100.x.x.x:4096`
- **opencode Cloud** *(planned — not yet shipped)* — one-tap managed hosting, no server to run

Enter the password you set in Step 1, tap **Connect**, and you're in.

---

## How It Works

OpenCode Mobile is a thin client. It speaks the opencode HTTP + SSE API: listing sessions, sending messages, streaming responses, and subscribing to file-change events. All AI model calls are handled by your opencode server — you bring your own API keys (OpenAI, Anthropic, etc.) and the app never touches them. The app never proxies your code or conversation through our servers.

```
┌─────────────────────────────────────┐
│         OpenCode Mobile             │
│  (React Native / Expo, this repo)   │
└──────────────┬──────────────────────┘
               │  HTTP + SSE
               │  (local network / tunnel)
               ▼
┌─────────────────────────────────────┐
│       opencode server               │
│  (github.com/sst/opencode, MIT)     │
│  Running on your laptop / VPS       │
└──────────────┬──────────────────────┘
               │  API calls
               ▼
┌─────────────────────────────────────┐
│   Your AI provider                  │
│  (OpenAI / Anthropic / Gemini / …)  │
│  Your keys, your bill               │
└─────────────────────────────────────┘
```

---

## Project Status

**Current version: v0.4.7**

| Feature | Status |
|---|---|
| **Embedded on-device runtime (this fork)** | Experimental — boots, serves, and connects on arm64 devices; cold-start cost & ~1-2GB RSS inherent |
| Offline demo mode | Stable |
| First-run onboarding clarity | Stable |
| Multi-connection management | Stable |
| Session list + creation | Stable |
| Streaming chat | Stable |
| Diff viewer | Stable |
| Biometric unlock | Stable |
| Tool call approval UI | Stable |
| Sentry crash reporting (opt-in) | Stable |
| Cloudflare / ngrok tunnel wizard | Beta |
| opencode Cloud one-tap connect | Planned |
| iPad / tablet layout | Planned |
| Offline session history | Planned |

---

## Supporters and Sponsors

OpenCode Mobile is built and maintained by [VIBE TECHNOLOGIES, LLC](https://agentlabs.cc/opencode). GitHub Sponsors help cover Sentry, EAS Build, and CI costs (~$60/month). The opencode Cloud hosted backend (planned, $10/mo) is the long-term revenue model.

If OpenCode Mobile saves you time, consider sponsoring:

**[github.com/sponsors/VibeTechnologies](https://github.com/sponsors/VibeTechnologies)**

| Tier | Price | Perk |
|---|---|---|
| Supporter | $5/mo | Your name in `SUPPORTERS.md` |
| Backer | $15/mo | Name + early access to opencode Cloud beta |
| Business | $50/mo | Logo on [agentlabs.cc/opencode](https://agentlabs.cc/opencode) + quarterly support call |

Questions or private support: [support@agentlabs.cc](mailto:support@agentlabs.cc)

---

## Roadmap

Tracked on the [GitHub Projects board](https://github.com/dzianisv/opencode-mobile/projects) and in the [open milestones](https://github.com/dzianisv/opencode-mobile/milestones).

Near-term priorities:
- opencode Cloud one-tap connect + managed hosting
- F-Droid mainline acceptance (FCM audit + reproducible build verification)
- Tunnel setup wizard (Cloudflare / ngrok / Tailscale)
- iPad / tablet layout
- Offline session history cache

---

## Contributing

We welcome bug reports, feature requests, and pull requests. See [CONTRIBUTING.md](CONTRIBUTING.md) for how to set up a dev environment and the contribution process.

---

## Privacy

OpenCode Mobile does not collect personal data. Optional Sentry crash reporting (opt-in, off by default) sends anonymised crash traces to Sentry. No analytics SDKs are bundled. Credentials are stored exclusively on-device in the OS keystore.

Full privacy policy: [dzianisv.github.io/opencode-mobile/privacy](https://dzianisv.github.io/opencode-mobile/privacy/)

---

## License

MIT — see [LICENSE](LICENSE).

Copyright (c) 2026 VIBE TECHNOLOGIES, LLC

---

## Acknowledgments

- **[dzianisv/opencode-mobile](https://github.com/dzianisv/opencode-mobile)** — the upstream this fork builds on (MIT); all credit for the app's client foundation goes to VIBE TECHNOLOGIES, LLC and its contributors
- **[Hope2333/opencode-termux](https://github.com/Hope2333/opencode-termux)** — the native single-ELF bionic build of opencode that makes the embedded runtime possible (MIT)
- [sst/opencode](https://github.com/sst/opencode) — the AI coding agent this app connects to (MIT)
- [Expo](https://expo.dev) — the React Native toolchain powering the app
- Every contributor who filed a bug, opened a PR, or starred the repo
