# 🎵 Seek Audio — Android

A feature-rich audio player for Android, built for the **Solana Seeker dApp Store**.

## Tech Stack

| Layer | Library |
|---|---|
| Language | Kotlin |
| UI | XML Views + ViewBinding |
| Architecture | MVVM + Repository |
| Audio Engine | Media3 / ExoPlayer |
| Database | Room |
| DI | Hilt |
| Async | Coroutines + Flow |
| Image loading | Glide |
| Navigation | Navigation Component |

## Project Structure

```
app/src/main/
├── java/com/seekaudio/
│   ├── SeekAudioApp.kt          # Hilt application
│   ├── data/
│   │   ├── model/               # Song, Playlist, EqState, WalletState, etc.
│   │   └── repository/          # Room DB, DAOs, MediaStore scanner
│   ├── di/                      # Hilt AppModule
│   ├── service/
│   │   └── PlaybackService.kt   # Media3 foreground service
│   ├── ui/
│   │   ├── MainActivity.kt      # Nav host + mini player
│   │   ├── player/              # PlayerViewModel, PlayerFragment, QueueFragment, WaveformView
│   │   ├── library/             # LibraryFragment + SongAdapter
│   │   ├── equalizer/           # EqualizerFragment (10-band EQ)
│   │   ├── sleep/               # SleepTimerFragment
│   │   ├── lyrics/              # LyricsFragment + LyricsAdapter
│   │   ├── artist/              # ArtistFragment
│   │   ├── id3/                 # Id3EditorFragment
│   │   ├── driving/             # DrivingActivity (fullscreen driving mode)
│   │   └── web3/                # Web3Fragment (optional Solana wallet)
│   └── utils/
│       └── Extensions.kt        # View helpers, formatDuration
└── res/
    ├── layout/                  # All XML layouts
    ├── navigation/nav_graph.xml # Navigation graph
    ├── drawable/                # Shape drawables + vector icons
    ├── values/                  # strings, colors, themes, dimens
    └── menu/bottom_nav_menu.xml
```

## Features

### Phase 1 — Core Player
- Local audio file scanning (MediaStore)
- Background playback (Media3 foreground service)
- Notification + lock screen controls
- Shuffle, repeat (off/all/one)
- Playback speed (0.5× – 2.0×)
- Volume control
- 10-band Equalizer + presets
- Bass Boost + Virtualizer
- Sleep Timer (5/15/30/45/60 min + end of track + fade out)

### Phase 2 — Extended Features
- Synced Lyrics viewer (supports .lrc format)
- Artist profiles
- ID3 tag editor (title, artist, album, genre, year)
- Driving Mode (fullscreen, giant controls)
- Queue management

### Phase 3 — Solana / Seeker (Optional)
- Wallet connect (Seed Vault, Phantom, Backpack, Solflare)
- SKR token rewards for listening
- Audio NFT player
- Artist tipping with SKR
- Activity feed
- All wallet features are **100% optional** — app works fully without connecting

## Setup

### Requirements
- Android Studio Hedgehog or later
- JDK 17
- Android SDK 34
- Min SDK 26 (Android 8.0)

### Steps
1. Clone / open project in Android Studio
2. Let Gradle sync
3. Run on device or emulator (API 26+)
4. Grant storage permission when prompted — app scans your local audio files

### To add real Solana wallet support
Replace the mock `connectWallet()` in `Web3Fragment` and `PlayerViewModel` with the
[Solana Mobile Wallet Adapter](https://github.com/solana-mobile/mobile-wallet-adapter):

```kotlin
// build.gradle — add dependency
implementation 'com.solanamobile:mobile-wallet-adapter-clientlib-ktx:2.0.3'

// Replace mock connection with real MWA call
val result = MobileWalletAdapter().transact(activity) {
    authorize(identityUri, iconUri, "Seek Audio", RpcCluster.MainnetBeta)
}
```

### To add real lyrics
Replace the empty lyrics list in `LyricsFragment` with an LRC file parser:
- Store `.lrc` files alongside audio files (same name, `.lrc` extension)
- Or integrate [LrcLib API](https://lrclib.net) for online lyric fetching

### dApp Store submission
See the [Solana Mobile docs](https://docs.solanamobile.com/dapp-publishing/overview) for
how to mint your Publisher NFT and submit the signed APK.

## License
MIT
