package fj;

import fj.controller.FileController;
import fj.utils.CodeGenerator;
import java.io.IOException;

public class App {
    public static void main(String[] args) {
        try {
            // Start the API server on port 8080
            int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
            FileController fileController = new FileController(port);
            fileController.start();
            
            System.out.println("PeerPort server started on port:"+port);

            String frontendUrl = System.getenv().getOrDefault("FRONTEND_URL","http://localhost:3000");
            System.out.println("UI available at: " + frontendUrl);

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("Shutting down server...");
                fileController.stop();
            }));
            
        } catch (IOException e) {
            System.err.println("Error starting server: " + e.getMessage());
            e.printStackTrace();
        }
    }
}