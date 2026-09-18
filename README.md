# Velocímetro nativo para Android

Aplicación Kotlin/Jetpack Compose para Android 11+ (API 30), concebida para un registro de velocidad de bajo consumo y sin servicios de Google obligatorios.

## Decisiones iniciales

- **GPS del sistema + servicio foreground**: registra una ruta incluso cuando la interfaz no está visible. Los callbacks y las escrituras locales corren en un hilo dedicado; se descartan lecturas imprecisas y saltos GPS.
- **SQLite directo**: rutas y puntos quedan en el dispositivo y no se incluyen en backups. Los checkpoints permiten recuperar una ruta que quedó interrumpida por el sistema.
- **MapLibre Native + OpenFreeMap/OpenStreetMap**: renderizado de mapa nativo y proveedor intercambiable. Para producción se debe revisar la política/cuota del proveedor de teselas elegido; no se usan las teselas públicas de OSM como infraestructura de producción.
- **Jetpack Compose y Material 3**: tema claro/oscuro, medidor personalizable y UI sin una capa web.
- **Capas explícitas**: `domain` contiene modelos, contratos y casos de uso; `data` implementa almacenamiento; `tracking` encapsula Android/GPS; `ui` contiene presentación. El `AppContainer` entrega las dependencias sin una biblioteca DI adicional.

El registro de recorridos es local. Al mostrar el mapa, sí se realiza una petición al proveedor de estilo/teselas para el área visible; la aplicación acredita el origen y el proveedor puede sustituirse antes de publicar.

## Abrir y ejecutar

1. Abrir esta carpeta con Android Studio reciente (JDK 21).
2. Instalar Android SDK Platform 37 y aceptar la sincronización de Gradle.
3. Ejecutar en un dispositivo/emulador con Android 11 o superior. En un dispositivo real, conceder ubicación precisa y notificaciones.

La primera versión tiene: medición actual, iniciar/finalizar ruta, persistencia de puntos, historial con máximo/promedio/duración/distancia, promedio histórico de velocidad, promedio semanal de kilómetros recorridos, tema y ajustes de los módulos del panel. La siguiente etapa natural es una pantalla de detalle con el trazo completo y exportación GPX.
