# Private LLM for Android

A fully offline, privacy-first AI chat app for Android. Runs large language models **entirely on-device** using [llama.cpp](https://github.com/ggerganov/llama.cpp) — no data ever leaves your phone.

## Features

- **100% Offline** — No internet permission, no telemetry, no cloud. Period.
- **GGUF Model Support** — Import any GGUF-format model via the file picker
- **Streaming Responses** — Token-by-token output as the model generates
- **Conversation History** — Stored locally with Room database
- **Multiple Chat Templates** — ChatML, Llama 3, Alpaca, and plain text
- **Configurable Parameters** — Temperature, top-p, max tokens, context size, thread count
- **Material 3 UI** — Modern Android design with dynamic color support

## Architecture

```
┌─────────────────────────────────────────────┐
│              Jetpack Compose UI             │
│  ┌──────────┐ ┌──────────┐ ┌─────────────┐ │
│  │   Chat   │ │  Models  │ │  Settings   │ │
│  └────┬─────┘ └────┬─────┘ └──────┬──────┘ │
│       │             │              │        │
│  ┌────▼─────────────▼──────────────▼──────┐ │
│  │           ViewModels (MVVM)            │ │
│  └────┬─────────────┬──────────────┬──────┘ │
│       │             │              │        │
│  ┌────▼────┐  ┌─────▼─────┐ ┌─────▼──────┐ │
│  │  Room   │  │  Model    │ │ DataStore  │ │
│  │   DB    │  │  Manager  │ │   Prefs    │ │
│  └─────────┘  └───────────┘ └────────────┘ │
│                                             │
│  ┌─────────────────────────────────────────┐│
│  │     LlamaInference (Kotlin/JNI)        ││
│  │  ┌──────────────────────────────────┐   ││
│  │  │  privatellm_jni.cpp (C++ NDK)   │   ││
│  │  │  ┌────────────────────────────┐  │   ││
│  │  │  │      llama.cpp library     │  │   ││
│  │  │  └────────────────────────────┘  │   ││
│  │  └──────────────────────────────────┘   ││
│  └─────────────────────────────────────────┘│
└─────────────────────────────────────────────┘
```

## Requirements

- **Android Studio** Ladybug (2024.2+) or newer
- **Android NDK** r26+ (install via SDK Manager → SDK Tools → NDK)
- **CMake** 3.22.1+ (install via SDK Manager → SDK Tools → CMake)
- **Min SDK**: Android 8.0 (API 26)
- **Device**: ARM64 device with at least 4GB RAM (8GB+ recommended)

## Getting Started

### 1. Clone and Open

```bash
cd ~/PrivateLLM
```

Open this folder in Android Studio.

### 2. Install NDK & CMake

In Android Studio:
- Go to **Settings → Languages & Frameworks → Android SDK → SDK Tools**
- Check **NDK (Side by side)** and **CMake**
- Click Apply

### 3. Build

Build the project — Gradle will automatically:
1. Fetch llama.cpp source code via CMake FetchContent
2. Cross-compile it for `arm64-v8a` and `x86_64`
3. Build the JNI bridge
4. Package everything into the APK

> **Note:** First build takes longer as it downloads and compiles llama.cpp (~5 min).

### 4. Get a Model

You need a GGUF-format model file. Recommended small models for phones:

| Model | Size | RAM Needed | Quality |
|-------|------|------------|---------|
| Qwen2.5-1.5B-Instruct Q4_K_M | ~1 GB | ~2 GB | Good for simple tasks |
| Phi-3.5-mini Q4_K_M | ~2.2 GB | ~4 GB | Great general purpose |
| Llama-3.2-3B-Instruct Q4_K_M | ~2 GB | ~4 GB | Strong instruction following |
| Mistral-7B-Instruct Q4_K_M | ~4.4 GB | ~6 GB | High quality |

Download from [HuggingFace](https://huggingface.co/models?sort=trending&search=gguf) and transfer to your phone.

### 5. Import and Chat

1. Open the app
2. Go to **Models** tab
3. Tap **Import Model** and select your `.gguf` file
4. Tap the download icon to load the model
5. Switch to **Chat** tab and start talking!

## Project Structure

```
app/src/main/
├── java/com/privatellm/app/
│   ├── MainActivity.kt           # Entry point + navigation
│   ├── MainApplication.kt        # Application class
│   ├── inference/
│   │   ├── LlamaInference.kt     # JNI bridge to llama.cpp
│   │   └── PromptFormatter.kt    # Chat template formatting
│   ├── data/
│   │   ├── db/                    # Room database
│   │   │   ├── AppDatabase.kt
│   │   │   ├── dao/               # Data access objects
│   │   │   └── entity/            # Conversation, ChatMessage
│   │   ├── model/
│   │   │   └── ModelManager.kt    # GGUF file management
│   │   └── preferences/
│   │       └── AppPreferences.kt  # DataStore settings
│   ├── viewmodel/                 # MVVM view models
│   │   ├── ChatViewModel.kt
│   │   ├── ModelViewModel.kt
│   │   └── SettingsViewModel.kt
│   └── ui/
│       ├── theme/                 # Material 3 theme
│       ├── components/            # Reusable composables
│       ├── screens/               # Chat, Models, Settings
│       └── navigation/            # Bottom nav routing
└── cpp/
    ├── CMakeLists.txt             # Builds llama.cpp + JNI bridge
    └── privatellm_jni.cpp         # C++ JNI implementation
```

## Performance Tips

- Use **Q4_K_M** quantized models for the best speed/quality balance on phones
- Set thread count to match your device's efficiency cores (usually 4)
- Lower context size (1024-2048) for faster responses
- Close other apps to free RAM before loading large models

## Privacy Guarantee

This app contains:
- **Zero** internet permissions
- **Zero** analytics or tracking
- **Zero** cloud dependencies
- Network security config that blocks all traffic

Your conversations and models stay on your device. Always.

## License

MIT — see individual dependencies for their licenses.
llama.cpp is licensed under MIT.
