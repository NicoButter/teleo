# Boca articulatoria y fondo de percusión

Incremento local del 14 de septiembre de 2026, sobre `6c35800`. No modifica
servidores Resonance, transporte/caché REMOTE, Kinetra, orientaciones ni reproducción.

## Arquitectura encontrada y decisión

El runtime ya tenía `TeleoExperienceSampler → TeleoVisualState → PremiumMusicScene
→ PremiumMusicRenderer` con Compose Canvas. La boca era un contorno exterior y
una cavidad separada; la intensidad se sumaba a la apertura, incluso en MBP.
Los eventos se recorrían linealmente y el Canvas no se remuestreaba al buscar en
pausa. Graphics Shapes 1.1.0 ya estaba integrado para las vocales orgánicas del lab.

Se conserva esa arquitectura y se separan tres responsabilidades:

1. **Muestreo:** `MouthArticulationTimeline` y `RhythmicBackgroundSampler` consultan
   listas indexadas. No integran tiempo ni analizan audio.
2. **Geometría:** `MouthPose`, `MouthGeometry` y `RhythmicPressureField`.
3. **Dibujo:** `ArticulatoryMouthRenderer` y `RhythmicBackgroundRenderer` tienen sus
   propios paths reutilizables; el compositor Premium controla el orden.

## Anatomía y poses

Los 15 controles normalizados son `jawOpen`, `lipOpen`, `lipWidth`, `lipRound`,
`lipPucker`, `lipSpread`, `lipClosure`, `lipPressure`, `upperTeethVisible`,
`lowerTeethVisible`, `lowerLipRaise`, `labiodentalContact`, `tongueVisible`,
`tongueRaise` y `tongueForward`. Las nueve poses base se crean una vez.

- **Labio superior:** superficie rellena con arco de Cupido y sombreado propio.
- **Labio inferior:** volumen independiente que asciende para FV.
- **Cavidad:** oscura, recorta todos los dientes y la lengua.
- **Incisivos superiores/inferiores:** piezas individuales, con visibilidad separada.
- **Lengua:** cuerpo, punta elevada/adelantada y surco central; nace en la cavidad.
- **Mandíbula:** soporte inferior y arco de mentón que acompaña la apertura.
- **Comisuras:** todos los bordes comienzan/terminan en los mismos dos anclajes.
  En cierre, el borde interno inferior reutiliza exactamente el superior, con
  una sola línea de cierre; no hay dos contornos paralelos de apertura.

| Código | Clave visual |
| --- | --- |
| A · MBP | Cierre completo, labios comprimidos, presión, sin dientes ni lengua. |
| B | Abertura pequeña y extendida, dientes superiores e inferiores. |
| C | Abertura media, mandíbula y cavidad visibles. |
| D | Apertura amplia y mandíbula descendida. |
| E | Redondeo abierto. |
| F · UW | Boca estrecha, pequeña apertura redonda, labios gruesos/fruncidos; protrusión representada por volumen 2D. |
| G · FV | Incisivos superiores expuestos hasta el labio inferior elevado; sin dientes inferiores. |
| H · L | Abertura parcial y punta de lengua elevada hacia los incisivos. |
| X · REST | Labios cerrados relajados, de mayor volumen que el cierre presionado A. |

## Coarticulación y sincronía

Se mezcla visualmente previous/current/next con smoothstep alrededor de las
fronteras. Cada semiventana es como máximo 35 ms y como máximo 1/5 de las duraciones
vecinas; las pausas se mezclan con reposo y limitan su ventana a media pausa.
Se preservan los timestamps, el código activo exacto y el centro completo de cada
pose, incluso en cues cortos. No se aplica luego un filtro temporal a la pose que
pueda impedir un MBP breve. Los cues extremadamente cortos pueden quedar entre
frames del dispositivo: no se inventa duración para mostrarlos.

La intensidad solo afecta un pequeño reflejo labial. El pitch se ignora en la
geometría. El fondo calcula ataque/decay y desplazamiento desde la edad del golpe
en milisegundos de reproducción. Consultar otra posición reconstruye las mismas
poses y deformaciones sin depender de los frames previos.

`GenerativeMusicCanvas` usa la fuente temporal existente; con audio siempre es
ExoPlayer. `withFrameNanos` sirve para pedir dibujo y suavizar señales ambientales,
no como reloj musical. La pausa y sus seeks generan un muestreo puntual. No se
cambió la háptica ni el reloj visual preexistente de LOCAL_IMPORT sin audio.

## Batería y composición

| Familia | Respuesta | Duración visual |
| --- | --- | --- |
| kick | Frente radial expansivo y pesado, centrado en la región inferior. | 800 ms |
| snare | Tensión ondulada breve y estrecha en la malla. | 260 ms |
| tom | Onda resonante lateral; lado determinista por timestamp. | 850 ms |
| cymbal | Halo/wash localizado y arco luminoso superior. | 650 ms |
| hi_hat | Cruz fina y breve en la periferia. | 150 ms |

La intensidad multiplica la envolvente; intensidad cero no emite señal. El ataque
visual dura 12 ms. No hay color aleatorio, flashes de pantalla completa ni datos
Mock agregados cuando faltan eventos. Los canales no confiables quedan sin golpes.

Se mantiene `kick/bass_drum`, `snare`, `hi_hat/hihat/hat`, `tom/toms` y
`cymbal/crash/ride`. Se toleran guiones, mayúsculas y variantes explícitas:
`snare_drum/rimshot/side_stick`, `open_hi_hat/closed_hi_hat/hi_hat_open/hi_hat_closed/
pedal_hi_hat`, `low_tom/mid_tom/high_tom/floor_tom`, `cymbals/splash/crash_cymbal/
ride_cymbal/china`. Valores desconocidos siguen siendo OTHER.

Se corrigió la conversión de `drums.events` al timeline: antes `toEvent` podía
sobrescribir tom/cymbal con OTHER; ahora comparte el mapeo de familia del dominio.

El fondo mantiene bajo contraste y la boca tiene una zona oscura de lectura.
Premium no dibuja encima las partículas genéricas del motor anterior. Ocultar
RHYTHM oculta la malla; ocultar VOICE oculta la boca. El control de partículas sigue
operando en las otras escenas. Movimiento reducido atenúa la deformación al 18 %
y reduce líneas; desactivar destellos conserva una señal periférica muy tenue.

## Rendimiento

- Cinco índices de batería, búsqueda binaria por familia; empate de timestamp usa
  la intensidad máxima para esa familia.
- Solo se conserva el último golpe de cada familia. Un redoble reinicia su onda;
  no se acumulan ondas ilimitadas.
- Malla fija 33 × 23: dos FloatArray reutilizados y un Path. Calidad baja y
  movimiento reducido dibujan líneas alternas.
- Poses cacheadas, mezcla escalar y paths/brushes de boca reutilizados.
- El sampler de impulsos activos usa búsqueda binaria y un máximo acumulado de
  finales para conservar solapamientos; eventos cruzados usan sublistas binarias.
- Premium no ejecuta el motor genérico ni sus búsquedas lineales por frame.

## Evaluación de librerías

No se agregaron dependencias ni se consultaron servicios/remotos. La evaluación
se limita al stack y documentación disponibles localmente.

- **Compose Canvas:** ya resuelve capas Bézier, clipping, gradientes y mallas con
  control directo de dientes/lengua. Suficiente para el incremento.
- **AndroidX Graphics Shapes (open source, ya instalado):** útil para el morph de
  siluetas del lab original. Una silueta morph no resuelve por sí sola el contacto
  entre dientes, lengua y labios; aquí se necesitan capas con anclajes comunes.
- **AndroidX graphics-path:** candidato a un spike futuro si hiciera falta medir o
  inspeccionar segmentos. Hoy no aporta una capacidad necesaria al renderer.
- **Rive/Lottie:** no se adoptan. Un recurso de animación/rig externo añadiría otro
  flujo de autoría y runtime sin resolver la necesidad actual de poses y contacto
  parametrizados a partir del JSON. No se hizo benchmark ni comparación de versiones.

## Lab y revisión reproducible

Abrir **Teleo Música → Abrir Voice Visual Lab → Boca**. Elegir A/F/G/H/X, luego
B/C/D/E. El slider de intensidad debe conservar el cierre/contacto. El panel muestra
el código fijo, su clave visual y cuatro parámetros; utiliza exactamente el renderer
de producción. El overlay de reproducción conserva el visema real activo.

Validación mínima:

```bash
./gradlew testDebugUnitTest compileDebugAndroidTestKotlin --no-configuration-cache
```

Con dispositivo o emulador operativo:

```bash
./gradlew connectedDebugAndroidTest --no-configuration-cache \
  -Pandroid.testInstrumentationRunnerArguments.class=com.nicolas.teleo.features.music.ArticulatoryRendererTest
```

La prueba instrumentada recorre el lab, compara capturas antes/después de seeks en
pausa y exporta las nueve poses y cinco familias desde Canvas real a
`Android/data/com.nicolas.teleo/files/visual-review/` en el dispositivo.
Las imágenes se deben inspeccionar visualmente, no solo generar.

### Resultado de esta sesión

- `testDebugUnitTest`: 64 pruebas aprobadas, cero fallos.
- `compileDebugAndroidTestKotlin`: completado.
- `assembleDebug`: APK generado en `app/build/outputs/apk/debug/app-debug.apk`.
- `connectedDebugAndroidTest`: no ejecutó pruebas, `No connected devices`.
- Se intentó un AVD temporal con la imagen API 36 instalada, con SwiftShader,
  sin Vulkan y sin aceleración de CPU. Los tres arranques terminaron con código
  139 antes de disponer de Android. No se generaron ni inspeccionaron capturas.
- La distinción visual de las cuatro poses clave sigue pendiente de aprobación
  visual en un dispositivo operativo; las pruebas geométricas no la sustituyen.

## Limitaciones y siguiente mejora

El diseño es una boca frontal estilizada 2D, sin mejillas, rostro completo,
profundidad real ni calibración individual. Rhubarb agrupa sonidos: no ofrece
identidad fonética exacta ni validación de comprensión por lectura labial.
La punta de lengua y la protrusión son aproximaciones gráficas. La calidad y
alineación del JSON externo siguen limitando la articulación.

El siguiente paso es revisar sin audio A/F/G/H/X con personas que utilizan lectura
labial, ajustar geometría/contactos con sus observaciones y medir fluidez en un
Android físico de gama media. No se propone reemplazar el stack visual.
