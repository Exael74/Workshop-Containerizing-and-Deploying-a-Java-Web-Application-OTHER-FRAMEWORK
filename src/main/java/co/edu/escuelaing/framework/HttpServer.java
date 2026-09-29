package co.edu.escuelaing.framework;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Minimal HTTP server built directly on ServerSocket, with no external
 * web framework. Each accepted connection is handed off to a fixed thread
 * pool so multiple requests are served concurrently instead of one at a time.
 */
public class HttpServer {

    public static void main(String[] args) throws IOException {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "35000"));
        int poolSize = Integer.parseInt(System.getenv().getOrDefault("THREAD_POOL_SIZE", "20"));

        ServerSocket serverSocket = new ServerSocket(port);
        ExecutorService pool = Executors.newFixedThreadPool(poolSize);
        AtomicBoolean running = new AtomicBoolean(true);

        Runtime.getRuntime().addShutdownHook(new Thread(() ->
                shutdown(serverSocket, pool, running), "shutdown-hook"));

        System.out.println("[HttpServer] Listening on port " + port
                + " with a thread pool of " + poolSize + " workers.");

        while (running.get()) {
            try {
                Socket client = serverSocket.accept();
                pool.submit(new RequestHandler(client));
            } catch (SocketException e) {
                // Thrown by serverSocket.close() during shutdown; only an error if we're not shutting down.
                if (running.get()) {
                    System.err.println("[HttpServer] Socket error: " + e.getMessage());
                }
            }
        }
    }

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
}
