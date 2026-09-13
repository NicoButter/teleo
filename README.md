<p align="center">
  <img src="app/src/main/res/drawable-nodpi/logo_teleo_transparent.png" alt="Teleo Logo" width="360">
</p>

# Teleo

Teleo es un prototipo Android de accesibilidad y comunicación presencial. Convierte
voz y mensajes en experiencias visuales y explora una representación visual/háptica
de la música para personas sordas o con hipoacusia.

## Estado actual

- **Palabra Viva:** transcripción en vivo mediante el reconocimiento de voz de
  Android.
- **Escribir:** mensajes escritos presentados a gran tamaño.
- **Teleo Cerca:** comunicación local entre dispositivos con Google Nearby
  Connections, selección o QR de sesión y aprobación del host.
- **Teleo Música — Experimental:** perfiles Resonance self-hosted HTTPS, audio local
  y señales visuales/hápticas sincronizadas por `ExoPlayer.currentPosition`. Las
  experiencias REMOTE se muestrean por timestamps; la demo Mock continúa disponible
  de forma explícita y separada.
- **Voice Visual Lab:** laboratorio simulado de formas, vocales y partículas; todavía
  no analiza micrófono ni audio.

La descripción técnica completa y verificable para personas y asistentes de IA está
en **[Estado actual de Teleo](docs/PROJECT_CONTEXT.md)**. Las decisiones y límites
específicos de la experiencia musical están en
**[Teleo Música](docs/TELEO_MUSIC.md)**.

## Stack

- Kotlin y Jetpack Compose/Material 3.
- Android mínimo 8.0 (API 26), `compileSdk` y `targetSdk` 35.
- Google Nearby Connections, Android Speech Recognition, CameraX y ML Kit Barcode.
- Media3 ExoPlayer y AndroidX Graphics Shapes para Teleo Música.

## Compilar

Abrir el proyecto en Android Studio con un SDK Android compatible o ejecutar:

```bash
./gradlew assembleDebug --no-configuration-cache
./gradlew testDebugUnitTest compileDebugAndroidTestKotlin --no-configuration-cache
```

Las pruebas instrumentadas requieren emulador o dispositivo:

```bash
./gradlew connectedDebugAndroidTest --no-configuration-cache
```

`deploy.sh` compila e instala el APK debug en un dispositivo conectado por USB. Si
hay conflicto de firma, el script puede desinstalar primero la app y eliminar sus
datos locales.

## Configuración local

Teleo Música configura servidores Resonance desde su pantalla **Servidores**. Cada
perfil exige una URL HTTPS, puede usar Bearer y se guarda en el almacenamiento
privado de la app; los tokens se cifran mediante Android Keystore. No se deben
versionar `.env`, `local.properties`, keystores ni credenciales.

En una experiencia REMOTE, Teleo conserva el audio en el dispositivo y procesa el
JSON temporal como: `Teleo Music Protocol → TeleoExperience →
TeleoExperienceSampler → TeleoVisualState → renderer`. No descarga ni reproduce
audio remoto, ni mezcla datos del modo Mock.

## Alcance responsable

Teleo Música no interactúa con implantes cocleares, no programa dispositivos médicos
y no se presenta como tratamiento ni como recuperación de la audición. El análisis
musical local todavía no está implementado: Teleo no ejecuta separación de stems ni
IA musical. Las experiencias reales vienen de JSON temporal producido externamente
por Kinetra Resonance; la demo Mock sigue usando datos simulados.

## Autor

Desarrollado por **Nicolas Butterfield** —
[nicobutter@gmail.com](mailto:nicobutter@gmail.com) ·
[@nicobutter](https://github.com/nicobutter)

<p align="center">
  <a href="https://vetrabyte.com.ar/">
    <img src="app/src/main/res/drawable-nodpi/logo_vetrabyte.png" alt="Vetrabyte" width="260">
  </a>
</p>
