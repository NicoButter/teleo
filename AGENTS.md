# Instrucciones de contexto para agentes

Antes de analizar, planificar o modificar Teleo, leer completo
[`docs/PROJECT_CONTEXT.md`](docs/PROJECT_CONTEXT.md). Es la fotografía general del
producto, su arquitectura, lo que funciona, lo que sigue simulado y cómo validar
cambios. Para trabajo dentro de Teleo Música, leer además
[`docs/TELEO_MUSIC.md`](docs/TELEO_MUSIC.md).

Reglas esenciales:

- Tratar el código, `AndroidManifest.xml`, Gradle y las pruebas como fuentes de
  verdad cuando contradigan la documentación.
- No describir Teleo Música como análisis de audio real: hoy utiliza
  `MockMusicAnalyzer`; BPM, instrumentos, letra, traducción y progreso son
  simulados.
- No presentar Teleo como dispositivo médico ni como tratamiento. La experiencia
  musical es visual/háptica y no interactúa con implantes cocleares.
- Mantener `MainActivity` en horizontal y `MusicActivity` en vertical salvo que el
  cambio de orientación sea un requisito explícito.
- No versionar `.env`, `local.properties`, claves, keystores ni datos personales.
- Para cambios Kotlin/Compose ejecutar, como mínimo,
  `./gradlew testDebugUnitTest compileDebugAndroidTestKotlin
  --no-configuration-cache`. Las pruebas instrumentadas requieren un dispositivo
  o emulador.
- Actualizar `docs/PROJECT_CONTEXT.md` cuando cambien capacidades, arquitectura,
  dependencias, limitaciones, persistencia o comandos de validación.

