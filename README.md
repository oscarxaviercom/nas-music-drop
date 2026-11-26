# Music Drop

Aplicación Android para descargar música desde enlaces y búsquedas, normalizar el audio con **EBU R128**, incrustar metadatos ID3v2.3 (carátula 600×600 y letras sincronizadas/planas) y enviarla directamente a tu **NAS por SMB** o guardarla en la carpeta `Music/` del teléfono.

Desarrollado por **oscarxaviercom**.

## Características principales

- **Enlaces y playlists:** compatible con YouTube, YouTube Music, Spotify, Apple Music, Deezer, Amazon Music y SoundCloud (incluido «Compartir → Music Drop»).
- **Detección de duplicados:** comprueba previamente tu biblioteca para marcar solo las pistas que te faltan.
- **Búsqueda y reconocimiento por micrófono:** buscador integrado con preescucha, recorte por marcas de tiempo y reconocimiento de música ambiente.
- **Normalización EBU R128 y MP3 nativo:**
  - `-16 LUFS (Suave)`: mayor rango dinámico (clásica, acústica, Hi-Fi).
  - `-14 LUFS (Estándar)`: equilibrio estándar tipo Spotify/YouTube.
  - `-11 LUFS (Potente)`: mayor volumen con limitador anti-clipping (coche, altavoces Bluetooth).
  - Codificación MP3 `192k` o `320k` con LAME nativo.
- **Destino flexible (NAS o Móvil):**
  - Modo **NAS (SMB)** con conmutación automática entre IP local (`445`), VPN (Tailscale / WireGuard / ZeroTier) o IP/DDNS remota con puerto SMB configurable.
  - Modo **Local** directo a la carpeta `Music/`.
  - Organización sin carpetas, por `Artista` o `Artista/Álbum`.

## Compilación

1. Configura `sdk.dir` en `local.properties`.
2. Para compilar en debug:
   ```bash
   ./gradlew assembleDebug
   ```
3. Para compilar en release firmado, configura tu keystore en `musicdrop.keystore` y sus credenciales en `local.properties` (`musicdrop.storePassword`, `musicdrop.keyAlias`, `musicdrop.keyPassword`) o mediante variables de entorno (`MUSICDROP_STORE_PASSWORD`, `MUSICDROP_KEY_ALIAS`, `MUSICDROP_KEY_PASSWORD`), y ejecuta:
   ```bash
   ./gradlew assembleRelease
   ```
