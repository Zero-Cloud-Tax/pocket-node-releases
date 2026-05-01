# Pocket Node for Android

![License](https://img.shields.io/badge/License-MIT-blue.svg)
![Release](https://img.shields.io/github/v/release/yourusername/Pocket-Node?label=Latest%20Release)
![Kotlin](https://img.shields.io/badge/Kotlin-Multiplatform-purple.svg)
![Min SDK](https://img.shields.io/badge/Min%20SDK-Android%208.0+-green.svg)
![Offline AI](https://img.shields.io/badge/AI-100%25%20Offline-orange.svg)

A fully offline, privacy-first AI chat app for Android. Runs large language models **entirely on-device** using [llama.cpp](https://github.com/ggerganov/llama.cpp) — no data ever leaves your phone.

<div align="center">
  <!-- TODO: Add your actual screenshots to a docs/screenshots folder -->
  <img src="docs/screenshots/home.png" width="18%" alt="Home screen" />
  <img src="docs/screenshots/import.png" width="18%" alt="Model import screen" />
  <img src="docs/screenshots/chat.png" width="18%" alt="Chat streaming" />
  <img src="docs/screenshots/rag.png" width="18%" alt="RAG document attachment" />
  <img src="docs/screenshots/vision.png" width="18%" alt="Vision mode" />
</div>

## 📦 Download

- **Pocket Node Lite (Free)** — [Download APK](https://github.com/yourusername/Pocket-Node/releases/latest/download/PocketNodeLite.apk)
- **Pocket Node Pro ($19.99)** — [Get Pro](#) (Coming Soon)

---

## 🧠 Why Pocket Node?

Pocket Node turns your phone into a private AI computer:
- **No cloud**
- **No accounts**
- **No tracking**
- **No subscriptions**
- **No data leaving your device**

---

## ⚖️ Pro vs Lite

| Feature | Lite (Free) | Pro ($19.99 one‑time) |
|--------|-------------|-----------------------|
| Local inference | ✔ | ✔ |
| RAG (PDF/Text) | ✔ | ✔ |
| Vision (LLaVA) | ✔ | ✔ |
| Background API mode | — | ✔ |
| Unlimited chat history | — | ✔ |
| Model auto‑downloader | — | ✔ |

---

## ✨ Features

- **100% Offline** — No internet permission required for chatting.
- **GGUF Model Support** — Import any GGUF-format model via the file picker or direct download.
- **Document Indexing (RAG)** — Attach PDFs and text files directly in chat to ask questions about them.
- **Multi-Modal Vision** — Attach images and use LLaVA models to describe or analyze visual content natively.
- **Streaming Responses** — Token-by-token output as the model generates.
- **Multiple Chat Templates** — ChatML, Llama 3, Alpaca, and plain text.
- **Configurable Parameters** — Temperature, top-p, max tokens, context size, thread count.

---

## 📊 Benchmarks

*Real numbers running locally on mobile hardware.*

### Pixel 8 Pro (Tensor G3)
- Phi‑3.5‑mini Q4_K_M → **18 tok/s**
- Llama‑3.2‑3B Q4_K_M → **12 tok/s**

### Galaxy S24 Ultra (Snapdragon 8 Gen 3)
- Phi‑3.5‑mini Q4_K_M → **28 tok/s**
- Mistral‑7B Q4_K_M → **9 tok/s**

---

## 🤖 Model Compatibility

| Model | Works? | Notes |
|-------|--------|--------|
| Phi‑3.5‑mini | ✔ | Fastest, great reasoning |
| Llama‑3.2‑3B | ✔ | Best instruction following |
| Mistral‑7B | ✔ | High quality, requires 6GB+ RAM |
| LLaVA 1.5 7B | ✔ | Requires vision projector file |
| Qwen2.5‑7B | ⚠ | Slow on mid‑range phones |

---

## 🛠️ Tech Stack

- **UI:** Kotlin + Jetpack Compose + Material 3
- **Local Storage:** Room DB + DataStore
- **Inference:** JNI + C++ + llama.cpp
- **Acceleration:** Vulkan compute

---

## 🔒 Security Notes

Privacy is our core feature:
- **No Internet Permission for Core App:** (Check the `AndroidManifest.xml`) Internet is only used temporarily for the auto-downloader.
- **Network Security Config:** Blocks untrusted traffic.
- **Local-only Room DB:** Chat history never syncs anywhere.
- **Zero Analytics:** No crashlytics, no trackers.

---

## 🚀 Roadmap

- [ ] Pro Tier (API mode, background service)
- [ ] Model auto-downloader (background foreground service)
- [ ] On-device quantization
- [ ] Chat export / import
- [ ] Model metadata viewer

---

## 🔧 Troubleshooting

- **"Model fails to load"**: Ensure you have enough RAM. Close other apps. Try a smaller Q4_K_M model.
- **"App crashes on startup"**: Make sure you granted necessary storage permissions for Android 13+.
- **"Vulkan not supported"**: Some older devices may fallback to CPU. Performance will be severely impacted.
- **"Out of memory loading 7B models"**: You need at least 8GB of physical RAM on your phone to comfortably run a 7B parameter model.
- **"NDK not found"**: If building from source, ensure NDK r26+ is installed via Android Studio SDK Manager.

---

## 🏗️ Building from Source

### Requirements
- **Android Studio** Ladybug (2024.2+) or newer
- **Android NDK** r26+ and **CMake** 3.22.1+
- **Min SDK**: Android 8.0 (API 26)

### Steps
1. Clone the repository: `git clone <repo-url> "Pocket Node" && cd "Pocket Node"`
2. Open in Android Studio.
3. Go to **Settings → Languages & Frameworks → Android SDK → SDK Tools** and ensure NDK & CMake are installed.
4. Build the project. Gradle will fetch llama.cpp via CMake and compile it for `arm64-v8a`. *(First build takes ~5 mins)*.

---

## 🤝 Contributing

Pull requests are welcome! For major changes, please open an issue first to discuss what you’d like to change. 

## 📜 License

MIT — see individual dependencies for their licenses.
llama.cpp is licensed under MIT.
