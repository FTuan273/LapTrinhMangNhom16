package com.mycompany.coreserver;

import java.io.FileInputStream;
import java.net.ServerSocket;
import java.security.KeyStore;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocketFactory;

public class CoreServer {
    private static final int PORT = 8888;
    // Đường dẫn này giả định bạn sẽ tạo thư mục certs và file keystore.jks sau
    private static final String KEYSTORE_PATH = "certs/keystore.jks"; 
    private static final String KEYSTORE_PASSWORD = "password123";

    public static void main(String[] args) {
        try {
         System.out.println("Core Server đang lắng nghe tại port: " + PORT);

            ExecutorService executor = Executors.newFixedThreadPool(10);

            while (true) {
                java.net.Socket clientSocket = serverSocket.accept();
                System.out.println("Client connected: " + clientSocket.getInetAddress());
                // Giao cho Thread xử lý...
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}