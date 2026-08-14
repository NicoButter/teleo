# Teleo Música

## Objetivo

Teleo Música es un prototipo experimental para representar una canción mediante imagen y tacto. Está pensado para que una persona sorda o con hipoacusia pueda seguir ritmo, secciones, voz, melodía, bajo y batería sin que un estado importante dependa exclusivamente del audio.

La experiencia no interactúa con implantes cocleares, no programa dispositivos médicos y no se presenta como tratamiento ni como recuperación de la audición.

## Runtime remoto Kinetra Resonance

Teleo Música soporta dos fuentes explícitas:

- **REMOTE:** Kinetra Resonance produce `teleo_experience.json`; Teleo descarga un
  catálogo y la experiencia estática por HTTPS, valida el contrato, la cachea y la
  reproduce sobre un audio local seleccionado por la persona.
- **MOCK:** `MockMusicAnalyzer` permanece para demo, desarrollo y tests. Nunca se
  usa como fallback silencioso de una experiencia remota.

Teleo no analiza el audio ni ejecuta separación de stems, Demucs, RoFormer,
Rhubarb o IA musical. Es el runtime player + renderer. `ExoPlayer.currentPosition`
sigue siendo la única referencia temporal para eventos, letras, visemas, visuales y
háptica; los relojes de frame solo mueven la física visual.

### Configuración Android

Crear o editar `local.properties` en la raíz, sin versionarlo:

```properties
TELEO_MUSIC_BASE_URL=https://music.example.com/
```

Gradle genera `BuildConfig.TELEO_MUSIC_BASE_URL`. Sin configuración, usa
`https://music.teleo.invalid/`; así development no habilita HTTP accidentalmente.
Solo se aceptan URLs de experiencia relativas a esa base. La app declara
`INTERNET`, pero no habilita cleartext HTTP global.

### Contrato estático v1

`GET <BASE>/catalog.json`:

```json
{
  "format": "teleo-music-catalog",
  "version": 1,
  "tracks": [{
    "id": "track-id",
    "title": "Título",
    "artist": "Artista opcional",
    "durationMs": 253794,
    "experienceVersion": 1,
    "quality": "AUTOMATIC",
    "experienceUrl": "tracks/track-id/experience.json",
    "sourceHash": { "algorithm": "sha256", "value": "opcional" }
  }]
}
```

`GET <BASE>/tracks/<track-id>/experience.json` requiere `format: "teleo-music"`,
`version: 1` y un `track` que coincida en `id` y `durationMs` con el catálogo. Los
canales opcionales son `drums.events`, `bass.events`, `guitar.events`,
`piano.events`, `vocals.events`, `vocals.visemes`, `other.events`, `lyrics`,
`sections`, `haptics` y `featureFrames`. Cada evento temporal usa `startMs`,
`durationMs` o `endMs`, `intensity` y, si corresponde, `type`/`label`.

Los visemas usan códigos visuales Rhubarb `A B C D E F G H X`; no son etiquetas de
fonemas. Teleo los consulta con búsqueda binaria según `startMs <= position < endMs`
y hoy los muestra como señal visual sincronizada. La futura capa
`TeleoArticulationMapper → MouthPose` podrá consumir el mismo dominio.

La versión de esquema (`version`) y la revisión concreta (`experienceVersion`) son
independientes. Una versión de esquema desconocida se rechaza con “Esta experiencia
requiere una versión más reciente de Teleo.”

### Caché, offline y audio local

La caché vive en `filesDir/music_timelines/remote`: catálogo y, por track,
`experience.json` + `metadata.json` con track ID, versiones, fecha y URL. Las
escrituras son temporales y luego se reemplazan; una experiencia sin metadata no se
considera válida. Si no hay red se usa la experiencia cacheada válida. Si el catálogo
sube `experienceVersion`, se descarga la nueva versión. JSON inválido o truncado de
caché se descarta antes de volver a descargar.

El audio no se descarga ni se copia. Teleo valida duración con tolerancia central
`AudioValidationConfig.DURATION_TOLERANCE_MS` (1.500 ms). Si existe
`sourceHash.sha256`, calcula SHA-256 del URI en `Dispatchers.IO`; una discrepancia
impide iniciar la experiencia. Sin hash se permite la coincidencia de duración y el
estado `NOT_AVAILABLE` queda disponible para debug.

## Arquitectura

Teleo utiliza Compose y navegación manual con `Screen`. Las funciones históricas permanecen en `MainActivity` y conservan su diseño horizontal. Teleo Música abre `MusicActivity`, una actividad interna dedicada y bloqueada en retrato para ofrecer una escena vertical más alta sin rotar ni reiniciar las demás experiencias. Toda la implementación nueva vive bajo `com.nicolas.teleo.features.music`:

- `domain`: modelos validados, contratos y lógica de consulta temporal.
- `data`: analizador simulado, resolución de metadatos del URI, JSON y repositorio de timelines.
- `playback`: contrato de reproducción e implementación Media3 ExoPlayer.
- `haptics`: contrato háptico e implementación con las APIs oficiales de Android.
- `visual`: interpolador musical, escenas, emisores, pool y motor de partículas determinista.
- `ui`: pantallas Compose de selección, preparación, cuenta regresiva y reproducción.
- `MusicExperienceViewModel`: estado central y coordinación de análisis, caché, reproducción, búfer, visualización y háptica.

Esta separación permite extraer la funcionalidad a un módulo Gradle propio más adelante sin migrar el resto de Teleo.

## Dependencias agregadas

- `androidx.media3:media3-exoplayer:1.5.1` para reproducir el URI seleccionado. Se mantiene esta versión compatible con `compileSdk 35`; las versiones actuales de Media3 exigen elevar el SDK de compilación.
- `androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7` para conservar y exponer el estado del feature.
- `androidx.graphics:graphics-shapes:1.1.0` para construir siluetas orgánicas compatibles y producir transiciones vectoriales con `RoundedPolygon` y `Morph` dentro de Compose Canvas.

Coroutines y Lifecycle ya formaban parte de las dependencias transitivas/actuales. El JSON usa `org.json`, igual que el código existente, por lo que no se agregó una biblioteca de serialización. No se agregó Media3 Session porque esta etapa no reproduce fuera de la actividad ni publica controles del sistema. Tampoco se agregó WorkManager: el análisis simulado es corto y se cancela junto con el ViewModel.

No se agregó `graphics-path`: `graphics-shapes` ya convierte el resultado del morph a un `android.graphics.Path`, que Compose puede dibujar directamente, y el laboratorio no inspecciona segmentos. Tampoco se incorporaron Rive, Lottie, libGDX, motores de juegos ni sistemas de partículas externos.

## Flujo de estados

1. `Idle`: no hay canción seleccionada.
2. `TrackSelected`: se resolvieron nombre, duración, artista cuando está disponible y un hash estable de metadatos.
3. `Analyzing`: `MockMusicAnalyzer` publica etapa, porcentaje y milisegundos preparados.
4. `Countdown`: muestra de forma visible y accesible 3, 2 y 1.
5. `Playing`: ExoPlayer reproduce el URI y su posición determina carriles, letra y pulsos.
6. `Error`: informa el problema y permite reintentar cuando corresponde.

Al cancelar o salir se cancelan las corrutinas, se pausa la reproducción y se detiene inmediatamente toda vibración.

## Formato de `MusicTimeline`

Cada archivo JSON interno incluye:

- `trackId`, `durationMs`, `bpm` y `analysisVersion`.
- `events`, ordenados por `timestampMs`, con duración, tipo, intensidad entre 0 y 1 y etiqueta opcional.
- `lyrics`, ordenadas por inicio, con texto original, idioma, traducciones, palabras temporizadas opcionales y marca de traducción personalizada.
- `featureFrames`, cada 250 ms en el mock, con fase/fuerza de pulso, energía por bandas, presencia vocal, altura melódica, brillo espectral, energía general y sección.

Los constructores rechazan tiempos negativos, intensidades fuera de rango y listas desordenadas. El repositorio escribe en `filesDir/music_timelines/<hash>.json`. Nunca copia ni almacena el audio.

Para este prototipo el hash combina URI persistente, nombre, tamaño y duración mediante SHA-256. Es estable para el mismo documento y evita leer completamente archivos musicales grandes en el hilo de selección. Una etapa posterior puede calcular el hash del contenido durante el análisis real.

## Búfer y sincronización

Los valores iniciales son:

- búfer inicial: 10 segundos;
- margen seguro: 5 segundos;
- ventana de análisis: 12 segundos;
- solapamiento: 4 segundos.

El analizador genera el timeline simulado y entrega progreso inicial. Tras la cuenta regresiva, una tarea representa el avance de ventanas solapadas y actualiza `bufferedUntilMs`. Si la distancia preparada baja de cinco segundos se muestra un estado de recuperación, sin cerrar ni bloquear la experiencia.

La única fuente de verdad temporal es `ExoPlayer.currentPosition`. Una lectura periódica publica esa posición; no existe un temporizador que avance una posición musical independiente. Pausar, reiniciar o buscar cambia ExoPlayer y, por esa misma posición, se resincronizan carriles, letra y háptica. El ajuste manual se limita a -250…+250 ms.

## Motor visual generativo

El motor visual no abre ni analiza archivos. Consume solamente `MusicTimeline`, `MusicFeatureFrame`, eventos activos y la posición de ExoPlayer. Sus responsabilidades están separadas así:

1. `LinearMusicFeatureInterpolator` obtiene características fluidas entre frames precalculados.
2. `DeterministicMusicVisualEngine` actualiza física, emisores, límites y métricas simples.
3. `MusicParticlePool` reutiliza objetos y evita asignaciones masivas durante la animación.
4. `MusicVisualScene` define entrada, actualización, renderizado y salida sin depender de Compose.
5. `GenerativeMusicCanvas` adapta el resultado al Canvas acelerado de Compose.

El ciclo de cada fotograma usa `withFrameNanos`, pero no convierte ese tiempo en una posición musical. En cada frame consulta `ExoPlayer.currentPosition`, interpola características en esa posición, obtiene eventos cruzados desde el frame anterior, actualiza el motor y dibuja. El delta de frame sólo se utiliza para física visual. Al pausar, el ciclo se suspende; al buscar o cambiar de canción, el motor se reconstruye con la posición real y eventos recientes.

## Voice Visual Lab

`Voice Visual Lab` es un laboratorio interno, vertical y completamente simulado para validar el futuro lenguaje visual de la voz sin abrir el micrófono ni analizar una canción. Se abre desde la pantalla inicial de Teleo Música con **Abrir Voice Visual Lab**. Puede utilizarse sin seleccionar un archivo.

La arquitectura mantiene tres responsabilidades separadas:

1. `VoiceVisualFrame`, `VowelProbabilities`, `VoiceVisualSettings` y los parámetros de suavizado/tuning forman la entrada normalizada, sin dependencias de Media3.
2. `DefaultVoiceVisualRenderer` filtra presencia, intensidad, tono, vibrato y probabilidades; resuelve el morph; actualiza posición, escala, opacidad y partículas. También expone `reset()` para limpiar la escena.
3. `VoiceBlobCanvas` usa el reloj de frames de Compose y dibuja únicamente el estado preparado. El ciclo se ejecuta con `repeatOnLifecycle(STARTED)`, se detiene fuera de pantalla y limpia el renderer al salir.

### Lenguaje de vocales y morphing

Todas las vocales usan 20 vértices con el mismo orden para conservar compatibilidad geométrica:

- **A**: ancha, abierta y con expansión lateral.
- **E**: horizontal y con ondulación moderada.
- **I**: estrecha y vertical.
- **O**: redondeada, profunda y simétrica.
- **U**: compacta y más cerrada arriba.
- **Neutral/UNKNOWN**: círculo orgánico estable.

`VoiceShapeLibrary` construye cada objetivo como `RoundedPolygon` y cachea cada par de `Morph`. El renderer normaliza las seis probabilidades, suaviza sus cambios, identifica las dos más fuertes y calcula la proporción relativa antes de pedir el path intermedio. De este modo no se fuerza a `Morph` a mezclar más geometrías que las admitidas por su contrato.

La presencia controla opacidad y aparición; la intensidad controla escala, grosor, luminosidad y emisión; el tono desplaza moderadamente la figura en vertical; el vibrato ondula un contorno continuo; y un ataque o aumento abrupto de intensidad dispara partículas desde el borde. Los multiplicadores viven en `VoiceVisualTuning` y `VoiceVisualSettings`, no en la pantalla de controles.

### Suavizado, partículas y accesibilidad

El filtro exponencial usa ataque más rápido que relajación para responder a una entrada y volver gradualmente al punto central al desaparecer la voz. También amortigua la posición, escala y probabilidades de vocales. El vibrato suma una onda continua dependiente del tiempo, sin ruido aleatorio entre frames.

El motor propio de partículas utiliza pool y reciclado, delta limitado a 50 ms y una semilla fija. Los topes son 40 en `LOW`, 100 en `MEDIUM`/`AUTO` y 200 en `HIGH`. Movimiento reducido conserva la lectura de intensidad, pero reduce al 22 % el desplazamiento/deformación y limita las partículas al 35 % de la calidad elegida. Desactivar partículas las devuelve inmediatamente al pool.

La pantalla ofrece sliders de presencia, intensidad, tono, vibrato y ataque; vocal dominante, segunda vocal y mezcla; calidad; partículas; movimiento reducido; y destellos permitidos. Incluye Silencio, Voz suave, Voz intensa, A/E/I/O/U, Vibrato, Ataque fuerte y una secuencia automática que interpola estados para inspeccionar continuidad.

La ruta futura queda preparada así: `MusicTimeline → MusicFeatureInterpolator → VoiceVisualFrame → VoiceVisualRenderer`. En esta etapa no hay detección real de vocales, MediaPipe, TensorFlow/LiteRT, separación de voz, Demucs, micrófono ni conexión con la reproducción.

### Renderizado de alta resolución

El Canvas utiliza el tamaño físico que Android asigna a la superficie: en una pantalla 4K dibuja a 4K, y en paneles menores usa su resolución nativa. No se rasterizan fondos a una resolución fija ni se escalan bitmaps. Ondas, anillos, fragmentos, diamantes, triángulos, halos y tipografía se dibujan vectorialmente, por lo que permanecen nítidos a cualquier densidad.

No se agregó una librería externa de partículas. En esta etapa un motor propio es más controlable, determinista y accesible, y evita incorporar una dependencia cuyo comportamiento de memoria o destellos no pueda limitar Teleo. Las APIs gratuitas usadas son las que ya incluye Jetpack Compose.

### Partículas y emisores

Los límites son 80 en calidad baja, 180 en media y 350 en alta. `AUTO` comienza en alta y baja progresivamente si acumula frames por encima de 22 ms. Cada partícula usa coordenadas normalizadas, velocidad, aceleración, tamaño, rotación, vida, opacidad, forma y rol semántico.

- Bombo: expansión radial con anillos grandes.
- Redoblante: fragmentos triangulares y lineales hacia ambos lados.
- Hi-hat: diamantes breves; se omiten si los destellos están desactivados.
- Bajo: puntos de onda amplios y lentos, acompañados por dos curvas continuas.
- Voz: aura de partículas alrededor de la zona tipográfica.
- Melodía: trayectoria que cambia también en altura, no solamente en color.

### Lenguaje sinestésico por instrumento

El preset artístico principal es `SYNESTHETIC`. La composición vertical utiliza posiciones que no cambian entre canciones para que el lenguaje pueda aprenderse:

- voz: forma orgánica blanda en el centro superior, deformada por presencia vocal;
- bombo: pulso circular en el centro inferior;
- redoblante: línea vertical que desciende desde la zona superior y se fragmenta al impactar;
- hi-hat: chispa cruciforme breve en la periferia superior, desactivable;
- bajo: cuerda/onda gruesa y lenta en la zona inferior;
- guitarra: cuerda fina de vibración rápida en la zona media;
- piano: línea segmentada en ocho rangos en la parte superior, con una activación cuya posición representa el tono.

La forma, posición, grosor y velocidad identifican cada instrumento incluso sin color. Como el analizador todavía no separa guitarra y piano, ambos se alimentan provisionalmente de la característica melódica simulada, aunque pueden ocultarse de manera independiente.

La semilla combina hash de canción, versión de análisis, preset, timestamp y tipo de evento. Dos motores con la misma entrada producen la misma composición. Un seek reconstruye eventos recientes con esa misma semilla.

### Presets

- `SYNESTHETIC`: escena artística principal con identidad espacial propia para voz, ritmo, bajo, guitarra y piano.
- `PARTICLES`: motor de partículas disponible internamente para experimentación.
- `LANES`: conserva los cuatro carriles accesibles del prototipo.
- `MINIMAL`: fondo, una onda y pulso con movimiento reducido.
- `WAVES` e `IMMERSIVE`: contratos preparados para escenas futuras; todavía no aparecen como opciones públicas.

## Letras y traducción

`TimedLyricLine` conserva texto original, idioma, traducciones y palabras opcionales. La búsqueda de línea y palabra usa la posición ajustada de ExoPlayer. La demostración contiene texto original genérico en inglés y traducción simulada al español, sin material protegido.

Durante la reproducción se puede elegir original, español, ambos u ocultar. En modo dual el original conserva mayor peso visual. Cuando existen palabras temporizadas, la palabra actual cambia también de color y peso. `Letra estable` elimina la escala reactiva; el tamaño puede ajustarse entre 80 % y 150 %.

Las interfaces `LyricsSource`, `LyricsTranslator` y `LyricsTranslationRepository` están separadas. Se incluyen:

- parser e implementación de fuente `.lrc`;
- fuente de texto manual sin sincronización;
- fuente de demostración;
- traductor simulado inglés → español;
- caché en memoria diferenciada por hash, versión de letra, idiomas, proveedor y versión del traductor.

La importación LRC/manual todavía no tiene selector en la pantalla. `MlKitLyricsTranslator` no fue agregado: antes hay que validar versión, descarga bajo demanda, políticas de red y almacenamiento de modelos. Una futura fuente remota deberá ser licenciada; no se implementará scraping ni una API no oficial.

## Accesibilidad visual

Los controles permiten:

- activar movimiento reducido;
- desactivar destellos;
- limitar por defecto cambios bruscos de luminosidad;
- usar letra estable;
- cambiar tamaño y modo de letra;
- elegir carriles o escena minimalista;
- ocultar la letra, las partículas o instrumentos individuales;
- pausar inmediatamente animación y vibración.

No se producen flashes repetitivos de pantalla completa. Los instrumentos se diferencian mediante forma, trayectoria, posición y etiqueta además de color.

## Rendimiento

- El Canvas renderiza solamente la subárea visual; los objetos de partículas viven fuera de los composables.
- Los `Path` principales se reutilizan.
- El pool recicla partículas expiradas.
- El render se suspende al pausar o cuando el composable deja de estar activo.
- El delta se limita a 50 ms para impedir saltos físicos tras una pausa del sistema.
- La calidad automática registra tiempo medio y cantidad de frames lentos y reduce el máximo de partículas.
- Archivo, traducción, caché y análisis permanecen fuera del bucle de dibujo.
- Voice Visual Lab reutiliza los `Path` y la matriz de transformación, cachea morphs y limita el pool a 40/100/200 objetos según calidad.
- El laboratorio no mantiene un loop activo cuando su lifecycle baja de `STARTED`; `reset()` recicla todas las partículas al navegar hacia atrás.

Las cifras reales de FPS/jank deben medirse en builds release sobre dispositivos 1080p y 4K de gama media. El modo `HIGH · 4K` describe la calidad máxima y el render nativo; no puede convertir físicamente una pantalla de menor resolución en 4K.

## Háptica

`AndroidHapticMusicEngine` comprueba la existencia del vibrador y el control de amplitud. Usa `VibrationEffect` con pulsos limitados:

- bombo: pulso fuerte y breve;
- redoblante: dos pulsos cortos;
- hi-hat: pulso mínimo (desactivado por defecto);
- bajo: pulso algo más largo (desactivado por defecto);
- inicio de sección: tres pulsos diferenciados.

Cuando no hay control de amplitud se usan solamente duraciones. El usuario puede desactivar toda vibración y elegir intensidad suave, media o fuerte. Pausar, buscar, salir o desactivar la opción ejecuta `cancel()` inmediatamente. El permiso `android.permission.VIBRATE` ya estaba declarado y no requiere solicitud en tiempo de ejecución.

## Ejecutar la demostración

1. Abrir Teleo y elegir la tarjeta **Teleo Música — Experimental**.
2. Para probar la voz sin audio, pulsar **Abrir Voice Visual Lab** y usar sus sliders o demos. No requiere seleccionar una canción.
3. Para continuar con la experiencia musical, pulsar **Seleccionar canción** y elegir un documento de audio local mediante el selector del sistema.
4. Configurar vibraciones e intensidad.
5. Pulsar **Preparar experiencia**.
6. Ver el progreso, la cuenta regresiva y la escena de partículas inicial.
7. Alternar entre **Sinestesia**, **Carriles** y **Minimal**.
8. Probar calidad Auto/Baja/Media/Alta · 4K, movimiento reducido, destellos y letra estable.
9. Cambiar la letra entre original, español, ambas y oculta.
10. Probar reproducir/pausar, buscar, reiniciar, vibración y ajuste de sincronía.
11. Volver a elegir la misma canción para comprobar la carga rápida desde caché.

Teleo Música siempre se abre en orientación vertical e inmersiva. Al volver, la pantalla principal recupera su orientación horizontal habitual.

Para compilar y probar desde consola:

```bash
./gradlew assembleDebug testDebugUnitTest
./gradlew compileDebugAndroidTestKotlin
```

Las pruebas instrumentadas Compose requieren un emulador o dispositivo:

```bash
./gradlew connectedDebugAndroidTest
```

## Limitaciones actuales

- Ritmo, energías, instrumentos, secciones, letra, traducción y avance del análisis son simulados.
- El BPM por defecto es 112 y no se extrae del audio.
- La letra es texto bilingüe genérico creado para la demostración; no se importan letras protegidas.
- El parser LRC, texto manual y caché por idioma están implementados como infraestructura, pero aún no tienen flujo de importación visible.
- `WAVES` e `IMMERSIVE` están modelados pero todavía comparten/esperan una escena dedicada.
- La reducción automática se basa en delta de frame; falta instrumentación de campo con JankStats y macrobenchmarks.
- El audio no se decodifica para análisis y no existe separación de fuentes.
- El indicador de búfer representa el futuro procesamiento por ventanas, no el búfer de red de ExoPlayer.
- No hay sesión multimedia, reproducción en segundo plano, WorkManager, servidor ni IA.
- No hay todavía integración Bluetooth con un dispositivo háptico externo.
- Voice Visual Lab usa controles y secuencias simuladas: todavía no recibe presencia, intensidad, tono, vibrato, ataques ni probabilidades de vocales desde audio real.

## Próximas etapas

1. Analizador real de ritmo y energía.
2. Extracción real de golpes de batería.
3. Importación de letras sincronizadas.
4. Separación de instrumentos.
5. Procesamiento mediante ventanas solapadas.
6. Perfil personalizado para cada usuario.
7. Conexión Bluetooth con Teleo Pad.
8. Pruebas de experiencia con usuarios.
9. Exportación e importación de timelines.
10. Procesamiento opcional en computadora o servidor.

El siguiente incremento debe usar un JSON real producido por Kinetra Resonance y
verificar visualmente que los eventos, el visema y la háptica cambian al hacer seek.
El análisis PCM local propuesto anteriormente ya no es el siguiente paso de producto:
el análisis pertenece a Kinetra, no al teléfono.

## READY FOR VPS TEST

Subir contenido público estático, sin API ni autenticación:

```text
<document-root>/catalog.json
<document-root>/tracks/<track-id>/experience.json
```

En el teléfono/build local configurar:

```properties
TELEO_MUSIC_BASE_URL=https://music.example.com/
```

Usar para la primera prueba un `experience.json` real de Kinetra con el contrato v1
descrito arriba: `format: teleo-music`, `version: 1`, track ID/duración idénticos a
`catalog.json`, eventos de batería, `haptics` y, si está disponible,
`vocals.visemes`. Si Kinetra produce SHA-256, publicar el mismo `sourceHash` en
catálogo o track de experiencia; debe corresponder al archivo de audio que se elija
localmente.

Prueba desde el teléfono:

1. Recompilar tras modificar `local.properties` e instalar el APK debug.
2. Abrir **Teleo Música** y pulsar **Actualizar**.
3. Elegir la card remota, esperar la descarga y seleccionar el audio local exacto.
4. Confirmar **Preparar experiencia** y reproducir.
5. En debug, comprobar `pos`, `event`, `visema` y `háptica`; hacer seek hacia
   adelante y atrás y confirmar que se actualizan de inmediato.
6. Cerrar la app, desconectar Internet y repetir: debe indicar caché/offline y
   seguir permitiendo la experiencia ya descargada.
