# Cortex: Mobile-First AI Study Companion for Samsung Galaxy

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Target Platform](https://img.shields.io/badge/Platform-Samsung%20Galaxy%20Store-blue)](https://galaxystore.samsung.com/)
[![AI Engine](https://img.shields.io/badge/AI-Gemini%20Flash%20%2F%20Vertex%20AI-purple)](https://cloud.google.com/vertex-ai)
[![Monetization](https://img.shields.io/badge/Monetization-RevenueCat%20Galaxy%20SDK-orange)](https://www.revenuecat.com/)

**Cortex** is an intelligent, interaction-first study companion designed natively for Samsung Galaxy foldables, tablets, and phones. Built for students tackling complex, information-heavy courses, Cortex transforms dense lecture slides, textbook PDFs, and syllabi into an active, grounded learning experience.

Rather than forcing desktop-style multi-pane document readers onto a phone, Cortex prioritizes natural mobile interaction: a focused conversational stream with non-obtrusive citations, on-demand learning artifacts (interactive quizzes with instant rationale, 2-host audio briefings, exam cheat sheets), S-Pen formula sketching, and hardware-level **tabletop Flex Mode** ergonomics.

---

## 🌟 Key Capabilities

1. **Course & Project Siloing:** Organize by class (*Biology 101*, *Linear Algebra*, *Macroeconomics*). Each project holds its own persistent sources, messages, quizzes, audio briefings, and cheat sheets, grounding all interactions strictly in that course's materials.
2. **Subtle, Interactive Citations:** Citations appear as compact badges (`p.4 • Lecture_04`) once per response without interrupting reading flow. Tap any badge to open the **Source Citation Inspector** with the exact quoted passage and source context.
3. **LaTeX Math & Science Rendering:** Complex mathematical formulas and chemical equations render in crisp notation (inline `$..$` and display block `$$..$$`) with currency-safe tokenization (`$10 to $25` never collides with LaTeX parsing).
4. **Active Recall Quizzing:** One tap generates multiple-choice quizzes grounded in your active course sources with immediate visual feedback, detailed explanations, and source citations.
5. **2-Host Multi-Voice Audio Briefings:** Generates conversational mini-podcasts between two AI study buddies ("Alex" & "Sam") with `UtteranceProgressListener` automatic turn progression, distinct voice pitch/rate modulation (`0.92x` vs `1.15x`), interactive transcript cards, and animated waveform playback.
6. **High-Yield Exam Cheat Sheets & S-Pen Formula Studio:** Generate condensed Markdown + LaTeX cheat sheets or sketch formulas on the S-Pen canvas to recognize and export LaTeX equations.
7. **Tabletop Flex Mode (Galaxy Z Fold & Z Flip):** Automatically detects `FoldingFeature.State.HALF_OPENED` via `androidx.window` (or toggle manually via the TopAppBar foldable icon). The top screen angles toward your eyes to display grounded answers and formulas, while the bottom screen becomes an ergonomic 2×2 tabletop control deck.

---

## 🛠️ Architecture & Tech Stack

* **UI & Presentation:** Kotlin 2.0 + Jetpack Compose with **Material 3** design tokens and `CortexViewModel` state retention.
* **Foldable APIs:** `androidx.window` with `WindowInfoTracker` collected safely via `repeatOnLifecycle(Lifecycle.State.STARTED)`.
* **AI Engine:** Google Gemini (`gemini-3.8-flash` with in-app Low Reasoning and Extended Reasoning modes) / custom Vertex AI endpoint with an automatic offline TF-IDF source-grounded RAG engine when running without an API key.
* **Document Ingestion:** `DocumentTextExtractor` on `Dispatchers.IO` with native `PdfRenderer` page counting and `FlateDecode` / `BT..ET` PDF text stream extraction.
* **Monetization:** RevenueCat Samsung Galaxy Store SDK (`com.revenuecat.purchases:purchases-store-galaxy:8.9.0`) powering Cortex Pro with persistent entitlement state.
* **Retention:** OneSignal Android SDK (`com.onesignal:OneSignal:5.1.25`) scheduling contextual spaced-repetition study reminders.

---

## 🚀 Building, Configuring & Running

### Requirements
* Java Development Kit (JDK 17 or higher)
* Android SDK (API Level 34)

### Optional: Configure Gemini API Key
You can provide a Gemini API key either in `local.properties`:
```properties
GEMINI_API_KEY=your_api_key_here
VERTEX_PROJECT_ENDPOINT=
```
Or configure it at runtime using the **AI Engine Settings (Tune icon)** in the top bar of the app. When left blank, Cortex automatically uses its built-in offline source-grounded RAG engine.

### Build Debug APK
```bash
./gradlew assembleDebug
```

### Run Unit Tests
```bash
./gradlew testDebugUnitTest
```

### Build Release Bundle (for Samsung Galaxy Store)
```bash
./gradlew bundleRelease
```

---

## 📄 License
This project is open-source under the [MIT License](LICENSE).
