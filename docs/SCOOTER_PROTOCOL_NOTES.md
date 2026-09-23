# Notas de protocolo — estado parcial

Esta nota describe únicamente estructura, tamaños y algoritmos. No contiene `ltmk`, claves de sesión, cargas BLE, tokens, credenciales ni dirección del scooter.

## Evidencia empleada

- Tres sesiones BLE locales de Xiaomi Home, sin Wi-Fi, con cargas dinámicas entre sesiones.
- APK Xiaomi Home 11.7.704 de prueba, revisado localmente.
- Conector de chip de seguridad: `securitychipauth` (clase ofuscada `OooOO0`), su lanzador `fw0` y la selección `securityChipConnect`.
- Plugin local del scooter `xiaomi.scooter.cross`, versión `1.1.11`, asociado al modelo Xiaomi Scooter 6 Max.

## Características observadas

| Característica | Propiedades observadas | Uso inferido | Evidencia |
|---|---|---|---|
| `00000016-0000-1000-8000-00805f9b34fb` | escritura sin respuesta + notificación | Arranque e intercambio de autenticación | HCI y conector de chip |
| `0000001a-0000-1000-8000-00805f9b34fb` | escritura durante sesión | Mensajes de aplicación tras autenticar | HCI |

En las sesiones registradas, la primera característica incluyó una carga dinámica de 66 bytes y una posterior de 10 bytes; la segunda transportó secuencias dinámicas de 13, 17, 20 y 23 bytes. Los tamaños y las huellas cambiaron por sesión. Esto descarta un replay estático.

## Flujo de inicio de sesión confirmado en código

1. El conector obtiene un `ltmk` de 32 bytes asociado al dispositivo. Si existe, toma la ruta `securityChipConnect`; si no, deriva hacia registro/recuperación.
2. Se suscribe a notificaciones y emite el control de inicio `20 00 00 00` hacia la característica de autenticación.
3. Xiaomi Home genera un par de claves EC efímero y serializa su clave pública. El código de transporte asociado es `3`.
4. El scooter entrega 64 bytes de clave pública. La app antepone `04` y la interpreta como un punto EC sin comprimir; esto es consistente con P-256.
5. La app calcula ECDH, toma sus primeros 32 bytes y concatena el `ltmk`:

   ```text
   ikm = ECDH_shared[0:32] || ltmk
   session_key = HKDF-SHA256(
       ikm,
       salt = "smartcfg-login-salt",
       info = "smartcfg-login-info",
       output_length = 64
   )
   ```

6. Como prueba de posesión, calcula el CRC de 32 bits de la clave pública de 64 bytes del scooter, serializado little-endian. Lo cifra con AES-CCM usando los bytes `16..31` de `session_key`, nonce fijo de 12 bytes (`10 11 ... 1b`), sin datos autenticados adicionales y etiqueta de 4 bytes. El resultado tiene 8 bytes (4 de texto cifrado + 4 de etiqueta) y se envía por el flujo de transporte `5`.
7. Una notificación con el estado `21 00 00 00` confirma la sesión y el cliente conserva la `session_key` para operaciones posteriores.

## Verificación en ejecución (2026-09-21)

Tras reiniciar Bluetooth y ejecutar un desbloqueo manual, el proceso principal de Xiaomi Home invocó el lector del `ltmk` y creó el lanzador `security_chip`.

- El `ltmk` persistente leído tiene 32 bytes.
- La entrada de 32 bytes del lanzador tiene una huella diferente: el código estático explica esto cuando aplica la transformación configurada por el dispositivo antes de iniciar la sesión. Un cliente futuro debe reproducir esa transformación o recibir el material ya preparado por un proveedor de secretos.
- Ningún valor de clave se ha escrito en este repositorio ni se incluye en esta nota.

Durante pruebas manuales de desbloqueo y bloqueo, el proceso principal emitió dos secuencias de aplicación sobre el handle GATT `0x0046` (UUID corto `0x001A`): una trama de 6 bytes y dos envíos de 20 bytes. Cada gesto genera una secuencia nueva. Esto delimita las órdenes posteriores a una sesión autenticada y descarta un replay estático.

Una sesión BLE nueva capturada en HCI confirmó el framing mínimo de la autenticación sobre el handle `0x003d` (UUID corto `0x0016`), sin conservar las cargas:

- El canal de autenticación usa indicaciones confirmadas en su descriptor de configuración del cliente; el transporte debe escoger indicación cuando la característica la anuncia, en vez de asumir notificaciones.
- Antes de la negociación P-256, el scooter emite dos envolturas de preparación A4 (etapas 0 y 1). Xiaomi Home confirma cada una como A5 sin modificar su carga y sólo después habilita el estado final de sesión.
- El inicio queda acotado a tres características del servicio: leer `0004`, enviar el control de preparación a `0010`, completar A4/A5 en `0016` y enviar el control de inicio de sesión en `0010`. No forma parte del canal de órdenes de aplicación.

1. La aplicación y el scooter intercambian tramas previas de 6 y 244 bytes.
2. La aplicación escribe una trama de 66 bytes, consistente con una clave pública efímera de 64 bytes más framing.
3. El scooter notifica una trama de 68 bytes, consistente con su clave pública efímera de 64 bytes más framing.
4. Tras los acuses intermedios, la aplicación escribe una trama de confirmación de 10 bytes.
5. Luego aparece tráfico de aplicación sobre `0x001A` y su canal asociado, delimitando el fin del establecimiento de sesión.

El canal añade un prefijo de secuencia de 16 bits little-endian a los fragmentos de datos salientes. Con el MTU negociado, la clave pública local de 64 bytes cabe en un único fragmento de 66 bytes. La confirmación AES-CCM de 8 bytes ocupa de la misma forma una trama de 10 bytes. El formato exacto del control de inicio, los acuses y el encabezado de la respuesta de 68 bytes sigue pendiente de nombrar, pero sus tamaños, orden y campo de secuencia ya están delimitados.

La preparación del dispositivo también usa una envoltura de 244 bytes, por lo que la autenticación exige negociar un MTU ATT de al menos 247 antes de suscribirse o enviar controles. Con el MTU predeterminado la primera preparación cabe, pero la segunda no llega a transmitirse.

## Framing del canal de seguridad

El código de transporte de Xiaomi Home confirma cuatro clases de paquetes, todos little-endian:

| Paquete | Estructura | Uso durante la autenticación |
|---|---|---|
| Control de flujo | `secuencia=0` + tipo `0` + tipo de paquete + cantidad de fragmentos (`u16`) | Inicia el envío de la clave pública local y de la confirmación. |
| Fragmento de datos | secuencia (`u16`, desde 1) + datos | Transporta la clave pública de 64 bytes o la confirmación de 8 bytes. |
| Acuse | `secuencia=0` + tipo `1` + estado + lista opcional de secuencias | Regula avance, reintento y sincronización. |
| Control de mensaje único | `secuencia=0` + tipo `2` + tipo de paquete + datos | La respuesta de 68 bytes del scooter porta la clave pública remota de 64 bytes. |

Para las escrituras originadas por el cliente, el código siempre usa control de flujo seguido de fragmentos. Los tipos de paquete confirmados son `3` para la clave pública local y `5` para la confirmación AES-CCM. El reensamblado y los acuses son responsabilidad de la capa de canal, antes de que el conector de seguridad procese el mensaje.

El registro HCI no contiene errores ATT asociados a esas escrituras; los errores ATT observados pertenecen al descubrimiento de atributos opcionales. Xiaomi Home sí informó una vez el resultado interno `-28`: el código decompilado lo emite cuando el callback de la cola local falla durante el paso 4. Dado que el enlace ATT continuó con la respuesta pública y la confirmación, no se interpreta como un rechazo criptográfico del scooter ni como fuente de verdad del estado de sesión.

## Operación semántica de bloqueo

El plugin del scooter obtiene el estado de bloqueo desde el servicio MiOT `siid=4`, propiedad booleana `piid=6`. Al alternarlo, envía una operación genérica de MiOT BLE Spec v2 de establecimiento de propiedad:

```text
setPropertiesValue({ siid: 4, piid: 6, type: BOOL, value: true  })  → bloqueo
setPropertiesValue({ siid: 4, piid: 6, type: BOOL, value: false })  → desbloqueo
```

La capa nativa la convierte en un paquete de establecimiento de propiedades y la encola en la sesión autenticada. Una traza de metadatos de 2026-09-21, iniciada con la orden manual `unlock` seguida de `lock`, confirmó esa correspondencia y el envío de la secuencia de aplicación para ambos gestos. No se conservaron cargas BLE ni datos de sesión.

El cuerpo binario de una sola propiedad ocupa 12 bytes y es little-endian donde corresponde: longitud con la bandera del protocolo, identificador de solicitud de 16 bits, opcode de establecimiento, cantidad de propiedades y la entrada `(siid, piid, tipo+longitud, valor)`. Para `BOOL`, el tipo es `0`, la longitud es uno y el valor se representa por un único byte. El identificador de solicitud se incrementa localmente y se reinicia antes del valor reservado final.

## Envoltura de aplicación autenticada

El canal de aplicación usa AES-CCM-128 sin datos autenticados adicionales y una etiqueta de cuatro bytes. Para cada conexión autenticada:

- La clave de salida es la segunda porción de 16 bytes de la clave de sesión; la de entrada es la primera porción de 16 bytes.
- Cada dirección toma un prefijo de nonce distinto de cuatro bytes desde la clave de sesión y agrega un contador de 16 bits más una época de 16 bits, todos little-endian. Los contadores se reinician al abrir una sesión nueva.
- El mensaje saliente contiene ese contador seguido del resultado AES-CCM. Después, el canal existente lo divide mediante control de flujo y fragmentos; una orden de bloqueo cabe en un único fragmento.

El módulo `:scooterlab` reproduce este codec en memoria con datos sintéticos y pruebas
unitarias. La app Android lo conecta al GATT de aplicación y exige una confirmación de interfaz
antes de transmitir una orden física. El Scooter 6 Max confirma bloqueo y desbloqueo mediante
una respuesta MiOT autenticada con el mismo identificador de solicitud y la propiedad `4.6`; no
siempre entrega el acuse genérico de datos que otros canales usarían.

## Lo que aún debe verificarse dinámicamente

- Los códigos de estado del protocolo de seguridad, separados de los callbacks internos de la cola de Xiaomi Home.
- Las demás operaciones semánticas de Xiaomi Home y su política de reintento/timeout. Bloqueo y
  desbloqueo ya validan la respuesta MiOT recibida por GATT; se debe replicar cada función nueva
  de forma independiente.

## Consecuencia de implementación

Un cliente compatible necesita el `ltmk` del dispositivo y debe realizar un ECDH nuevo en cada conexión. El `ltmk` no debe guardarse en el repositorio. La app de laboratorio puede reutilizar una credencial local privada para las pruebas autorizadas y presenta acciones físicas sólo detrás de confirmación explícita; no constituye un mecanismo de almacenamiento apto para distribución.
