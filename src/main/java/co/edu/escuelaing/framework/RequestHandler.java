package co.edu.escuelaing.framework;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * Handles a single client connection: parses the request line, routes it,
 * and writes back a raw HTTP/1.1 response. Runs inside the server's thread
 * pool, so many instances execute concurrently.
 */
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

            String requestLine = in.readLine();
            if (requestLine == null || requestLine.isBlank()) {
                return;
            }

            String[] parts = requestLine.split(" ");
            String path = parts.length > 1 ? parts[1] : "/";

            // Consume the remaining request headers; this framework does not use them yet,
            // but the stream must be drained before writing the response.
            String header;
            while ((header = in.readLine()) != null && !header.isEmpty()) {
                // intentionally ignored
            }

            writeResponse(out, path);

        } catch (IOException e) {
            System.err.println("[RequestHandler] Error handling request on thread "
                    + Thread.currentThread().getName() + ": " + e.getMessage());
        }
    }

    private void writeResponse(OutputStream out, String path) throws IOException {
        int status;
        String statusText;
        String contentType = "text/plain";
        String body;

        if (path.startsWith("/greeting")) {
            status = 200;
            statusText = "OK";
            body = "Hello, " + extractQueryParam(path, "name", "World");
        } else if (path.equals("/health")) {
            status = 200;
            statusText = "OK";
            contentType = "application/json";
            body = "{\"status\":\"UP\",\"thread\":\"" + Thread.currentThread().getName() + "\"}";
        } else {
            status = 404;
            statusText = "Not Found";
            body = "Not Found: " + path;
        }

        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        String responseHeaders = "HTTP/1.1 " + status + " " + statusText + "\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + bodyBytes.length + "\r\n"
                + "Connection: close\r\n"
                + "\r\n";

        out.write(responseHeaders.getBytes(StandardCharsets.UTF_8));
        out.write(bodyBytes);
        out.flush();
    }

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
}
