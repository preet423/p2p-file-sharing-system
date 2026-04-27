package fj.controller;

import java.io.*;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import fj.utils.CodeGenerator;

import org.apache.commons.io.IOUtils;

public class FileController {

    private final HttpServer server;
    private final String uploadDir;
    private final ExecutorService executorService;

    // fileId -> File
    private static final Map<String, File> fileStore = new ConcurrentHashMap<>();

    public FileController(int port) throws IOException {
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        this.uploadDir = System.getProperty("java.io.tmpdir")
                + File.separator + "filejunction-uploads";
        this.executorService = Executors.newFixedThreadPool(10);

        File uploadDirFile = new File(uploadDir);
        if (!uploadDirFile.exists()) {
            uploadDirFile.mkdirs();
        }

        server.createContext("/upload", new UploadHandler());
        server.createContext("/download", new DownloadHandler());
        server.createContext("/", new CORSHandler());

        server.setExecutor(executorService);
    }

    public void start() {
        server.start();
        System.out.println("API server started on port " + server.getAddress().getPort());
    }

    public void stop() {
        server.stop(0);
        executorService.shutdown();
        System.out.println("API server stopped");
    }

    // ---------------- CORS ----------------
    private class CORSHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            Headers headers = exchange.getResponseHeaders();
            headers.add("Access-Control-Allow-Origin", "*");
            headers.add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            headers.add("Access-Control-Allow-Headers", "Content-Type,Authorization");

            if (exchange.getRequestMethod().equalsIgnoreCase("OPTIONS")) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            exchange.sendResponseHeaders(404, -1);
        }
    }

    // ---------------- UPLOAD ----------------
    private class UploadHandler implements HttpHandler {

    @Override
    public void handle(HttpExchange exchange) throws IOException {

        Headers headers = exchange.getResponseHeaders();
        headers.add("Access-Control-Allow-Origin", "*");
        headers.add("Access-Control-Allow-Methods", "POST, OPTIONS");
        headers.add("Access-Control-Allow-Headers", "Content-Type");

        if (exchange.getRequestMethod().equalsIgnoreCase("OPTIONS")) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.startsWith("multipart/form-data")) {
            exchange.sendResponseHeaders(400, -1);
            return;
        }

        try {
            String boundary = contentType.substring(contentType.indexOf("boundary=") + 9);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            IOUtils.copy(exchange.getRequestBody(), baos);
            byte[] requestData = baos.toByteArray();

            MultipartParser parser = new MultipartParser(requestData, boundary);
            MultipartParser.ParseResult result = parser.parse();

            if (result == null) {
                exchange.sendResponseHeaders(400, -1);
                return;
            }

            String filename = (result.filename == null || result.filename.isBlank())
                    ? "unnamed-file"
                    : result.filename;

            String storedFilename = UUID.randomUUID() + "_" + filename;
            File storedFile = new File(uploadDir, storedFilename);

            try (FileOutputStream fos = new FileOutputStream(storedFile)) {
                fos.write(result.fileContent);
            }

            String fileId;
            do {
                fileId = CodeGenerator.generateCode();
            } while (fileStore.containsKey(fileId));

            fileStore.put(fileId, storedFile);


            String json = "{ \"fileId\": \"" + fileId + "\" }";
            byte[] responseBytes = json.getBytes("UTF-8");

            headers.add("Content-Type", "application/json; charset=UTF-8");
            exchange.sendResponseHeaders(200, responseBytes.length);

            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBytes);
            }

        } catch (Exception e) {
            e.printStackTrace();
            exchange.sendResponseHeaders(500, -1);
        } finally {
            exchange.close();
        }
    }
}

    // ---------------- DOWNLOAD ----------------
    private class DownloadHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            Headers headers = exchange.getResponseHeaders();
            headers.add("Access-Control-Allow-Origin", "*");

            if (!exchange.getRequestMethod().equalsIgnoreCase("GET")) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            String path = exchange.getRequestURI().getPath();
            String fileId = path.substring(path.lastIndexOf('/') + 1);

            File file = fileStore.get(fileId);
            if (file == null || !file.exists()) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }

            headers.add("Content-Type", "application/octet-stream");
            headers.add(
                    "Content-Disposition",
                    "attachment; filename=\"" + file.getName() + "\""
            );

            exchange.sendResponseHeaders(200, file.length());

            try (OutputStream os = exchange.getResponseBody();
                 FileInputStream fis = new FileInputStream(file)) {

                byte[] buffer = new byte[8192];
                int read;
                while ((read = fis.read(buffer)) != -1) {
                    os.write(buffer, 0, read);
                }
            }
        }
    }

    // ---------------- MULTIPART PARSER (UNCHANGED) ----------------
    private static class MultipartParser {
        private final byte[] data;
        private final String boundary;

        public MultipartParser(byte[] data, String boundary) {
            this.data = data;
            this.boundary = boundary;
        }

        public ParseResult parse() {
            try {
                String s = new String(data);
                int fnStart = s.indexOf("filename=\"");
                if (fnStart == -1) return null;

                fnStart += 10;
                int fnEnd = s.indexOf("\"", fnStart);
                String filename = s.substring(fnStart, fnEnd);

                int headerEnd = s.indexOf("\r\n\r\n");
                if (headerEnd == -1) return null;

                int contentStart = headerEnd + 4;
                byte[] boundaryBytes = ("\r\n--" + boundary).getBytes();

                int contentEnd = indexOf(data, boundaryBytes, contentStart);
                if (contentEnd == -1) return null;

                byte[] fileContent = new byte[contentEnd - contentStart];
                System.arraycopy(data, contentStart, fileContent, 0, fileContent.length);

                return new ParseResult(filename, "application/octet-stream", fileContent);
            } catch (Exception e) {
                return null;
            }
        }

        private int indexOf(byte[] data, byte[] target, int start) {
            outer:
            for (int i = start; i <= data.length - target.length; i++) {
                for (int j = 0; j < target.length; j++) {
                    if (data[i + j] != target[j]) continue outer;
                }
                return i;
            }
            return -1;
        }

        public static class ParseResult {
            public final String filename;
            public final String contentType;
            public final byte[] fileContent;

            public ParseResult(String filename, String contentType, byte[] fileContent) {
                this.filename = filename;
                this.contentType = contentType;
                this.fileContent = fileContent;
            }
        }
    }
}
