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

<img width="1080" height="2400" alt="photo_2026-11-27_01-08-08" src="https://github.com/user-attachments/assets/67f388bf-614f-4f5f-911b-e3ec55d09c1a" />
<img width="1080" height="2400" alt="photo_2026-11-27_01-06-52" src="https://github.com/user-attachments/assets/1f5b817b-ce36-4b50-b13b-0185c07e4807" />
<img width="1080" height="2400" alt="photo_2026-11-27_01-06-41" src="https://github.com/user-attachments/assets/16fd3421-1f39-43ff-aed3-8a49b5b5389b" />
<img width="1080" height="2400" alt="photo_2026-11-27_01-06-32" src="https://github.com/user-attachments/assets/535faeed-aec4-463b-9ce8-ce44f1fcd826" />
