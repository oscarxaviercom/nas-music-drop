# 🎵 Music Drop

[🇬🇧 English](#english) · [🇪🇸 Español](#español)

**Personal audio library manager for Android** — free, open-source, **ad-free, and no accounts required**. Normalize tracks with **EBU R128**, embed **ID3v2.3** metadata (cover art and lyrics), and transfer them directly to your **NAS over SMB/Tailscale** or your phone's `Music/` folder.

Developed by **oscarxaviercom**.

---

## 📱 Screenshots / Capturas de pantalla

| History / Historial | Links & Queue / Enlaces y Cola | Search / Buscar | Settings / Ajustes |
| :---: | :---: | :---: | :---: |
| <img width="200" alt="History / Historial" src="https://github.com/user-attachments/assets/67f388bf-614f-4f5f-911b-e3ec55d09c1a" /> | <img width="200" alt="Links & Queue / Enlaces y Cola" src="https://github.com/user-attachments/assets/1f5b817b-ce36-4b50-b13b-0185c07e4807" /> | <img width="200" alt="Search / Buscar" src="https://github.com/user-attachments/assets/16fd3421-1f39-43ff-aed3-8a49b5b5389b" /> | <img width="200" alt="Settings / Ajustes" src="https://github.com/user-attachments/assets/535faeed-aec4-463b-9ce8-ce44f1fcd826" /> |

---

<a id="english"></a>
## 🇬🇧 English

### ✨ Why Music Drop?

- 🚫 **100% free, ad-free, and no sign-up**
- 🎚️ **True EBU R128 loudness normalization** (`-16`, `-14`, or `-11 LUFS`) with anti-clipping true-peak limiter
- 📝 **Embedded synced & plain lyrics** inside the MP3 tags
- 🎙️ **Built-in microphone audio recognition**
- 🗄️ **Direct SMB upload to your NAS** (local IP, Tailscale/VPN, or configurable SMB port)

### 🚀 Key Features

- **Link & playlist processing:** supports compatible media links and playlists (including via *Share → Music Drop*).
- **Duplicate detection:** checks your existing library first to pre-select only missing tracks.
- **Search, trim & audio recognition:** built-in search with preview playback, timestamp trimming, and ambient audio identification via microphone.
- **EBU R128 normalization & native MP3 encoding:**
  - `-16 LUFS (Soft)`: wider dynamic range (classical, acoustic, Hi-Fi).
  - `-14 LUFS (Standard)`: balanced streaming-standard loudness.
  - `-11 LUFS (Loud)`: higher volume with true-peak anti-clipping limiter (car audio or Bluetooth speakers).
  - `192k` or `320k` MP3 encoding via native LAME, `600×600` cover art, and embedded lyrics.
- **Flexible destination (NAS or Phone):**
  - **NAS (SMB)** mode with automatic switching between local IP (`445`), VPN (Tailscale / WireGuard / ZeroTier), or remote IP/DDNS with configurable SMB port.
  - **Local** mode directly to your phone's `Music/` folder.
  - Folder organization: flat, by `Artist`, or `Artist/Album`.
- **Multi-language:** available in English, Spanish, Portuguese, German, French, Italian, and Russian.

### 🛠️ Building from Source

1. Set `sdk.dir` in `local.properties`.
2. Build a debug APK:
   ```bash
   ./gradlew assembleDebug
   ```
3. To build a signed release APK, place your keystore at `musicdrop.keystore` and set its credentials in `local.properties` (`musicdrop.storePassword`, `musicdrop.keyAlias`, `musicdrop.keyPassword`) or via environment variables (`MUSICDROP_STORE_PASSWORD`, `MUSICDROP_KEY_ALIAS`, `MUSICDROP_KEY_PASSWORD`), then run:
   ```bash
   ./gradlew assembleRelease
   ```

### ⚖️ Disclaimer

This tool is intended strictly for personal use and managing your own audio library. Users are responsible for the content they process and for respecting copyright laws and each service's terms of use.

---

<a id="español"></a>
## 🇪🇸 Español

### ✨ ¿Por qué Music Drop?

- 🚫 **100 % gratuita, sin anuncios y sin registro**
- 🎚️ **Normalización EBU R128 real** (`-16`, `-14` o `-11 LUFS`) con limitador *true-peak* anti-clipping
- 📝 **Letras sincronizadas y planas incrustadas** en el propio MP3
- 🎙️ **Reconocimiento acústico por micrófono** integrado
- 🗄️ **Envío directo a tu NAS por SMB** (IP local, Tailscale/VPN o puerto SMB configurable)

### 🚀 Características principales

- **Procesamiento de enlaces y listas:** admite enlaces multimedia y listas compatibles (incluido mediante *Compartir → Music Drop*).
- **Control de duplicados:** comprueba previamente tu biblioteca para seleccionar solo las pistas que aún no tienes.
- **Búsqueda, recorte e identificación de audio:** buscador integrado con preescucha, recorte por marcas de tiempo e identificación acústica por micrófono.
- **Normalización EBU R128 y codificación MP3 nativa:**
  - `-16 LUFS (Suave)`: mayor rango dinámico (clásica, acústica, Hi-Fi).
  - `-14 LUFS (Estándar)`: equilibrio estándar de plataformas de streaming.
  - `-11 LUFS (Potente)`: mayor volumen con limitador true-peak anti-clipping (coche o altavoces Bluetooth).
  - Codificación MP3 `192k` o `320k` con LAME nativo, carátula `600×600` y letras incrustadas.
- **Destino flexible (NAS o Móvil):**
  - Modo **NAS (SMB)** con conmutación automática entre IP local (`445`), VPN (Tailscale / WireGuard / ZeroTier) o IP/DDNS remota con puerto SMB configurable.
  - Modo **Local** directo a la carpeta `Music/`.
  - Organización sin carpetas, por `Artista` o `Artista/Álbum`.
- **Multiidioma:** disponible en español, inglés, portugués, alemán, francés, italiano y ruso.

### 🛠️ Compilación

1. Configura `sdk.dir` en `local.properties`.
2. Para compilar en debug:
   ```bash
   ./gradlew assembleDebug
   ```
3. Para compilar en release firmado, configura tu keystore en `musicdrop.keystore` y sus credenciales en `local.properties` (`musicdrop.storePassword`, `musicdrop.keyAlias`, `musicdrop.keyPassword`) o mediante variables de entorno (`MUSICDROP_STORE_PASSWORD`, `MUSICDROP_KEY_ALIAS`, `MUSICDROP_KEY_PASSWORD`), y ejecuta:
   ```bash
   ./gradlew assembleRelease
   ```

### ⚖️ Aviso legal

Esta herramienta está pensada exclusivamente para uso personal y gestión de bibliotecas propias. El usuario es responsable del contenido que procese y de respetar los derechos de autor y las condiciones de uso de cada servicio.
