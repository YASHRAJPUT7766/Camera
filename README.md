# Camera Security Lab

A consent-based Android camera security testing laboratory for **your own test device**.

Built with Kotlin, Jetpack Compose (Material 3), CameraX, Firebase (Firestore), and Cloudinary,
targeting Android 13 (API 33).

> **Safety boundary — read first**
> This app deliberately contains **no** stealth surveillance, hidden camera access, permission
> bypass, invisible recording, background camera capture, lock-screen camera access, or any
> mechanism designed to hide camera usage from the device's own user. Every capability here is
> visible, user-initiated, and reported — never hidden. See "Restrictions" near the bottom of this
> file for the full list of things this project intentionally does not do.

---

## 1. Two ways to connect a dashboard

| | Local Wi-Fi (LAN) | Internet (Firebase + Cloudinary) |
|---|---|---|
| Dashboard location | Served by the phone itself | Hosted anywhere (e.g. Netlify) |
| Network required | Phone AND browser on same Wi-Fi | **Any** internet connection, mobile data included |
| How it connects | Phone's local IP + port | A short pairing code you type into the dashboard |
| "Live" video | Real MJPEG stream (~8 fps) | Periodic snapshots (~every 1.5s) via Cloudinary |
| Setup needed | None | A free Firebase project + a free Cloudinary account |

The **Internet mode is what lets you test from mobile data** — the phone doesn't need to be
reachable directly; Firebase relays commands/state, and Cloudinary hosts the actual photos/videos.

### Why Firebase AND Cloudinary?
Firebase's Firestore (a small, fast database) is free and handles pairing codes, commands, and
status — but Firebase **Storage** (for hosting photos/videos) requires the paid "Blaze" plan even
for tiny projects. Cloudinary has a genuinely free tier built specifically for hosting images and
video, so this project uses Firestore for everything except the actual media files, and Cloudinary
for those.

## 2. What's in this repo

```
CameraSecLab/
├── app/                                    # Android app (Kotlin, Jetpack Compose)
│   ├── src/main/
│   │   ├── java/com/testlab/camerasec/
│   │   │   ├── MainActivity.kt             # Nav shell + lifecycle observer/reporter
│   │   │   ├── MainViewModel.kt            # Wires camera + LAN streaming + Firebase together
│   │   │   ├── camera/CameraController.kt  # CameraX wrapper (start/stop/switch/capture)
│   │   │   ├── firebase/FirebaseRelay.kt   # Firestore pairing + command bridge
│   │   │   ├── cloudinary/CloudinaryUploader.kt # Uploads captured photos/videos to Cloudinary
│   │   │   ├── network/LocalStreamServer.kt# LAN-only MJPEG/HTTP server
│   │   │   ├── log/SecurityTestLog.kt      # In-memory security test event log
│   │   │   ├── util/NetworkUtils.kt        # LAN IP resolution
│   │   │   └── ui/                         # Compose screens + theme
│   │   ├── assets/dashboard/               # LAN-mode browser dashboard (served by the phone)
│   │   ├── res/                            # Strings, themes, launcher icon
│   │   └── AndroidManifest.xml
│   ├── google-services.json                # YOU add this — see Firebase Setup below
│   ├── build.gradle.kts
│   └── proguard-rules.pro
├── netlify-dashboard/                      # Internet-mode dashboard — deploy this to Netlify
│   ├── index.html
│   ├── dashboard.css
│   ├── dashboard.js
│   ├── firebase-config.js                  # Your Firebase Web config goes here
│   └── netlify.toml
├── firestore.rules                         # Firestore Security Rules (standalone reference)
├── storage.rules                           # NOTE: Storage isn't used — see file contents
├── firebase-rules-snippets/                # Merge instructions if you share a Firebase project
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── gradle/wrapper/gradle-wrapper.properties
└── .github/workflows/build-apk.yml         # CI: builds a debug APK on push
```

## 3. Firebase setup (Firestore only — required for Internet mode)

1. Go to **https://console.firebase.google.com** → **Add project** (or use an existing one) →
   follow the prompts (Google Analytics is optional).
2. Enable **Firestore Database** from the left sidebar → Create database → **production mode** →
   pick any region. (Do **not** enable Storage — it needs the paid plan and this project doesn't
   use it.)
3. **Add the Android app:** Project settings (gear icon) → **Your apps** → Add app → Android.
   - Package name: `com.testlab.camerasec` (must match exactly)
   - Download the generated `google-services.json`
   - Place it at `app/google-services.json` in this repo, replacing
     `app/GOOGLE_SERVICES_JSON_PLACEHOLDER.txt`
4. **Add a Web app** (same project): Project settings → Your apps → Add app → Web (`</>` icon).
   - Copy the `firebaseConfig` values it shows you into `netlify-dashboard/firebase-config.js`
5. **Deploy Firestore rules:** Firestore Database → **Rules** tab → paste the contents of
   `firestore.rules` from this repo → **Publish**.
   - **If this Firebase project is already used by other apps of yours** (sharing Firestore),
     do NOT overwrite your existing rules wholesale — see `firebase-rules-snippets/` for a
     merge-only version that only adds the `/pairings/...` path and leaves everything else as-is.

No billing/credit card is required — Firestore at this usage level comfortably fits the free
"Spark" tier.

## 4. Cloudinary setup (for photo/video hosting)

**Already configured for this build** — `CloudinaryUploader.kt` is set to cloud name `dljzyticd`
and unsigned preset `YASHRAJPUT`. Skip this section unless you want to point the app at a
different Cloudinary account.

To use a different account:
1. Go to **https://cloudinary.com** and sign in (or create a free one).
2. Note your **Cloud name**, shown at the top of the Cloudinary console dashboard.
3. Go to **Settings (gear icon) → Upload** → scroll to **Upload presets** → **Add upload preset**.
4. Set **Signing Mode** to **Unsigned** (required — the app uploads directly from the phone with
   no server in between, so it can never hold a signed/secret API key).
5. Save, and note the preset's **name**.
6. Open `app/src/main/java/com/testlab/camerasec/cloudinary/CloudinaryUploader.kt` and set:
   ```kotlin
   private const val CLOUD_NAME = "your-cloud-name"
   private const val UPLOAD_PRESET = "your-unsigned-preset-name"
   ```

No Cloudinary API secret ever goes in the app. Free-tier Cloudinary limits (25 credits/month,
which covers thousands of small test images) are more than enough for personal testing.

## 5. Deploying the dashboard to Netlify

1. Push this repo to GitHub — or just drag-and-drop the `netlify-dashboard/` folder directly onto
   Netlify's "Deploy manually" upload area (no GitHub needed for this part).
2. If using GitHub: **Add new site → Import an existing project**, set **Base/Publish directory**
   to `netlify-dashboard`, leave the build command blank (it's static HTML/CSS/JS).
3. Deploy. Netlify gives you a URL like `https://your-site-name.netlify.app`.

Make sure `netlify-dashboard/firebase-config.js` has your real Firebase Web config **before**
deploying (or redeploy after editing it).

## 6. Building the Android app via GitHub Actions (no local Android Studio needed)

1. Push this whole folder to a new GitHub repository, making sure `app/google-services.json`
   (your real one, from section 3) and your edited `CloudinaryUploader.kt` (from section 4) are
   committed.
2. GitHub Actions runs `.github/workflows/build-apk.yml` automatically on push, or trigger it
   manually from the **Actions** tab → "Build Debug APK" → **Run workflow**.
3. It verifies `google-services.json` is present, generates the Gradle wrapper, builds
   `assembleDebug`, and uploads `app-debug.apk` as a build artifact.
4. Download the artifact, transfer the APK to your Android 13 device, and install it (allow
   "Install unknown apps" for whichever app you use to open it).

## 7. First run on your device

1. Install and open the app.
2. **Permission tab** — tap "Request Camera Permission" and grant it.
3. **Preview tab** — tap "Start Camera". You should see a live preview and a pulsing
   **CAMERA ACTIVE** badge, plus Android's own camera privacy indicator.
4. **Streaming tab** → turn on **Allow Remote Capture**.
5. **For Internet/mobile-data testing:** under "Option A — Internet", tap **Start Session**. A
   pairing code appears (e.g. `7F3K-9QZP`).
6. On your Netlify-hosted dashboard (any device, any network — Wi-Fi or mobile data both work),
   enter that code and tap **Connect**.
7. Try the buttons: **Switch Front/Back**, **Start Live**, **Capture Photo**, **Capture 5s Video**.
   Watch the on-device banner and the Log tab update in real time as each command arrives.
8. **For same-Wi-Fi/no-internet testing:** use "Option B — Local Wi-Fi" instead.

## 8. How "Live" works (and its limits)

Roughly every 1.5 seconds, the phone captures the current camera frame, uploads it to Cloudinary,
writes the resulting URL into the Firestore pairing document, and the dashboard swaps the image in
place when it sees that URL change. It looks like a slow-motion "live" feed (~0.6 fps), not a
smooth video call — that trade-off is what makes "watch from any network, anywhere" possible
without a WebRTC/TURN server.

## 9. How to test each Android 13 security restriction

The **Security Test** tab in the app has these built in as an on-device checklist.

- **Permission enforcement:** deny Camera, try Start Camera → `SecurityException` → reported as
  `"Camera permission unavailable."`
- **Backgrounding:** start camera, press Home → lifecycle `onStop()` stops everything and logs
  `"Camera stopped when app entered this state."`
- **Screen-off:** same as backgrounding — no wake lock anywhere in this codebase.
- **Mid-session permission revoke:** revoke from Settings while open → next capture attempt fails
  the same way.
- **Force-stop:** no `<service>`, no boot receiver — everything (camera, LAN server, Firebase
  live-upload loop) stops because none of it runs outside this app's own process.
- **Privacy indicator:** Android's camera green-dot indicator is never suppressed or delayed.

## 10. Remote Capture Test (network data transfer) — both modes

Both dashboards can trigger **Switch Camera**, **Capture Photo**, **Capture 5s Video**, and
(Firebase mode only) **Start/Stop Live** — but only when **Allow Remote Capture** is ON, on-device.

**Why this doesn't become a stealth feature:**
- Off by default, must be turned on physically on-device.
- Every remote action triggers a visible on-device banner the moment the request arrives.
- Capture functions require the camera to already be visibly running in the foreground.
- Video clips are capped at 5 seconds with no audio track (no `RECORD_AUDIO` permission needed).
- LAN-mode files are read from cache into memory, sent over HTTP, then deleted. Firebase-mode
  captures go to your own Cloudinary account under a pairing-scoped folder and can be deleted from
  the Cloudinary console whenever you like.
- Every command, allowed or refused, is written to the on-device Log tab.

## 11. Restrictions this project intentionally does NOT implement

- Hidden or stealth camera operation
- Screen-off or lock-screen camera capture
- Any permission bypass or reflection-based permission trick
- Root or exploit-based techniques
- Accessibility-service abuse
- Notification hiding or suppression
- Persistence after uninstall (no boot receiver, no device admin, no background service)
- Remote camera control beyond the explicitly-gated, always-logged, always-on-device-visible
  capture test described in section 10 — always requires the device owner's on-device toggle
- Public internet exposure of the LAN dashboard/stream (LAN-bound only); Internet mode is scoped
  per-pairing-code via Firestore Security Rules, not a public open endpoint
- Silent photo/video capture — every capture is logged and surfaced as an on-device banner

## 12. Credits

Developed as a consent-based security testing lab (developer credit: GPT-5.6 Luna).
# Camera
