# Merging Camera Lab rules into your existing yash-software rules

Since `yash-software` already has other apps (Lurpix, EchoLock, etc.) using
Firestore/Storage, do **NOT** replace your existing rules with the standalone
`firestore.rules` / `storage.rules` files at the repo root — that would wipe
out whatever rules those other apps depend on.

Instead, **add one small block** to your existing rules. The Camera Lab rules
only ever touch paths starting with `/pairings/...`, so they can't collide
with another app's collections as long as none of your other apps also use a
top-level `pairings` collection (if one does, rename the collection in
`FirebaseRelay.kt` — see note at the bottom).

## Firestore

1. Firebase Console → Firestore Database → **Rules** tab.
2. Copy your CURRENT rules somewhere safe first (select all, copy) — just in
   case.
3. Find the line that looks like:
   ```
   match /databases/{database}/documents {
   ```
4. Paste this block right after that line (before your existing `match`
   blocks, order doesn't matter):

   ```
   match /pairings/{pairingCode} {
     allow get: if true;
     allow create, update, delete: if true;
     allow list: if false;

     match /commands/{commandId} {
       allow create: if request.resource.data.keys().hasOnly(['type', 'consumed', 'createdAt'])
                     && request.resource.data.consumed == false
                     && request.resource.data.type in
                        ['SWITCH_CAMERA', 'CAPTURE_PHOTO', 'CAPTURE_VIDEO', 'START_LIVE', 'STOP_LIVE'];
       allow read, update: if true;
       allow delete: if false;
     }
   }
   ```
5. Leave every other `match` block you already had untouched.
6. **Publish.**

## Storage — NOT needed

This project does not use Firebase Storage (it requires the paid Blaze
plan). Captured photos/videos/live-frames are uploaded to Cloudinary
instead. **Do not touch your existing Storage rules at all** — leave them
exactly as they are for your other apps.

## If another app already uses a `pairings` collection

Unlikely, but if `yash-software` already has a top-level `pairings`
collection for something else, rename the one this project uses before
merging rules — in `app/src/main/java/com/testlab/camerasec/firebase/FirebaseRelay.kt`,
change every `db.collection("pairings")` to something more specific like
`db.collection("camseclab_pairings")`, and update the collection name in both
rule snippets above to match.
