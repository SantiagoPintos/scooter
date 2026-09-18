# Velocímetro nativo para Android

Aplicación Kotlin/Jetpack Compose para Android 11+ (API 30), concebida para un registro de velocidad de bajo consumo y sin servicios de Google obligatorios.

## Decisiones iniciales

- **GPS del sistema + servicio foreground**: registra una ruta incluso cuando la interfaz no está visible. Se descartan lecturas imprecisas y se evita el trabajo periódico innecesario.
- **SQLite directo**: rutas y puntos quedan en el dispositivo, sin red ni serialización adicional en el camino crítico.
- **MapLibre Native + OpenFreeMap/OpenStreetMap**: renderizado de mapa nativo y proveedor intercambiable. Para producción se debe revisar la política/cuota del proveedor de teselas elegido; no se usan las teselas públicas de OSM como infraestructura de producción.
- **Jetpack Compose y Material 3**: tema claro/oscuro, medidor personalizable y UI sin una capa web.

## Abrir y ejecutar

1. Abrir esta carpeta con Android Studio reciente (JDK 21).
2. Instalar Android SDK Platform 36 y aceptar la sincronización de Gradle.
3. Ejecutar en un dispositivo/emulador con Android 11 o superior. En un dispositivo real, conceder ubicación precisa y notificaciones.

La primera versión tiene: medición actual, iniciar/finalizar ruta, persistencia de puntos, historial con máximo/promedio/duración/distancia, promedio histórico de velocidad, promedio semanal de kilómetros recorridos, tema y ajustes de los módulos del panel. La siguiente etapa natural es una pantalla de detalle con el trazo completo y exportación GPX.
