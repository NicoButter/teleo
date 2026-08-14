# Estado actual de Teleo

> Documento de traspaso para ChatGPT, Codex y personas que se incorporen al
> proyecto. Resume el estado comprobado del repositorio completo, no una visión
> futura del producto.

## Fotografía verificada

- **Fecha de revisión:** 13 de agosto de 2026.
- **Rama revisada:** `main`.
- **Commit base:** `e45ae40d142f26b20c19eea8a25cb963772add96` —
  `feat: Implement Voice Visual Lab with interactive controls and visualizations`.
- **Aplicación:** Android nativa, un único módulo Gradle (`:app`).
- **Identificador:** `com.nicolas.teleo`.
- **Versión declarada:** `1.0` (`versionCode = 1`).
- **Estado de compilación al revisar:**
  `testDebugUnitTest` y `compileDebugAndroidTestKotlin` completaron con
  `BUILD SUCCESSFUL`.

Este documento describe el código del commit indicado más los cambios puramente
documentales que lo incorporan. Si una afirmación contradice al código, al
manifest, a Gradle o a una prueba, esas fuentes técnicas tienen prioridad y este
archivo debe corregirse.

## Qué es Teleo hoy

Teleo es un prototipo Android de accesibilidad y comunicación presencial. Busca
convertir voz y mensajes en señales que puedan percibirse visualmente y, en la
experiencia musical, mediante vibración. Toda la interfaz está construida con
Kotlin y Jetpack Compose.

La aplicación tiene cuatro experiencias visibles desde Inicio:

| Experiencia | Estado real | Fuente principal |
| --- | --- | --- |
| **Palabra Viva** | Transcribe voz con `SpeechRecognizer`, muestra resultados parciales y finales, y ofrece modo continuo o pulsar-para-hablar. | `MainActivity.kt` |
| **Escribir** | Permite escribir un mensaje y presentarlo con tipografía grande en pantalla. | `MainActivity.kt` |
| **Teleo Cerca** | Conecta dispositivos próximos mediante Google Nearby Connections, con creación/unión de charla, QR, mensajes escritos y transcripción compartida. | `MainActivity.kt` |
| **Teleo Música** | Descarga experiencias temporales Kinetra Resonance desde JSON estático HTTPS, las cachea y las sincroniza con un audio local mediante ExoPlayer. El modo Mock y Voice Visual Lab siguen siendo simulados explícitos. | `features/music/` |

También existe un perfil local con nickname, color e imagen tomada con cámara o
seleccionada desde la galería.

## Flujo de la aplicación

1. `MainActivity` muestra el splash nativo y luego uno Compose de 1,4 segundos.
2. Inicio ofrece las cuatro experiencias y el acceso al perfil.
3. La navegación de las funciones históricas usa el enum `Screen`; no utiliza
   Navigation Compose.
4. `MainActivity` está bloqueada en orientación horizontal, modo inmersivo y con
   la pantalla encendida.
5. Teleo Música abre una actividad interna separada, `MusicActivity`, bloqueada en
   vertical, también inmersiva y con la pantalla encendida.

## Capacidades implementadas

### Palabra Viva

- Usa el servicio de reconocimiento configurado en Android mediante
  `SpeechRecognizer` y solicita `RECORD_AUDIO` en tiempo de ejecución.
- Solicita resultados parciales, convierte el texto a mayúsculas y anima la última
  palabra mientras conserva el resultado final visible.
- Reinicia la escucha después de un resultado o de errores recuperables de silencio
  y ausencia de coincidencias.
- El modo walkie-talkie cambia entre escucha continua y pulsar-para-hablar.
- La decoración opcional reconoce unas pocas palabras y agrega emojis.
- Las “emociones” no provienen de un modelo: son heurísticas basadas en `JAJA` /
  `HAHA` y en el nivel RMS para clasificar risa, grito o susurro.
- La disponibilidad y el uso de red dependen del proveedor de reconocimiento de voz
  instalado en el dispositivo; Teleo no implementa su propio motor.

### Escribir y mostrar

- Mantiene texto en estado Compose local y lo presenta a gran tamaño.
- Puede aplicar la misma decoración básica con emojis.
- No guarda historial ni sincroniza el texto con un servidor.

### Perfil

- Guarda nickname, color, avatar y preferencia walkie-talkie en
  `SharedPreferences` (`teleo_prefs`).
- El avatar se recorta a cuadrado, se reduce a 384 × 384 y se persiste como PNG en
  Base64 dentro de las preferencias.
- La cámara frontal y la galería son fuentes de avatar.
- Los interruptores de emojis y emociones viven solo durante la ejecución actual;
  no se persisten.

### Teleo Cerca

- Usa `Nearby.getConnectionsClient`, estrategia `P2P_STAR` y el service ID
  hardcodeado `com.nicolas.teleo.NEARBY_SERVICE`.
- El host publica una sesión aleatoria de ocho caracteres y aprueba o rechaza cada
  solicitud.
- Un participante puede descubrir sesiones, elegir una lista o escanear el QR del
  identificador de sesión. El QR ayuda a localizar la charla; no es autenticación.
- Los payloads son JSON en memoria. Admiten texto escrito, resultado parcial de voz,
  resultado final y mensajes de sistema.
- El host puede ver y expulsar participantes conectados.
- Al desconectar o destruir la actividad se detienen discovery/advertising, se
  cierran endpoints y se limpian participantes y mensajes.
- No hay historial durable, cuentas, backend, sincronización posterior ni protocolo
  de aplicación versionado. La validación real debe hacerse con al menos dos
  dispositivos Android y combinaciones de versiones/permisos.

### Teleo Música

- Selecciona audio local con el selector del sistema (`OpenDocument`) y conserva el
  permiso de lectura del URI cuando el proveedor lo permite.
- Resuelve nombre, artista, duración, tamaño e ID estable sin copiar el audio.
- Reproduce mediante Media3 ExoPlayer; `currentPosition` es la única referencia
  temporal para visuales, letra y háptica.
- Obtiene `catalog.json` y `tracks/<track-id>/experience.json` mediante HTTPS,
  usando una única `BuildConfig.TELEO_MUSIC_BASE_URL` configurada desde
  `local.properties`.
- Cachea catálogo y experiencias remotas validadas en
  `filesDir/music_timelines/remote/`; conserva el caché previo de timelines Mock en
  `filesDir/music_timelines/<track-hash>.json`.
- Tiene estados `Idle`, `TrackSelected`, `Analyzing`, `Countdown`, `Playing` y
  `Error`, coordinados por `MusicExperienceViewModel`.
- Ofrece escenas Sinestesia, Carriles y Minimal; controles de calidad, instrumentos,
  partículas, destellos, movimiento reducido, letra y ajuste de sincronía.
- Genera pulsos hápticos para eventos y los cancela al pausar, buscar o salir.
- El detalle de modelos, render, letras, caché, háptica y decisiones se mantiene en
  [`TELEO_MUSIC.md`](TELEO_MUSIC.md).

#### Límite esencial de Música

`MockMusicAnalyzer` no lee ni decodifica el contenido de audio y se conserva solo
como demo/fallback explícito. Para una experiencia `REMOTE`, Teleo no mezcla letras,
secciones, visemas, eventos ni háptica del mock: consume exclusivamente el JSON
Kinetra validado. El teléfono no ejecuta separación de fuentes, Demucs, RoFormer,
análisis musical pesado ni IA; es un runtime player + renderer. No hay streaming de
audio, servidor propio, autenticación ni reproducción en segundo plano.

### Voice Visual Lab

- Se abre desde la selección de Teleo Música sin elegir un archivo.
- Sus sliders y demos producen presencia, intensidad, tono, vibrato, ataque y mezcla
  de vocales A/E/I/O/U/neutral.
- Usa morphing vectorial con `graphics-shapes`, suavizado y partículas deterministas.
- Es un laboratorio visual: no abre el micrófono, no analiza audio y no detecta
  vocales reales.

## Arquitectura del repositorio

```text
teleo/
├── app/
│   ├── build.gradle.kts
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── java/com/nicolas/teleo/
│       │   │   ├── MainActivity.kt
│       │   │   ├── ui/theme/
│       │   │   └── features/music/
│       │   │       ├── data/
│       │   │       ├── domain/
│       │   │       ├── haptics/
│       │   │       ├── playback/
│       │   │       ├── ui/
│       │   │       └── visual/
│       │   └── res/
│       ├── test/
│       └── androidTest/
├── docs/
│   ├── PROJECT_CONTEXT.md
│   └── TELEO_MUSIC.md
├── AGENTS.md
├── README.md
└── deploy.sh
```

### Código histórico

`MainActivity.kt` tiene aproximadamente 1.200 líneas y concentra modelos,
serialización JSON, Nearby, reconocimiento de voz, permisos, navegación y casi toda
la UI no musical. Usa estados Compose propiedad de la actividad; no hay ViewModel,
inyección de dependencias ni capas de dominio/datos para estas funciones.

### Código de Música

Música está separado por responsabilidades dentro de `features/music`, aunque sigue
perteneciendo al módulo `:app`:

- `domain`: modelos, contratos y consultas temporales.
- `data`: resolución del URI, analizador simulado, letras y repositorio JSON.
- `playback`: contrato e integración con ExoPlayer.
- `haptics`: contrato y adaptación a vibración Android.
- `visual`: interpolación, escenas, partículas y lenguaje visual de voz.
- `ui`: selección, preparación, reproducción, Canvas y laboratorio.
- `MusicExperienceViewModel`: orquestación de estado, caché, reproducción y háptica.

## Datos, red y privacidad

| Dato | Persistencia / transporte |
| --- | --- |
| Nickname, color, avatar, walkie-talkie | `SharedPreferences` locales |
| Emojis y emociones activados | Memoria de `MainActivity` |
| Mensajes de Teleo Cerca | Memoria y payloads Nearby; se borran al desconectar |
| Audio seleccionado | Permiso persistente al URI; Teleo no copia el audio |
| Timeline Mock | JSON privado de la app en `filesDir/music_timelines` |
| Catálogo/experiencia remota | JSON privado y validado en `filesDir/music_timelines/remote` |
| Letra/traducción demo | Generada localmente; no contiene letra comercial |

No existe backend propio, base de datos remota, autenticación, analítica, telemetría
ni Firebase. Teleo sí hace dos GET públicos de JSON estático con OkHttp, siempre
contra rutas relativas de la base HTTPS configurada. Nearby y el proveedor Android
de voz también son servicios externos al proceso y pueden tener requisitos propios.

El código no carga `.env`. `TELEO_MUSIC_BASE_URL` se genera en `BuildConfig` desde
`local.properties`, con fallback seguro `https://music.teleo.invalid/`. La URL no es
secreta; `local.properties` sigue sin versionarse para separar development y
production. `.env` y `local.properties` nunca deben copiarse a la documentación.

## Permisos y hardware

- Micrófono: `RECORD_AUDIO`.
- Vibración: `VIBRATE`.
- Cámara opcional: `CAMERA`, usada para avatar y QR.
- Nearby según versión Android: ubicación hasta Android 12L, Bluetooth
  scan/advertise/connect desde Android 12 y `NEARBY_WIFI_DEVICES` desde Android 13.
- Estado y modificación de Wi-Fi, más permisos Bluetooth heredados para versiones
  antiguas.

El mínimo soportado es Android 8.0 (API 26); `compileSdk` y `targetSdk` son 35.

## Stack y versiones relevantes

- Kotlin `2.2.10`.
- Android Gradle Plugin `9.2.1`.
- Gradle Wrapper `9.4.1`.
- Java source/target `11`; el script de despliegue busca un JDK completo 21.
- Jetpack Compose con BOM `2026.02.01` y Material 3.
- Media3 ExoPlayer `1.5.1`.
- OkHttp `4.12.0` y Kotlinx Serialization JSON `1.8.1` para experiencias remotas.
- Lifecycle `2.8.7`.
- Google Nearby `19.3.0`.
- CameraX `1.4.1`.
- ML Kit Barcode Scanning `17.3.0` y ZXing Core `3.5.4`.
- AndroidX Graphics Shapes `1.1.0`.

No hay Navigation Compose, Hilt/Koin, Room, Retrofit/Ktor, WorkManager ni Media3
Session. Tampoco hay configuración de CI en el repositorio.

## Compilar y validar

Desde la raíz:

```bash
./gradlew assembleDebug --no-configuration-cache
./gradlew testDebugUnitTest compileDebugAndroidTestKotlin --no-configuration-cache
```

Las pruebas JVM cubren principalmente dominio, repositorio, letras, háptica y
motores visuales de Música. Las pruebas Compose instrumentadas cubren flujos y
controles principales de Música y Voice Visual Lab, pero solo se ejecutan con
emulador o dispositivo:

```bash
./gradlew connectedDebugAndroidTest --no-configuration-cache
```

Para instalar el debug APK en un dispositivo USB puede usarse:

```bash
./deploy.sh
```

Ese script puede desinstalar la aplicación instalada si detecta conflicto de firma;
la desinstalación elimina sus datos locales. Debe usarse de forma consciente.

## Limitaciones y deuda técnica confirmadas

- Las funciones principales están concentradas en un archivo grande y tienen pocas
  pruebas automatizadas fuera de Música.
- La navegación principal y varios estados no sobreviven necesariamente a una
  recreación de actividad; solo parte del perfil se persiste.
- Hay callbacks que silencian excepciones en payloads, cámara y escáner, lo que
  dificulta diagnóstico y observabilidad.
- Teleo Cerca necesita validación de interoperabilidad, desconexiones, reconexión,
  límites de participantes y permisos en dispositivos reales.
- El service ID de Nearby está duplicado conceptualmente entre el código y
  `.env.example`; la plantilla no controla la app.
- No existe configuración de firma release en Gradle y `release` tiene minificación
  desactivada.
- La versión sigue en `1.0`/`1` pese al alcance experimental añadido.
- Las capturas referenciadas por versiones antiguas del README no existen en el
  repositorio actual.
- El render remoto admite el contrato v1 de Kinetra; no aplica aún un renderer
  articulatorio avanzado para visemas Rhubarb, aunque los sincroniza y expone.
- No se verificaron pruebas instrumentadas en esta revisión porque requieren un
  dispositivo o emulador conectado.

## Criterios para futuras modificaciones

1. Separar siempre “implementado”, “simulado” y “planificado” en documentación y UI.
2. Mantener accesibilidad por forma, texto y posición; no depender solo de color,
   sonido, vibración o destellos.
3. Detener micrófono, vibración, reproducción, CameraX y Nearby al salir del flujo
   correspondiente.
4. No introducir letras protegidas, scraping ni APIs no oficiales sin una decisión
   explícita de producto/licencias.
5. No presentar la app como tratamiento, restauración auditiva o interfaz para
   implantes/dispositivos médicos.
6. Preservar datos del usuario y cambios ajenos; nunca versionar secretos ni archivos
   locales del SDK.
7. Añadir pruebas en la capa afectada y ejecutar los comandos de validación de este
   documento.
8. Si cambia el estado del producto, actualizar la fecha, el commit base, la tabla de
   capacidades, las limitaciones y el resultado de validación de este archivo.

## Próximo punto técnico ya identificado

La documentación de Música propone como incremento mínimo reemplazar parte del mock
con análisis local de una ventana PCM mono: decodificar con
`MediaExtractor`/`MediaCodec`, calcular energía RMS por bloques y detectar picos con
umbral adaptativo. Es una propuesta documentada, no una función implementada ni una
prioridad global confirmada.
