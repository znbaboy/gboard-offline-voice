# gboard-offline-voice

## ADVANCED VOICE TYPING RELEASED: https://github.com/znbaboy/gboard-offline-voice/releases

Morphe patch to manually download offline Google "advanced voice typing" voice model. No Gapps required. 100% vibe coded with Opus 5.5

Needed a way to use Gboard with voice typing fully offline and native. Couldn't find a solution so I AI slopped one out.

**NOTES:**
- 100% vibe coded
- probably wont update this any further unless something breaks for me
- build on Gboard gboard-18-6-3-1002251336-release-arm64-v8a. Feel free to fork and improve it
- can disable internet access to the app after model is downloaded

**Features:**
- Offline advanced voice typing (Google's server recognizer blocked)
- Setup screen: Voice typing → Advanced voice typing
- Voice typing in incognito (switch)
- Keep other audio playing while dictating (switch)
- Online features hidden (stickers, Emoji Kitchen, Translate, writing tools, usage sharing)
- GIF search switch (off by default)
- Experimental network filter. Not a guaranteed block. For that, turn off Gboard's network in Android settings. Voice typing still works, since Speech Services does the recognition on the phone.

**Install:**
1. Install Speech Services by Google (unpatched).
2. Download the .mpp from Releases, import it in Morphe Manager, and patch Gboard.
3. Gboard Settings → Voice typing → Advanced voice typing, follow the steps.

https://github.com/user-attachments/assets/f3d08477-0eed-48c4-ac72-6935d5a9dd3a
