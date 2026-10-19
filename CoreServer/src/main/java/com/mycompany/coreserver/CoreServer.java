package com.mycompany.coreserver;

import java.io.File;
import java.io.FileInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.ServerSocket;
import java.security.KeyStore;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSocket;

public class CoreServer {
    private static final int PORT = 8888;
    private static final String KEYSTORE_PASSWORD = "password123";

    private static File findKeyStoreFile() {
        File f1 = new File("../certs/keystore.jks");
        if (f1.exists()) return f1;
        File f2 = new File("certs/keystore.jks");
        if (f2.exists()) return f2;
        return f1;
    }

    public static void main(String[] args) {
        try {
            File keyStoreFile = findKeyStoreFile();
            System.out.println("Đang tìm keystore tại: " + keyStoreFile.getAbsolutePath());
            if (!keyStoreFile.exists()) {
                System.err.println("LỖI: Không tìm thấy file " + keyStoreFile.getAbsolutePath());
                return;
            }

            // 1. Nạp Keystore từ file đã tạo
            KeyStore keyStore = KeyStore.getInstance("JKS");
            try (FileInputStream fis = new FileInputStream(keyStoreFile)) {
                keyStore.load(fis, KEYSTORE_PASSWORD.toCharArray());
            }

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
                
                // Giao cho Thread xử lý client và thực hiện bắt tay TLS
                executor.submit(() -> {
                    try {
                        if (clientSocket instanceof SSLSocket) {
                            SSLSocket sslSocket = (SSLSocket) clientSocket;
                            sslSocket.startHandshake();
                            System.out.println("===> Bắt tay TLS thành công với Client: " + sslSocket.getRemoteSocketAddress());
                        }
                        DataInputStream dis = new DataInputStream(clientSocket.getInputStream());
                        DataOutputStream dos = new DataOutputStream(clientSocket.getOutputStream());

                        while (!clientSocket.isClosed()) {
                            int length = dis.readInt();
                            byte[] data = new byte[length];
                            dis.readFully(data);
                            String msg = new String(data, "UTF-8");
                            System.out.println("[CoreServer nhận từ " + clientSocket.getRemoteSocketAddress() + "]: " + msg);

                            // Phản hồi PONG nếu nhận PING
                            if (msg.contains("PING")) {
                                String pong = "{\"type\": \"PONG\"}";
                                byte[] pBytes = pong.getBytes("UTF-8");
                                dos.writeInt(pBytes.length);
                                dos.write(pBytes);
                                dos.flush();
                                System.out.println("[CoreServer gửi PONG]: " + pong);
                            }
                        }
                    } catch (Exception e) {
                        System.out.println("Client ngắt kết nối: " + e.getMessage());
                    }
                });
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}