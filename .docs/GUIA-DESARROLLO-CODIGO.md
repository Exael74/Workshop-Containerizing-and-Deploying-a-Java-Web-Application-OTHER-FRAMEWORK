# Guía de Desarrollo de Código — Custom HTTP Framework

Esta guía explica, en detalle y en español, **cómo se construyó** este servidor HTTP escrito a
mano sobre `java.net.ServerSocket`, sin ningún framework web de por medio. Es un manual de
**desarrollo** (qué hace cada línea y por qué), complementario a `DEPLOYMENT-STEPS.md` (que cubre
la parte operativa de build/Docker/EC2).

La idea central de este proyecto es entender **qué hace un framework como Spring Boot por
nosotros automáticamente**, escribiéndolo manualmente: recibir conexiones TCP, interpretar bytes
crudos como una petición HTTP, atender varias peticiones al mismo tiempo, y apagarse de forma
ordenada.

---

## 1. `pom.xml` — un proyecto Maven mínimo, sin frameworks

```xml
<groupId>co.edu.escuelaing</groupId>
<artifactId>custom-http-framework</artifactId>
<version>1.0.0</version>
<packaging>jar</packaging>

<properties>
    <maven.compiler.source>21</maven.compiler.source>
    <maven.compiler.target>21</maven.compiler.target>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
</properties>
```

A diferencia del proyecto Spring Boot, aquí **no hay ningún `<parent>`** ni ninguna
`<dependency>`: todo el código usa exclusivamente clases del JDK estándar
(`java.net.*`, `java.io.*`, `java.util.concurrent.*`). Por eso tampoco hace falta un plugin que
arme un "fat jar" — no hay dependencias externas que empaquetar.

```xml
<build>
    <finalName>custom-http-framework</finalName>
    <plugins>
        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-jar-plugin</artifactId>
            <version>3.4.1</version>
            <configuration>
                <archive>
                    <manifest>
                        <mainClass>co.edu.escuelaing.framework.HttpServer</mainClass>
                    </manifest>
                </archive>
            </configuration>
        </plugin>
    </plugins>
</build>
```

- `<finalName>custom-http-framework</finalName>`: fuerza que el `.jar` generado se llame
  exactamente `custom-http-framework.jar` (sin el sufijo de versión `-1.0.0` que Maven agregaría
  por defecto). Esto simplifica el `Dockerfile`, que puede referenciar el archivo por un nombre
  fijo sin usar comodines.
- `maven-jar-plugin` con `<mainClass>`: escribe en el manifiesto del `.jar`
  (`META-INF/MANIFEST.MF`) la línea `Main-Class: co.edu.escuelaing.framework.HttpServer`. Esto es
  lo que permite ejecutar `java -jar custom-http-framework.jar` sin tener que especificar
  manualmente `-cp ... co.edu.escuelaing.framework.HttpServer` — la JVM lee el manifiesto y sabe
  qué clase tiene el método `main`.

---

## 2. `HttpServer.java` — el corazón del servidor

Este archivo resuelve, en el orden en que aparecen los requisitos del taller, **concurrencia**,
**apagado ordenado (graceful shutdown)**, y **puerto configurable por variable de entorno**.

### 2.1. Lectura de configuración

```java
int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "35000"));
int poolSize = Integer.parseInt(System.getenv().getOrDefault("THREAD_POOL_SIZE", "20"));
```

Mismo patrón que en el proyecto Spring Boot (`System.getenv().getOrDefault(...)`), pero aquí
convertimos manualmente el resultado (que siempre es `String`, porque las variables de entorno del
sistema operativo son texto) a `int` con `Integer.parseInt`. Se agregó además una segunda variable,
`THREAD_POOL_SIZE`, para poder ajustar cuántos hilos trabajadores tiene el servidor sin recompilar
— un valor más alto permite atender más conexiones simultáneas, a costa de más memoria/CPU.

### 2.2. Apertura del socket de escucha

```java
ServerSocket serverSocket = new ServerSocket(port);
```

`ServerSocket` es la clase del JDK que representa un **socket de servidor TCP**: se une
("bindea") al puerto indicado y queda a la espera de que lleguen conexiones entrantes. Es la
pieza que, en un framework como Spring Boot, está oculta dentro de Tomcat — aquí la creamos
nosotros mismos, explícitamente.

### 2.3. El pool de hilos — la clave de la concurrencia

```java
ExecutorService pool = Executors.newFixedThreadPool(poolSize);
```

**Este es el requisito de "concurrent request handling" resuelto en una sola línea, pero hay que
entender qué resuelve exactamente:**

- Sin un pool, la forma más simple (e ingenua) de escribir un servidor sería un bucle que hace
  `accept()` y luego **procesa la petición ahí mismo, en el mismo hilo**, antes de volver a
  llamar `accept()` para la siguiente conexión. Eso es un servidor **secuencial**: si el cliente A
  tarda 3 segundos en recibir su respuesta, el cliente B tiene que esperar esos 3 segundos enteros
  antes de que el servidor siquiera empiece a leer su petición.
- Un `ExecutorService` (interfaz del paquete `java.util.concurrent`) representa un **grupo de
  hilos reutilizables** a los que se les puede "enviar" tareas (`Runnable` o `Callable`) para que
  las ejecuten en paralelo. `Executors.newFixedThreadPool(poolSize)` crea uno con exactamente
  `poolSize` hilos: si llegan más tareas que hilos disponibles, las tareas extra esperan en una
  cola interna hasta que un hilo se desocupe — pero mientras haya hilos libres, varias peticiones
  se procesan **al mismo tiempo**, en núcleos de CPU distintos si el hardware lo permite.
- Crear un hilo nuevo por cada petición (`new Thread(...)` sin límite) también funcionaría para
  pocas peticiones, pero no escala: crear y destruir hilos tiene un costo, y sin límite un
  atacante (o simplemente mucho tráfico) podría agotar la memoria del proceso. Un pool **fijo**
  acota ese costo de forma predecible.

### 2.4. La bandera de estado: `AtomicBoolean`

```java
AtomicBoolean running = new AtomicBoolean(true);
```

`running` es una bandera que indica si el servidor debe seguir aceptando conexiones. Se usa
`AtomicBoolean` en vez de un simple `boolean` por una razón muy concreta de concurrencia: esta
variable la **escribe** el hilo del shutdown hook (cuando llega la señal de apagado) y la **lee**
el hilo principal (dentro del `while (running.get())`). Sin una clase atómica (o sin `volatile`),
la Especificación del Lenguaje Java no garantiza que un cambio hecho por un hilo sea *visible*
inmediatamente para otro hilo — el hilo principal podría quedar leyendo un valor "cacheado"
desactualizado y nunca enterarse de que debe parar. `AtomicBoolean` garantiza esa visibilidad
entre hilos (además de operaciones atómicas, aunque aquí solo usamos `get()`/`set()` simples).

### 2.5. Registrar el shutdown hook **antes** de aceptar conexiones

```java
Runtime.getRuntime().addShutdownHook(new Thread(() ->
        shutdown(serverSocket, pool, running), "shutdown-hook"));
```

- `Runtime.getRuntime()` da acceso al objeto que representa el entorno de ejecución de la JVM
  actual.
- `addShutdownHook(Thread)` registra un hilo que la JVM **garantiza ejecutar** cuando el proceso
  está por terminar de forma "limpia": esto incluye cuando el programa termina normalmente, cuando
  se presiona Ctrl+C (que envía `SIGINT`), y — el caso que nos interesa para Docker — cuando el
  sistema operativo envía `SIGTERM` (que es exactamente lo que hace `docker stop`). **No** se
  ejecuta si el proceso se mata a la fuerza con `kill -9` / `SIGKILL`, porque esa señal no le da
  ninguna oportunidad a la JVM de reaccionar.
- Es crítico registrar este hook **antes** de entrar al bucle `while` de aceptar conexiones,
  porque el bucle bloquea el hilo principal indefinidamente en `accept()` — si el hook se
  registrara después del bucle, ese código nunca se alcanzaría a ejecutar.
- La lambda `() -> shutdown(serverSocket, pool, running)` simplemente delega el trabajo real al
  método `shutdown`, para mantener el método `main` legible.

### 2.6. El bucle principal — aceptar y delegar

```java
while (running.get()) {
    try {
        Socket client = serverSocket.accept();
        pool.submit(new RequestHandler(client));
    } catch (SocketException e) {
        if (running.get()) {
            System.err.println("[HttpServer] Socket error: " + e.getMessage());
        }
    }
}
```

- `serverSocket.accept()` es una llamada **bloqueante**: el hilo que la ejecuta se queda "dormido"
  hasta que llega una nueva conexión TCP. Cuando llega, devuelve un objeto `Socket` que representa
  esa conexión específica con ese cliente.
- `pool.submit(new RequestHandler(client))`: en vez de procesar la petición en este mismo hilo
  (el hilo principal, cuyo único trabajo debe ser aceptar conexiones lo más rápido posible), se
  **envuelve** el socket del cliente en un objeto `RequestHandler` (que implementa `Runnable`,
  ver sección 3) y se **envía** al pool de hilos. El método `submit` retorna inmediatamente — no
  espera a que la petición termine de procesarse — así que el bucle vuelve de inmediato a llamar
  `accept()` otra vez, listo para la siguiente conexión mientras la anterior se procesa en
  paralelo, en otro hilo.
- El `catch (SocketException e)`: cuando el shutdown hook llama a `serverSocket.close()`, cualquier
  llamada a `accept()` que estuviera bloqueada en ese momento **lanza una excepción**
  `SocketException` (así es como Java "despierta" al hilo bloqueado). Por eso el `catch` primero
  revisa `if (running.get())`: si la bandera ya está en `false`, sabemos que esta excepción es
  **esperada** (es la señal de que nos están pidiendo parar), así que no se imprime como error —
  simplemente se deja que el `while` vuelva a evaluar su condición, encuentre `running.get()` en
  `false`, y termine el bucle de forma natural.

### 2.7. El método `shutdown` — apagado ordenado paso a paso

```java
private static void shutdown(ServerSocket serverSocket, ExecutorService pool, AtomicBoolean running) {
    System.out.println("[HttpServer] Shutdown signal received. Closing the listening socket...");
    running.set(false);
    try {
        serverSocket.close();
    } catch (IOException e) {
        System.err.println("[HttpServer] Error closing server socket: " + e.getMessage());
    }

    System.out.println("[HttpServer] Waiting for in-flight requests to finish...");
    pool.shutdown();
    try {
        if (!pool.awaitTermination(10, TimeUnit.SECONDS)) {
            System.out.println("[HttpServer] Timeout reached; forcing remaining requests to stop.");
            pool.shutdownNow();
        }
    } catch (InterruptedException e) {
        pool.shutdownNow();
        Thread.currentThread().interrupt();
    }
    System.out.println("[HttpServer] Server stopped.");
}
```

Paso a paso, y en este orden **a propósito**:

1. `running.set(false)`: primero se marca la bandera, para que en cuanto el bucle principal se
   desbloquee, salga inmediatamente en vez de intentar aceptar una conexión más.
2. `serverSocket.close()`: cierra el socket de escucha. Esto tiene dos efectos: (a) el sistema
   operativo deja de aceptar **nuevas** conexiones entrantes en ese puerto, y (b) si el hilo
   principal estaba bloqueado en `accept()`, lo despierta lanzando la `SocketException` que ya
   vimos en el punto anterior.
3. `pool.shutdown()`: le dice al `ExecutorService` "no aceptes tareas nuevas a partir de ahora",
   pero **no interrumpe** las tareas que ya están en ejecución — las deja terminar normalmente.
   Esto es justo la semántica de "apagado ordenado": las peticiones que ya estaban siendo
   atendidas cuando llegó la señal de apagado **se completan y responden al cliente**, en vez de
   cortarse a la mitad.
4. `pool.awaitTermination(10, TimeUnit.SECONDS)`: bloquea el hilo del shutdown hook hasta que
   **todas** las tareas en curso terminen, o hasta que pasen 10 segundos, lo que ocurra primero.
   Devuelve `true` si todo terminó a tiempo, `false` si se acabó el plazo y todavía había tareas
   corriendo.
5. Si se agotó el plazo (`!awaitTermination(...)`), se llama a `pool.shutdownNow()`, que sí
   **interrumpe** forzosamente los hilos que sigan trabajando (usando `Thread.interrupt()`
   internamente) — esto es una salvaguarda para no dejar el proceso colgado indefinidamente si
   alguna petición quedó atascada.
6. El `catch (InterruptedException e)`: si el propio hilo del shutdown hook es interrumpido
   mientras espera (algo poco común, pero posible), se fuerza el apagado inmediato con
   `shutdownNow()` y se **re-propaga** la interrupción con `Thread.currentThread().interrupt()`
   — esto es una buena práctica estándar en Java: cuando atrapas una `InterruptedException` y no
   vas a relanzarla, debes al menos restaurar el "estado de interrumpido" del hilo, para que
   código más arriba en la pila de llamadas pueda enterarse si le importa.

Esta secuencia completa es exactamente lo que se ve en los logs reales capturados como evidencia
(`evidence/01-concurrency-and-graceful-shutdown.txt`):

```
[HttpServer] Shutdown signal received. Closing the listening socket...
[HttpServer] Waiting for in-flight requests to finish...
[HttpServer] Server stopped.
```

---

## 3. `RequestHandler.java` — atender una conexión

```java
public class RequestHandler implements Runnable {

    private final Socket client;

    public RequestHandler(Socket client) {
        this.client = client;
    }

    @Override
    public void run() {
        try (client;
             BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
             OutputStream out = client.getOutputStream()) {
            ...
        } catch (IOException e) {
            ...
        }
    }
}
```

- `implements Runnable`: esta es la interfaz que el `ExecutorService` sabe ejecutar — cualquier
  objeto `Runnable` que se le pase a `pool.submit(...)` tendrá su método `run()` invocado en uno
  de los hilos del pool.
- `try (client; BufferedReader in = ...; OutputStream out = ...)`: esto es un
  **try-with-resources** con **tres** recursos. Java cierra los recursos automáticamente al salir
  del bloque `try` (ya sea normalmente o por una excepción), en el **orden inverso** al que se
  declararon: primero se cierra `out`, luego `in`, y finalmente `client` (el propio socket). Esto
  evita tener que escribir manualmente un `finally` con `client.close()` y arriesgarse a olvidarlo
  o a dejar el socket abierto si ocurre una excepción a mitad de camino — una fuga de recursos muy
  común en código de red escrito "a mano".
- `client.getInputStream()` / `client.getOutputStream()`: todo `Socket` en Java expone estos dos
  flujos de bytes crudos — uno para leer lo que el cliente envía, otro para escribir la respuesta.
  El `BufferedReader` envuelve el flujo de entrada para poder leer **línea por línea** con
  `readLine()`, que es justo la unidad en la que está estructurada una petición HTTP de texto
  plano (cada línea de la petición termina en `\r\n`).

### 3.1. Parseo manual de la línea de petición

```java
String requestLine = in.readLine();
if (requestLine == null || requestLine.isBlank()) {
    return;
}

String[] parts = requestLine.split(" ");
String path = parts.length > 1 ? parts[1] : "/";
```

Una petición HTTP real empieza con una línea de texto como:

```
GET /greeting?name=Pedro HTTP/1.1
```

- `requestLine.split(" ")` la divide por espacios en tres partes: el **método** (`GET`), el
  **path** (`/greeting?name=Pedro`), y la **versión** del protocolo (`HTTP/1.1`). Este framework
  solo necesita el `path` — no distingue entre métodos HTTP (no hay verbos `POST`, `PUT`, etc.
  implementados), así que ni siquiera se guarda la parte del método.
- Si `requestLine` es `null` (el cliente cerró la conexión sin enviar nada) o está en blanco, el
  método simplemente `return`ea, dejando que el `try-with-resources` cierre todo limpiamente sin
  intentar procesar una petición que no existe.

### 3.2. "Drenar" los headers

```java
String header;
while ((header = in.readLine()) != null && !header.isEmpty()) {
    // intentionally ignored
}
```

Después de la línea de petición, un cliente HTTP real envía una serie de **cabeceras**
(`Host: ...`, `User-Agent: ...`, `Accept: ...`, etc.), una por línea, terminando con una línea
**vacía** que marca el final de las cabeceras. Este framework no necesita leer ninguna cabecera en
particular, pero **igual tiene que leerlas y descartarlas** — si no se "consumen" esos bytes del
flujo de entrada, quedarían pendientes en el buffer, lo cual en un protocolo más complejo (con
conexiones persistentes tipo `keep-alive`) confundiría al siguiente ciclo de lectura. Aquí,
como cada respuesta se envía con `Connection: close` (ver más abajo) y el socket se cierra
después de cada petición, el impacto práctico es menor, pero drenar las cabeceras es la práctica
correcta y necesaria para dejar el protocolo bien formado.

### 3.3. Enrutamiento simple con `if`/`else if`

```java
if (path.startsWith("/greeting")) {
    status = 200; statusText = "OK";
    body = "Hello, " + extractQueryParam(path, "name", "World");
} else if (path.equals("/health")) {
    status = 200; statusText = "OK"; contentType = "application/json";
    body = "{\"status\":\"UP\",\"thread\":\"" + Thread.currentThread().getName() + "\"}";
} else {
    status = 404; statusText = "Not Found";
    body = "Not Found: " + path;
}
```

Esto es, en esencia, lo que un framework como Spring hace con `@GetMapping("/greeting")` de forma
automática y declarativa (con anotaciones y una tabla de rutas interna) — aquí se hace explícito
con una cadena de `if`/`else if` comparando el `path` manualmente. Es deliberadamente simple
porque el objetivo del ejercicio es entender el mecanismo, no construir un sistema de rutas
genérico.

Dato interesante del endpoint `/health`: `Thread.currentThread().getName()` devuelve el nombre del
hilo del pool que está ejecutando **esta petición en particular** (por ejemplo,
`"pool-1-thread-3"`). Esto es justamente lo que permite **comprobar la concurrencia
empíricamente**: si se hacen varias peticiones simultáneas a `/health`, cada una reporta un
nombre de hilo distinto, demostrando que efectivamente se están atendiendo en paralelo por hilos
diferentes del pool.

### 3.4. Construcción manual de la respuesta HTTP

```java
byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
String responseHeaders = "HTTP/1.1 " + status + " " + statusText + "\r\n"
        + "Content-Type: " + contentType + "\r\n"
        + "Content-Length: " + bodyBytes.length + "\r\n"
        + "Connection: close\r\n"
        + "\r\n";

out.write(responseHeaders.getBytes(StandardCharsets.UTF_8));
out.write(bodyBytes);
out.flush();
```

Una respuesta HTTP/1.1 tiene un formato de texto muy preciso, que aquí se arma "a mano" carácter
por carácter:

- **Línea de estado:** `HTTP/1.1 <código> <texto>\r\n` — por ejemplo, `HTTP/1.1 200 OK`. El
  `\r\n` (retorno de carro + salto de línea) es el terminador de línea exigido por el estándar
  HTTP, distinto del simple `\n` que usan Unix/Linux internamente.
- **Cabecera `Content-Type`:** le dice al cliente cómo interpretar el cuerpo (`text/plain` para
  el saludo, `application/json` para `/health`).
- **Cabecera `Content-Length`:** el número **exacto** de bytes del cuerpo. Es fundamental
  calcularlo sobre `bodyBytes.length` (los bytes ya codificados en UTF-8) y no sobre
  `body.length()` (la longitud del `String` en caracteres) — con texto que use caracteres
  especiales (tildes, `ñ`, emojis) esos dos números pueden diferir, porque UTF-8 codifica algunos
  caracteres con más de un byte. Si `Content-Length` estuviera mal, el cliente podría cortar la
  lectura antes de tiempo o quedarse esperando bytes que nunca llegan.
- **Cabecera `Connection: close`:** le avisa al cliente que el servidor va a cerrar la conexión
  TCP después de esta respuesta (no soporta conexiones persistentes/`keep-alive`), así el cliente
  sabe que debe abrir una conexión nueva para la siguiente petición.
- **Línea en blanco (`\r\n` final):** el separador obligatorio entre las cabeceras y el cuerpo de
  la respuesta.
- `out.write(...)` dos veces (cabeceras, luego cuerpo) y `out.flush()` al final para asegurar que
  todos los bytes efectivamente salgan por la red y no queden retenidos en algún buffer interno
  del `OutputStream`.

### 3.5. Extracción manual de query params

```java
private String extractQueryParam(String path, String key, String defaultValue) {
    int queryIndex = path.indexOf('?');
    if (queryIndex == -1) {
        return defaultValue;
    }
    String query = path.substring(queryIndex + 1);
    for (String pair : query.split("&")) {
        String[] kv = pair.split("=", 2);
        if (kv.length == 2 && kv[0].equals(key)) {
            return URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
        }
    }
    return defaultValue;
}
```

Esto es exactamente lo que Spring hace automáticamente detrás de `@RequestParam` — aquí se
reimplementa a mano:

1. Busca el carácter `?` en el path completo; si no existe, no hay query string, y se devuelve el
   valor por defecto directamente.
2. `path.substring(queryIndex + 1)` toma todo lo que hay **después** del `?` (por ejemplo,
   `name=Pedro&otro=valor`).
3. `query.split("&")` separa cada par `clave=valor` (varios parámetros se combinan con `&`).
4. Para cada par, `pair.split("=", 2)` lo divide en como máximo dos partes por el primer `=`
   (el límite `2` es importante: si el valor mismo contuviera un `=`, no lo cortaría a la mitad).
5. Si la clave (`kv[0]`) coincide con la que buscamos, se devuelve el valor, pasado por
   `URLDecoder.decode(...)` — esto es necesario porque los valores en una URL pueden venir
   *percent-encoded* (por ejemplo, un espacio se representa como `%20` o `+`), y hay que
   decodificarlos de vuelta a su forma legible normal.
6. Si se recorre todo el query string y no se encuentra la clave, se devuelve el valor por
   defecto (mismo comportamiento que `defaultValue` en `@RequestParam` de Spring).

---

## 4. `Dockerfile` — mismo patrón que el proyecto Spring Boot, adaptado

```dockerfile
FROM amazoncorretto:21
WORKDIR /app
COPY target/custom-http-framework.jar app.jar
ENV PORT=35000
EXPOSE 35000
ENTRYPOINT [ "java", "-jar", "app.jar" ]
```

La única diferencia relevante frente al Dockerfile del proyecto Spring Boot es que aquí `COPY`
usa el nombre **exacto** del archivo (`custom-http-framework.jar`, gracias al `<finalName>`
configurado en el `pom.xml`) en vez de un comodín `*.jar` — ambas formas son válidas; se eligió
el nombre exacto aquí simplemente por variar y mostrar las dos formas posibles.

El resto de conceptos (imagen base con JDK ya instalado, `ENV` como documentación + valor por
defecto, `EXPOSE` como documentación sin efecto real en el mapeo de puertos, `ENTRYPOINT` en
forma "exec" para que `java` sea el PID 1 y reciba correctamente `SIGTERM`) son idénticos a los
ya explicados en la guía del repositorio de Spring Boot — ver esa guía para el detalle completo,
en particular la sección sobre `ENTRYPOINT` y por qué la forma exec es la que permite que
`docker stop` dispare realmente el shutdown hook de la JVM (que es, de hecho, el mecanismo que se
verificó funcionando en este mismo proyecto).

---

## 5. Comparación directa: qué hace Spring Boot "gratis" vs. qué se escribió a mano aquí

| Responsabilidad | En Spring Boot (`virtualization-lab`) | Aquí (`custom-http-framework`) |
|---|---|---|
| Aceptar conexiones TCP | Tomcat embebido, oculto detrás del *starter* | `ServerSocket.accept()` explícito |
| Atender varias peticiones a la vez | Tomcat ya trae su propio pool de hilos internamente | `ExecutorService` creado y gestionado a mano |
| Parsear la petición HTTP | Servlet API + Spring MVC | Parseo manual de la línea de petición y las cabeceras |
| Enrutar por path | `@GetMapping` + tabla de rutas interna del `DispatcherServlet` | Cadena de `if`/`else if` sobre el `path` |
| Leer parámetros de query | `@RequestParam` | Método `extractQueryParam` escrito a mano, con `URLDecoder` |
| Construir la respuesta | Se retorna un `String`/objeto y Spring arma la respuesta HTTP completa | Se escriben a mano la línea de estado, cabeceras y cuerpo, byte por byte |
| Apagado ordenado | Spring Boot ya maneja `SIGTERM` internamente al apagar el contexto | Shutdown hook + `ExecutorService.shutdown()`/`awaitTermination` implementado explícitamente |
| Puerto configurable | Propiedad `server.port` leída por Spring | `System.getenv()` leído directamente por nuestro propio código |

Esta tabla resume el propósito pedagógico completo del ejercicio: todo lo que en el primer
repositorio "simplemente funciona" gracias a Spring Boot, en este segundo repositorio se
construyó explícitamente, línea por línea, para entender el mecanismo real detrás de cualquier
framework web.
