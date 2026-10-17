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
            // Lưu ý: Đoạn code nạp Keystore tạm thời comment lại để bạn chạy thử được luôn
            // khi chưa tạo file keystore.jks bằng keytool.
            // Khi nào có file, hãy bỏ comment các dòng dưới đây.

            /*
            KeyStore keyStore = KeyStore.getInstance("JKS");
            keyStore.load(new FileInputStream(KEYSTORE_PATH), KEYSTORE_PASSWORD.toCharArray());

            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keyStore, KEYSTORE_PASSWORD.toCharArray());

            SSLContext sslContext = SSLContext.getInstance("TLSv1.2");
            sslContext.init(kmf.getKeyManagers(), null, null);

            SSLServerSocketFactory ssf = sslContext.getServerSocketFactory();
            ServerSocket serverSocket = ssf.createServerSocket(PORT);
            */

            // Tạm thời dùng ServerSocket thường để test tiến trình Git
            ServerSocket serverSocket = new ServerSocket(PORT);

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