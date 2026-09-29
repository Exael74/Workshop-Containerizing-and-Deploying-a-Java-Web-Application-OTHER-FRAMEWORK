# Custom HTTP Framework — Assignment Extension

**Author:** Stiven Esneider Pardo Gutierrez
**Course:** TDSE — Transformación Digital y Sistemas Empresariales

> This repository is the *Assignment Extension* of the [virtualization/containerization workshop](https://github.com/Exael74/Workshop-Containerizing-and-Deploying-a-Java-Web-Application-SPRINGBOOT). It does **not** use Spring — it is a minimal HTTP server hand-built on top of `java.net.ServerSocket`.

## 1. Current State of the Framework

This is a from-scratch implementation (not an extension of a prior lab). It is a single-purpose HTTP server that:

- Listens on a raw `ServerSocket` and parses the HTTP request line itself (no servlet container, no routing library).
- Serves two endpoints: `GET /greeting?name=X` (mirrors the Spring Boot workshop's endpoint, for a direct side-by-side comparison) and `GET /health` (returns the handling thread's name as JSON, which is what makes concurrency visible/verifiable).
- Has no persistence layer and no other routes — everything not matched returns `404`.

## 2. Changes Introduced in This Extension

The assignment required improving a framework so it supports concurrent request handling, graceful shutdown, environment-based port configuration, Docker execution, and EC2 deployment. All five are implemented from the start in this repository:

| Requirement | Implementation |
|---|---|
| **Concurrent request handling** | Every accepted `Socket` is submitted to a fixed `ExecutorService` thread pool (`HttpServer.java`) instead of being handled inline in the accept loop. Multiple clients are served in parallel by different pool threads — see `evidence/01-concurrency-and-graceful-shutdown.txt`, where 10 requests fired in parallel all completed out of request-order. |
| **Graceful shutdown** | A JVM shutdown hook (`Runtime.getRuntime().addShutdownHook`) intercepts termination signals: it closes the listening `ServerSocket` (unblocking `accept()`), calls `pool.shutdown()`, and waits up to 10 seconds for in-flight requests to finish before forcing termination with `shutdownNow()`. Verified with `docker stop`, which sends `SIGTERM` to the container's PID 1 — see the same evidence file. |
| **Port from environment variable** | `System.getenv().getOrDefault("PORT", "35000")` — never hardcoded. |
| **Docker container** | `Dockerfile` based on `amazoncorretto:21`, same pattern as the Spring Boot workshop. |
| **EC2 deployment** | Deployed as a second, independent container on the same EC2 instance used for the Spring Boot workshop, listening on host port 8081. |

## 3. Project Structure

```
other-framework/
├── pom.xml                 # Plain Maven build (no Spring), produces an executable jar
├── Dockerfile
├── evidence/
└── src/main/java/co/edu/escuelaing/framework/
    ├── HttpServer.java     # Accept loop, thread pool, graceful shutdown
    └── RequestHandler.java # Per-connection request parsing and response writing
```

## 4. Build and Run

```bash
mvn clean package
java -jar target/custom-http-framework.jar
```

Defaults to port `35000`; override with the `PORT` environment variable (and optionally `THREAD_POOL_SIZE`, default `20`):

```bash
PORT=8081 THREAD_POOL_SIZE=50 java -jar target/custom-http-framework.jar
```

Verify:

```
http://localhost:35000/greeting?name=Pedro   →  Hello, Pedro
http://localhost:35000/health                →  {"status":"UP","thread":"pool-1-thread-N"}
```

## 5. Running in Docker

```dockerfile
FROM amazoncorretto:21
WORKDIR /app
COPY target/custom-http-framework.jar app.jar
ENV PORT=35000
EXPOSE 35000
ENTRYPOINT [ "java", "-jar", "app.jar" ]
```

```bash
mvn clean package
docker build -t exael74/custom-http-framework:1.0 .
docker run -d --name custom-http-framework -e PORT=35000 -p 35000:35000 exael74/custom-http-framework:1.0
```

Published on Docker Hub: **https://hub.docker.com/r/exael74/custom-http-framework**

## 6. Evidence of Progress

| File | What it shows |
|---|---|
| [`evidence/01-concurrency-and-graceful-shutdown.txt`](evidence/01-concurrency-and-graceful-shutdown.txt) | 10 concurrent requests served in parallel by the thread pool, then a real `docker stop` (SIGTERM) triggering the graceful-shutdown log sequence |
| [`evidence/02-ec2-public-deployment-greeting.jpg`](evidence/02-ec2-public-deployment-greeting.jpg) | Browser screenshot of the EC2-deployed container responding from the public internet |
| [`evidence/03-ec2-deploy-output.txt`](evidence/03-ec2-deploy-output.txt) | Full pull/run/verify transcript on the EC2 instance, alongside the already-running Spring Boot container |

**Public deployment URL:** http://54.175.21.62:8081/greeting?name=AWS

## 7. Commit History

The commit that introduces concurrent request handling, graceful shutdown, environment-based configuration, and the Docker/EC2 setup:

```
Implement concurrent request handling and graceful shutdown
```

See the repository's commit history on GitHub for the exact commit hash and diff.

## 8. Known Limitations

- No HTTPS/TLS termination (would sit behind a load balancer or reverse proxy in production).
- No persistence layer — matches the scope of the extension, which focuses on the server's concurrency/lifecycle model, not application data.
- Minimal HTTP parsing (request line + draining headers); no support for request bodies, chunked encoding, or keep-alive connections (`Connection: close` is always sent).
