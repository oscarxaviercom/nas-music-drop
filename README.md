# Music Drop

Aplicación Android para gestionar, normalizar y organizar tu biblioteca de audio personal, con etiquetado automático (ID3v2.3, carátulas y letras) y transferencia directa a tu **servidor NAS por SMB** o a la carpeta `Music/` del dispositivo.

Desarrollado por **oscarxaviercom**.

## Características principales

- **Procesamiento de enlaces y listas:** admite enlaces multimedia y listas compatibles (incluido mediante «Compartir → Music Drop»).
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
- **Multiidioma:** disponible en español, inglés, portugués, alemán, francés e italiano.

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

## Aviso legal

Esta herramienta está pensada exclusivamente para uso personal y gestión de bibliotecas propias. El usuario es responsable del contenido que procese y de respetar los derechos de autor y las condiciones de uso de cada servicio.
