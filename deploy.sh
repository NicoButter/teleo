#!/bin/bash

# Script para compilar e instalar la app Teleo en el dispositivo conectado por USB

# Salir si hay errores
set -e

resolve_java_home() {
    if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ] && [ -x "$JAVA_HOME/bin/jlink" ]; then
        printf '%s\n' "$JAVA_HOME"
        return 0
    fi

    for candidate in \
        "$HOME/.gradle/jdks/eclipse_adoptium-21-amd64-linux.2" \
        "$HOME/.gradle/jdks/"* \
        "/opt/android-studio/jbr" \
        "$HOME/android-studio/jbr"
    do
        if [ -d "$candidate" ] && [ -x "$candidate/bin/java" ] && [ -x "$candidate/bin/jlink" ]; then
            printf '%s\n' "$candidate"
            return 0
        fi
    done

    return 1
}

echo "🚀 Iniciando proceso de despliegue para Teleo..."

if JAVA_HOME_RESOLVED=$(resolve_java_home); then
    export JAVA_HOME="$JAVA_HOME_RESOLVED"
    export PATH="$JAVA_HOME/bin:$PATH"
    echo "☕ Usando JDK: $JAVA_HOME"
else
    echo "❌ Error: no se encontró un JDK completo con 'jlink'."
    echo "Instalá un JDK 21 completo o Android Studio, o definí JAVA_HOME a un JDK válido."
    exit 1
fi

# 1. Compilar el APK (Debug)
echo "📦 Compilando APK..."
./gradlew --no-configuration-cache assembleDebug

# 2. Encontrar el archivo APK generado
APK_PATH=$(find app/build/outputs/apk/debug -name "*.apk" | head -n 1)

if [ -z "$APK_PATH" ]; then
    echo "❌ Error: No se pudo encontrar el archivo APK."
    exit 1
fi

echo "✅ APK generado en: $APK_PATH"

# 3. Instalar en el dispositivo
# Intentar encontrar adb si no está en el PATH
if ! command -v adb &> /dev/null; then
    export PATH=$PATH:$HOME/Android/Sdk/platform-tools
fi

echo "📲 Intentando instalar en el dispositivo..."
if ! adb install -r -d -t "$APK_PATH"; then
    echo "⚠️ La instalación falló (posiblemente por conflicto de firmas)."
    echo "🔄 Intentando desinstalar la versión anterior e instalar de nuevo..."
    adb uninstall com.nicolas.teleo
    adb install -r -d -t "$APK_PATH"
fi

echo "🎉 ¡Listo! La aplicación ha sido instalada y está lista para usarse."
