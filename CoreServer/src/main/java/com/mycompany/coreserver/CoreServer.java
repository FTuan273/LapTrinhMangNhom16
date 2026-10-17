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
    private static final String KEYSTORE_PATH = "certs/keystore.jks"; 
    private static final String KEYSTORE_PASSWORD = "password123";

    public static void main(String[] args) {
        try {
            // 1. Nạp Keystore từ file đã tạo
            KeyStore keyStore = KeyStore.getInstance("JKS");
            keyStore.load(new FileInputStream(KEYSTORE_PATH), KEYSTORE_PASSWORD.toCharArray());

            // 2. Khởi tạo KeyManagerFactory
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keyStore, KEYSTORE_PASSWORD.toCharArray());

            // 3. Thiết lập cấu hình SSLContext (TLSv1.2)
            SSLContext sslContext = SSLContext.getInstance("TLSv1.2");
            sslContext.init(kmf.getKeyManagers(), null, null);

            // 4. Khởi tạo SSLServerSocket từ cấu hình trên
            SSLServerSocketFactory ssf = sslContext.getServerSocketFactory();
            ServerSocket serverSocket = ssf.createServerSocket(PORT);

            System.out.println("Core Server đang lắng nghe qua kênh bảo mật TLS tại port: " + PORT);

            // 5. Khởi tạo Thread Pool để xử lý đa luồng
            ExecutorService executor = Executors.newFixedThreadPool(10);

            while (true) {
                // Biến serverSocket giờ đã tồn tại và sẵn sàng nhận kết nối
                java.net.Socket clientSocket = serverSocket.accept();
                System.out.println("Client connected: " + clientSocket.getInetAddress());
                // Giao cho Thread xử lý (sẽ implement ở các issue sau)...
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}