# 🎵 Music Drop

**Gestor de biblioteca musical personal para Android**, gratuito, de código abierto y **sin anuncios ni cuentas**. Normaliza tus pistas con **EBU R128**, incrusta metadatos **ID3v2.3** (carátula y letras) y envíalas directamente a tu **servidor NAS por SMB/Tailscale** o a la carpeta `Music/` del móvil.

Desarrollado por **oscarxaviercom**.

---

## ✨ ¿Por qué Music Drop?

- 🚫 **100 % gratuita, sin anuncios y sin registro**
- 🎚️ **Normalización EBU R128 real** (`-16`, `-14` o `-11 LUFS`) con limitador *true-peak* anti-clipping
- 📝 **Letras sincronizadas y planas incrustadas** en el propio MP3
- 🎙️ **Reconocimiento acústico por micrófono** integrado
- 🗄️ **Envío directo a tu NAS por SMB** (IP local, Tailscale/VPN o puerto SMB configurable)

---

## 📱 Capturas de pantalla

| Historial | Enlaces/Cola | Buscar | Ajustes |
| :---: | :---: | :---: | :---: |
| <img width="200" alt="Captura 1" src="https://github.com/user-attachments/assets/67f388bf-614f-4f5f-911b-e3ec55d09c1a" /> | <img width="200" alt="Captura 2" src="https://github.com/user-attachments/assets/1f5b817b-ce36-4b50-b13b-0185c07e4807" /> | <img width="200" alt="Captura 3" src="https://github.com/user-attachments/assets/16fd3421-1f39-43ff-aed3-8a49b5b5389b" /> | <img width="200" alt="Captura 4" src="https://github.com/user-attachments/assets/535faeed-aec4-463b-9ce8-ce44f1fcd826" /> |

---

## 🚀 Características principales

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
- **Multiidioma:** disponible en español, inglés, portugués, alemán, francés e italiano.

---

## 🛠️ Compilación

1. Configura `sdk.dir` en `local.properties`.
2. Para compilar en debug:
   ```bash
   ./gradlew assembleDebug
   ```
3. Para compilar en release firmado, configura tu keystore en `musicdrop.keystore` y sus credenciales en `local.properties` (`musicdrop.storePassword`, `musicdrop.keyAlias`, `musicdrop.keyPassword`) o mediante variables de entorno (`MUSICDROP_STORE_PASSWORD`, `MUSICDROP_KEY_ALIAS`, `MUSICDROP_KEY_PASSWORD`), y ejecuta:
   ```bash
   ./gradlew assembleRelease
   ```

---

## ⚖️ Aviso legal

Esta herramienta está pensada exclusivamente para uso personal y gestión de bibliotecas propias. El usuario es responsable del contenido que procese y de respetar los derechos de autor y las condiciones de uso de cada servicio.
