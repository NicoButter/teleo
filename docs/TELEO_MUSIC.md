# Teleo Música

## Objetivo

Teleo Música es un prototipo experimental para representar una canción mediante imagen y tacto. Está pensado para que una persona sorda o con hipoacusia pueda seguir ritmo, secciones, voz, melodía, bajo y batería sin que un estado importante dependa exclusivamente del audio.

La experiencia no interactúa con implantes cocleares, no programa dispositivos médicos y no se presenta como tratamiento ni como recuperación de la audición.

## Arquitectura

Teleo es hoy una aplicación de actividad única, Compose y navegación manual con `Screen`. Para respetar ese diseño, la entrada a Música se agregó al mismo flujo, pero toda la implementación nueva vive bajo `com.nicolas.teleo.features.music`:

- `domain`: modelos validados, contratos y lógica de consulta temporal.
- `data`: analizador simulado, resolución de metadatos del URI, JSON y repositorio de timelines.
- `playback`: contrato de reproducción e implementación Media3 ExoPlayer.
- `haptics`: contrato háptico e implementación con las APIs oficiales de Android.
- `ui`: pantallas Compose de selección, preparación, cuenta regresiva y reproducción.
- `MusicExperienceViewModel`: estado central y coordinación de análisis, caché, reproducción, búfer y háptica.

Esta separación permite extraer la funcionalidad a un módulo Gradle propio más adelante sin migrar el resto de Teleo.

## Dependencias agregadas

- `androidx.media3:media3-exoplayer:1.5.1` para reproducir el URI seleccionado. Se mantiene esta versión compatible con `compileSdk 35`; las versiones actuales de Media3 exigen elevar el SDK de compilación.
- `androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7` para conservar y exponer el estado del feature.

Coroutines y Lifecycle ya formaban parte de las dependencias transitivas/actuales. El JSON usa `org.json`, igual que el código existente, por lo que no se agregó una biblioteca de serialización. No se agregó Media3 Session porque esta etapa no reproduce fuera de la actividad ni publica controles del sistema. Tampoco se agregó WorkManager: el análisis simulado es corto y se cancela junto con el ViewModel.

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
- `lyrics`, ordenadas por inicio, con intervalo y texto.

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
2. Pulsar **Seleccionar canción** y elegir un documento de audio local mediante el selector del sistema.
3. Configurar vibraciones e intensidad.
4. Pulsar **Preparar experiencia**.
5. Ver el progreso, la cuenta regresiva y luego los cuatro carriles.
6. Probar reproducir/pausar, buscar en la barra, reiniciar, apagar vibraciones y ajustar sincronía.
7. Volver a elegir la misma canción para comprobar la carga rápida desde caché.

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

- Ritmo, instrumentos, secciones, letra y avance del análisis son simulados.
- El BPM por defecto es 112 y no se extrae del audio.
- La letra es texto genérico creado para la demostración; no se importan letras protegidas.
- El audio no se decodifica para análisis y no existe separación de fuentes.
- El indicador de búfer representa el futuro procesamiento por ventanas, no el búfer de red de ExoPlayer.
- No hay sesión multimedia, reproducción en segundo plano, WorkManager, servidor ni IA.
- No hay todavía integración Bluetooth con un dispositivo háptico externo.

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

El siguiente paso técnico más pequeño es decodificar PCM mono en una ventana corta con MediaExtractor/MediaCodec, calcular energía RMS por bloques y aplicar detección de picos con umbral adaptativo. Eso permitiría reemplazar únicamente los golpes periódicos del mock por candidatos de pulso reales, manteniendo intactos el modelo, caché, búfer, UI, reproducción y motor háptico.
