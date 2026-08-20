<div align="center">

# AnimePahe Extension for Aniyomi

[![Kotlin](https://img.shields.io/badge/Kotlin-2.2.0-7F52FF.svg?style=flat-square&logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Android](https://img.shields.io/badge/Android-API%2026%2B-3DDC84.svg?style=flat-square&logo=android&logoColor=white)](https://developer.android.com)
[![Gradle](https://img.shields.io/badge/Gradle-8.14-02303A.svg?style=flat-square&logo=gradle&logoColor=white)](https://gradle.org)
[![License](https://img.shields.io/badge/License-GPLv3-blue.svg?style=flat-square)](LICENSE)

AnimePahe source extension for [Aniyomi](https://aniyomi.org).

</div>

---


## Project Structure

```
AnimePaheExt/
├── src/main/
│   ├── AndroidManifest.xml
│   ├── res/                                 # Launcher mipmap assets
│   └── kotlin/eu/kanade/tachiyomi/animeextension/en/animepahe/
│       ├── AnimePahe.kt                     # Source entry point & coordinator
│       ├── AnimePahePreferences.kt          # Settings & preference binding
│       ├── KwikExtractor.kt                 # Video link & quality extractor
│       ├── database/
│       │   ├── AnimePaheDatabase.kt         # SQLite session cache helper
│       │   └── AnimeSessionEntry.kt         # Session entity data class
│       ├── dto/                             # Kotlinx serialization models
│       │   ├── EpisodeDto.kt
│       │   ├── LatestAnimeDto.kt
│       │   ├── ResponseDto.kt
│       │   └── SearchResultDto.kt
│       ├── network/                         # Interceptors & HTTP factory
│       │   ├── CloudflareInterceptor.kt
│       │   ├── DdosGuardInterceptor.kt
│       │   └── HttpClientFactory.kt
│       ├── proxy/                           # Local HTTP streaming proxy
│       │   ├── CryptoUtils.kt
│       │   └── KwikProxyServer.kt
│       └── repository/                      # Data layer & API parser
│           ├── AnimePaheRepository.kt
│           └── AnimePaheRepositoryImpl.kt
├── gradle/
│   └── libs.versions.toml                   # Centralized Version Catalog
├── build.gradle.kts                         # Single-module build script
└── settings.gradle.kts
```

---

## Build & Installation

### Prerequisites
- **JDK 17** 
- **Android SDK**

### Building from Source

Clone the repository and build the debug or release APK using the Gradle wrapper:

```bash
# Clone repository
git clone https://github.com/CursedSheep/AnimePaheExt.git
cd AnimePaheExt

# Build Debug APK
./gradlew assembleDebug

# Build Release APK
./gradlew assembleRelease
```

---


## License

Licensed under the [GNU General Public License v3.0](LICENSE).