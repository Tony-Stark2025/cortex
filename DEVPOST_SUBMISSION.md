# Devpost Hackathon Submission: Cortex (RevenueCat Shipaton 2026)

> **Submission URL**: [RevenueCat Shipaton 2026 on Devpost](https://revenuecat-shipaton-2026.devpost.com/)  
> **App Title**: Cortex — AI Study Companion for Samsung Galaxy  
> **Tagline**: Foldable-native AI study workspace with citation-grounded RAG, 2-host audio briefings, S-Pen formula studio, and RevenueCat Galaxy Store subscriptions.  
> **Target Store**: Samsung Galaxy Store (`https://galaxystore.samsung.com/detail/com.cortex.app`)  
> **Judge / Reviewer Access Code**: `SHIPATON2026` (Redeemable right inside the Paywall screen to immediately unlock all Cortex Pro Scholar features)

---

## 1. Project Overview & Inspiration

### Inspiration: The Fundamental Flaws of Chatbots for Studying
Almost every student has tried using generic AI chatbots (like ChatGPT or standard mobile assistants) to study for exams, and almost every student hits the exact same brick walls:

1. **The Hallucination Trap**: Generic chatbots produce convincing, authoritative answers—with completely invented page numbers and subtle factual errors. In university STEM exams, a slight sign error or invented enzyme pathway results in failure. Students cannot trust AI without **instant, verifiable citations directly to their professor's slides**.
2. **Passive Text Fatigue vs. Cognitive Science**: Chatbots dump overwhelming walls of prose. Psychological research on active learning proves students don't retain material by passively reading paragraphs; they retain information through **auditory discussion, spaced repetition, and active recall testing**.
3. **The Mobile STEM Barrier**: It is nearly impossible to type complex calculus integrals, organic chemistry mechanisms, or matrices into a mobile keyboard text box. Real studying happens with a pen on paper or a stylus on glass.
4. **Ergonomic Mismatch**: Standard chatbots force students to hunch over a cramped vertical phone screen. Yet students own incredible foldable hardware—like the **Samsung Galaxy Z Fold** and **Galaxy Tab with S-Pen**—that can sit propped up like a dedicated physical desk workstation.

We designed **Cortex** from the ground up as the antidote to generic chatbots: a hardware-first, citation-grounded study companion built exclusively for the ergonomics of Samsung Galaxy foldables and tablets.

---

## 2. What It Does

- **Multi-Course Notebook Silos**: Keep Biology 101, Linear Algebra, and Organic Chemistry completely separated. Ingest multi-page lecture PDFs, slide decks, and notes with zero leakage between classes.
- **Citation-Grounded AI**: Every explanation, theorem, and summary quotes the exact page and source document (`[Lecture_04_Glycolysis.pdf • Page 18]`). Tap any citation chip to inspect the underlying source snippet.
- **Active Recall Exam Quizzes**: Automatically converts raw lecture slides into 4-choice practice exams with domain-aware distractors, detailed rationales, and instant score tracking.
- **2-Host Audio Briefing**: "Alex & Sam" banter through complex concepts, highlighting counter-intuitive edge cases and exam traps.
- **Spaced Repetition Push Reminders**: Integrates OneSignal to calculate SM-2 retention intervals and remind students right before their memory curve drops.
- **Seamless Monetization (RevenueCat)**: Free tier supports 2 active course notebooks. "Cortex Pro Scholar" ($4.99/mo with a 7-day free trial) unlocks unlimited notebooks, unlimited audio briefings, and high-yield cheat sheets.

---

## 3. How We Built It

- **Language & UI**: 100% Kotlin 2.0 with Jetpack Compose & Material 3.
- **Foldable Ergonomics**: `androidx.window:window` and `WindowInfoTracker` dynamically detect folding postures (`TABLETOP_HORIZONTAL`, `BOOK_VERTICAL`, and `NORMAL`) to reconfigure the layout in real time.
- **Monetization & In-App Purchases**: Integrated RevenueCat's official Samsung Galaxy Store SDK (`purchases-store-galaxy:8.9.0`). The `RevenueCatManager` manages the `pro_scholar` entitlement, offerings, customer info, restore purchases, and reviewer promo code validation (`SHIPATON2026`).
- **Grounded Intelligence**: Dual-mode engine:
  - **Online**: Google Gemini 3.8 Flash via Vertex AI for live conversational reasoning, with in-app toggles for **Low Reasoning (Fast)** and **Extended Reasoning (Deep)**.
  - **Offline/Edge**: Built-in deterministic TF-IDF term-frequency RAG engine that guarantees instant, private, offline answers even on airplane mode or spotty campus Wi-Fi.
- **Persistence**: Thread-safe `AtomicFile` JSON storage ensuring zero data loss during phone restarts or battery drain.

---

## 4. RevenueCat Implementation Proof

Cortex connects directly to RevenueCat with the Samsung Galaxy Store billing backend:
```kotlin
// RevenueCat Configuration
val config = PurchasesConfiguration.Builder(context.applicationContext, GALAXY_API_KEY)
    .build()
Purchases.configure(config)

// Checking Entitlement State
val isPro = customerInfo.entitlements["pro_scholar"]?.isActive == true

// In-App Purchase via Galaxy Store
Purchases.sharedInstance.purchaseWith(
    PurchaseParams.Builder(activity, pkg).build(),
    onError = { error, userCancelled -> /* handle */ },
    onSuccess = { storeTransaction, customerInfo ->
        updateProStatus(customerInfo)
    }
)

// Restore Purchases
Purchases.sharedInstance.restorePurchasesWith(
    onError = { error -> /* handle */ },
    onSuccess = { customerInfo -> updateProStatus(customerInfo) }
)
```

### Devpost Judge Testing Instructions (Zero-Friction Evaluation)
1. **No API Key Required**: Launch Cortex on any Android device or emulator. Cortex comes with an on-device grounded RAG engine ready out of the box—no Google Cloud account, billing, or API keys are required to test quizzes, audio briefings, citations, or S-Pen math.
2. **Gemini 3.8 Flash Reasoning Modes**: Tap the top-bar AI settings icon to toggle between **⚡ Low Reasoning (Fast)** and **🧠 Extended Reasoning (Deep)**. An optional Gemini API key field is available if you wish to test live cloud streaming.
3. **RevenueCat Pro Unlock**: Tap the **"PRO"** badge in the top bar to open the Paywall screen.
4. Tap **"Devpost Judge / Promo Code (Redeem)"**, enter `SHIPATON2026`, and tap **Redeem**.
5. The `pro_scholar` entitlement unlocks immediately via RevenueCat, confirming full Pro access without requiring a physical Samsung test billing card!

---

## 5. 2-Minute Demo Video Script & Storyboard

| Time | Visual / Screen Action | Voiceover / Audio |
| :--- | :--- | :--- |
| **0:00 - 0:20** | Galaxy Z Fold on a desk folding to 90°. App opens to **Biology 101**. Top screen shows lecture citations; bottom screen shows 2x2 control deck. | *"Meet Cortex, the first mobile-native AI study companion built specifically for Samsung Galaxy foldables and tablets."* |
| **0:20 - 0:45** | User taps **"🎧 Audio Briefing"**. Two podcast host cards ("Alex & Sam") appear and start speaking through cellular respiration and ATP synthase. | *"Instead of dumping a wall of text like a generic chatbot, Cortex generates an interactive 2-host audio briefing between Alex & Sam, turning dry lecture slides into an engaging discussion you can listen to on your walk to class."* |
| **0:45 - 1:10** | User pulls out S-Pen, sketches $\int x e^x dx$ on the bottom screen. The stroke classifier converts it into clean LaTeX math and solves it with citations. | *"Got a calculus or physics problem? Use your S-Pen. Cortex classifies geometric stroke vectors and generates instant LaTeX formulas grounded in your textbook."* |
| **1:10 - 1:35** | User taps the **PRO** badge. The sleek Material 3 Paywall appears showing the 7-day trial and $4.99/mo plan powered by RevenueCat Galaxy Store SDK. | *"To monetize our student user base, we integrated RevenueCat's Samsung Galaxy Store SDK. Students get a 7-day free trial or $4.99 a month for unlimited courses and podcasts."* |
| **1:35 - 1:55** | User taps "Devpost Judge / Promo Code", enters `SHIPATON2026`, and the screen unlocks with a sparkling Pro Scholar badge. User takes a rapid active recall quiz. | *"For our Devpost judges, entering 'SHIPATON2026' unlocks Pro immediately. Seamless subscriptions, hardware-first ergonomics, and zero hallucination study companion. That's Cortex."* |
| **1:55 - 2:00** | End screen showing Cortex logo, RevenueCat logo, Samsung Galaxy Store badge, and GitHub repository link. | *"Cortex: Study smarter on Samsung Galaxy. Powered by RevenueCat."* |

---

## 6. What's Next for Cortex

1. **Samsung DeX Desktop Mode**: Transforming Galaxy tablets into dual-monitor study workstations with keyboard shortcuts.
2. **"Exam Cram Pass" Microtransactions**: Introducing 72-hour unlimited access passes ($1.99 consumable IAP via RevenueCat) for students who only need intensive study help during finals week.
3. **Cross-Device Sync**: Adding lightweight cloud backup between Galaxy Phones and Galaxy Tab S9 Ultra.
