#!/bin/bash

# Script para compilar e instalar la app Teleo en el dispositivo conectado por USB

# Salir si hay errores
set -e

echo "🚀 Iniciando proceso de despliegue para Teleo..."

# 1. Compilar el APK (Debug)
echo "📦 Compilando APK..."
./gradlew assembleDebug

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
