# Deployment Runbook — Custom HTTP Framework (Assignment Extension)

Complete, literal step-by-step record of building, testing, containerizing, publishing, and
deploying this framework — every command and every value used.

---

## Part 1 — Building the framework

1. Plain Maven project, no Spring, no external dependencies — just the JDK. `pom.xml` produces
   an executable jar via `maven-jar-plugin`, with the manifest's `Main-Class` set directly
   (no shade/assembly plugin needed since there are zero third-party dependencies to bundle):

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

2. `HttpServer.java` — the accept loop:
   - Reads `PORT` (default `35000`) and `THREAD_POOL_SIZE` (default `20`) from the environment.
   - Opens a single `ServerSocket`, creates a fixed-size `ExecutorService` thread pool.
   - Registers a JVM shutdown hook (`Runtime.getRuntime().addShutdownHook`) **before** entering
     the accept loop, so it is guaranteed to run on `SIGTERM`/`SIGINT`.
   - Loop: `serverSocket.accept()` → `pool.submit(new RequestHandler(client))`. Each connection is
     handed off immediately; the accept loop never blocks on request processing, which is what
     makes concurrent handling possible.
   - Shutdown hook: sets the `running` flag to `false`, closes the `ServerSocket` (which makes the
     blocked `accept()` throw `SocketException`, breaking the loop cleanly), calls `pool.shutdown()`,
     then `pool.awaitTermination(10, TimeUnit.SECONDS)` to let in-flight requests finish before
     falling back to `pool.shutdownNow()` if the timeout is exceeded.

3. `RequestHandler.java` — runs inside the pool, one instance per connection:
   - Reads the request line, extracts the path, drains remaining headers (required so the socket
     is left in a clean state even though headers aren't used yet).
   - Routes `/greeting?name=` (mirrors the Spring Boot workshop's endpoint for direct comparison)
     and `/health` (returns the *handling thread's name* as JSON — this is what makes concurrency
     directly observable: different simultaneous requests get answered by different pool threads).
   - Writes a raw HTTP/1.1 response by hand: status line, `Content-Type`, `Content-Length`,
     `Connection: close`, then the body bytes.

4. Build:

   ```bash
   mvn clean package
   # → target/custom-http-framework.jar
   ```

---

## Part 2 — Local verification (concurrency)

```bash
PORT=35000 java -jar target/custom-http-framework.jar
# [HttpServer] Listening on port 35000 with a thread pool of 20 workers.
```

Concurrency test — 8 requests fired in parallel with shell background jobs:

```bash
for i in 1 2 3 4 5 6 7 8; do
  curl -s "http://localhost:35000/greeting?name=Thread$i" &
done
wait
```

Result: `Hello, Thread2Hello, Thread3Hello, Thread1Hello, Thread4...` — responses interleaved and
out of request order, proving they were **not** processed one at a time. `curl /health` afterward
reported `pool-1-thread-9`, confirming distinct pool worker threads handled the load.

> **Graceful shutdown was not tested via raw `kill`/`taskkill` on Windows** — Windows has no true
> POSIX `SIGTERM`, and MSYS/Git Bash's `kill -TERM` on a JVM process does not reliably trigger a
> Java shutdown hook the way a real signal does; `taskkill` also forcefully terminates without
> giving the JVM a chance to run shutdown hooks in the general case. The graceful-shutdown
> requirement is actually meaningful in the containerized/orchestrated case (Docker, Kubernetes,
> EC2 instance stop), so it was verified properly in Part 3 with a real `docker stop`, which does
> send a genuine `SIGTERM` to PID 1 inside the Linux container.

---

## Part 3 — Docker: build, concurrency, and real graceful shutdown

1. `Dockerfile`:

   ```dockerfile
   FROM amazoncorretto:21
   WORKDIR /app
   COPY target/custom-http-framework.jar app.jar
   ENV PORT=35000
   EXPOSE 35000
   ENTRYPOINT [ "java", "-jar", "app.jar" ]
   ```

2. Build and run:

   ```bash
   docker build -t exael74/custom-http-framework:1.0 .
   docker run -d --name custom-http-framework -e PORT=35000 -p 35000:35000 exael74/custom-http-framework:1.0
   ```

3. Verified: `curl http://localhost:35000/greeting?name=Docker` → `Hello, Docker`;
   `curl http://localhost:35000/health` → `{"status":"UP","thread":"pool-1-thread-2"}`.

4. Concurrency inside the container — 10 parallel requests, all succeeded simultaneously:

   ```bash
   for i in 1 2 3 4 5 6 7 8 9 10; do curl -s "http://localhost:35000/greeting?name=C$i" & done
   wait
   ```

5. **Real graceful shutdown**, triggered by Docker's own stop mechanism (`docker stop` sends
   `SIGTERM` to PID 1, waits up to its default grace period, then would `SIGKILL` if the process
   hadn't exited — it didn't need to here):

   ```bash
   docker stop custom-http-framework
   docker logs custom-http-framework
   ```

   ```
   [HttpServer] Listening on port 35000 with a thread pool of 20 workers.
   [HttpServer] Shutdown signal received. Closing the listening socket...
   [HttpServer] Waiting for in-flight requests to finish...
   [HttpServer] Server stopped.
   ```

   This confirms the shutdown hook intercepted the real signal and ran its full sequence instead
   of the container being killed abruptly. Full transcript:
   `evidence/01-concurrency-and-graceful-shutdown.txt`.

---

## Part 4 — Publish to Docker Hub

```bash
docker rm custom-http-framework
docker tag exael74/custom-http-framework:1.0 exael74/custom-http-framework:latest
docker push exael74/custom-http-framework:1.0
docker push exael74/custom-http-framework:latest
```

Published at https://hub.docker.com/r/exael74/custom-http-framework (tags `1.0` and `latest`,
digest `sha256:a78788e34df96e2d9a3971932a087c02578b025d7bcf06639e5c4e1042b1aa7c`).

---

## Part 5 — AWS EC2 deployment (same instance as the Spring Boot workshop)

Rather than provisioning a second EC2 instance, this framework was deployed as a **second,
independent container** on the already-running `virtualization-lab` instance
(`i-0f776796ea6e335f1`, `54.175.21.62`, `t2.micro`, Amazon Linux 2023, us-east-1) — a direct,
practical demonstration of one VM hosting multiple isolated Docker workloads side by side.

### 5.1 Pull and run over SSH

```bash
ssh -i "Downloads/virtualization-lab-key.pem" ec2-user@54.175.21.62 "
  sudo docker pull exael74/custom-http-framework:1.0 &&
  sudo docker run -d \
    --name custom-http-framework \
    --restart unless-stopped \
    -e PORT=35000 \
    -p 8081:35000 \
    exael74/custom-http-framework:1.0 &&
  sudo docker ps &&
  sudo docker logs custom-http-framework
"
```

Result — both containers confirmed running side by side on the same host:

```
CONTAINER ID   IMAGE                               PORTS                                         NAMES
24d135e1daa4   exael74/custom-http-framework:1.0   0.0.0.0:8081->35000/tcp, :::8081->35000/tcp   custom-http-framework
d501d2335f61   exael74/virtualizationlab:1.0       0.0.0.0:8080->9000/tcp, :::8080->9000/tcp     virtualization-lab
```

### 5.2 Opening the security group for port 8081

Initial `curl` from an external machine timed out — port 8081 was not yet allowed by the
instance's security group (`launch-wizard-1`, `sg-0627cab76675ad10d`), which at that point only
allowed SSH (22, restricted to the operator's IP) and 8080 (the Spring Boot demo).

Via AWS Console → EC2 → Security Groups → `launch-wizard-1` → **Edit inbound rules** → **Add
rule**:

| Type | Protocol | Port range | Source |
|---|---|---|---|
| Custom TCP | TCP | `8081` | `0.0.0.0/0` |

(Same public-access criterion already used and approved for port 8080, since this is a public
class demo, not a production deployment.) → **Save rules**.

> Note: as with the Spring Boot repository's EC2 setup, the AWS Console's rule-editing page
> intermittently stopped responding to mouse-wheel scrolling during this session. It recovered
> each time after a short wait; keyboard `Tab` navigation was used once to reach the "Save rules"
> button when it was scrolled out of view and mouse-wheel scroll had no effect.

### 5.3 External verification

```bash
curl -s -m 10 "http://54.175.21.62:8081/greeting?name=AWS"   # → Hello, AWS
curl -s -m 10 "http://54.175.21.62:8081/health"               # → {"status":"UP","thread":"pool-1-thread-2"}
```

Screenshot: `evidence/02-ec2-public-deployment-greeting.jpg`.
Full transcript: `evidence/03-ec2-deploy-output.txt`.

---

## Part 6 — Decommissioning

This container shared the EC2 instance with the Spring Boot workshop deployment. The instance
was **stopped/terminated** after all evidence above was captured, at the repository owner's
request, to avoid unnecessary AWS Academy lab charges. After this point, the public URL
`http://54.175.21.62:8081/greeting?name=AWS` is no longer reachable — it is preserved in the
README and in `evidence/` purely as proof that the deployment worked while the instance was live.
