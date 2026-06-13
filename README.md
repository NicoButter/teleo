<p align="center">
  <img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" alt="Teleo Logo" width="128">
</p>

# Teleo

Teleo es una aplicación enfocada en la comunicación en tiempo real, facilitando la interacción mediante tecnología de reconocimiento de voz y conectividad de proximidad.

## Características Principales

- **Palabra Viva**: Visualización en tiempo real de lo que se está hablando.
- **Escribir y Mostrar**: Interfaz simple para escribir mensajes y mostrarlos en pantalla completa.
- **TeleoCerca**: Comunicación con dispositivos cercanos utilizando Google Nearby Connections.
- **Reconocimiento de Voz**: Soporte para dictado y transcripción inmediata.

## Capturas de Pantalla

<p align="center">
  <img src="screenshots/home.png" width="30%" alt="Inicio">
  <img src="screenshots/chat.png" width="30%" alt="Chat">
  <img src="screenshots/voice.png" width="30%" alt="Voz">
</p>

## Tecnologías Utilizadas

- **Kotlin** y **Jetpack Compose** para una interfaz moderna y reactiva.
- **Google Nearby Connections API** para comunicación offline y local.
- **Android Speech Recognition** para la captura de texto desde audio.

## Requisitos

- Android SDK 35
- Dispositivo con soporte para Bluetooth y Wi-Fi (para Nearby Connections)
- Permisos de Micrófono y Ubicación

## Instalación y Configuración

1. Clonar el repositorio:
   ```bash
   git clone https://github.com/nicobutter/teleo.git
   ```
2. Abrir el proyecto en Android Studio.
3. Asegurarse de tener configurado el SDK de Android.
4. Ejecutar el proyecto en un emulador o dispositivo físico.

## Configuración de Entorno (.env)

El proyecto utiliza un archivo `.env` para gestionar variables sensibles. Asegúrate de crear uno basado en el ejemplo proporcionado.

## Contacto

Desarrollado por **Nicolas Butterfield**  
- Email: [nicobutter@gmail.com](mailto:nicobutter@gmail.com)  
- GitHub: [@nicobutter](https://github.com/nicobutter)

---
Desarrollado para mejorar la comunicación.
