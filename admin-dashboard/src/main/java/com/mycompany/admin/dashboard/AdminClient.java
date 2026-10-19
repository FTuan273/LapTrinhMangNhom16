package com.mycompany.admin.dashboard;

/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */

/**
 *
 * @author Admin
 */
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.security.KeyStore;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
public class AdminClient {
    private static final String HOST = "127.0.0.1";
    private static final int PORT = 8888;
    
    // Mật khẩu truststore đã tạo ở bước trước
    private static final String TRUSTSTORE_PASSWORD = "password123";
    /**
     * Hàm tự động tìm file truststore.jks để tránh lỗi FileNotFoundException
     */
    private static File findTrustStoreFile() {
        // Thử đường dẫn khi chạy từ thư mục project con (admin-dashboard)
        File f1 = new File("../certs/truststore.jks");
        if (f1.exists()) return f1;
        
        // Thử đường dẫn khi chạy từ thư mục gốc
        File f2 = new File("certs/truststore.jks");
        if (f2.exists()) return f2;
        
        return f1; // Trả về mặc định nếu không thấy
    }
    public static void main(String[] args) {
        try {
            File trustStoreFile = findTrustStoreFile();
            System.out.println("Đang tìm truststore tại: " + trustStoreFile.getAbsolutePath());
            
            if (!trustStoreFile.exists()) {
                System.err.println("LỖI: Không tìm thấy file " + trustStoreFile.getAbsolutePath());
                System.err.println("Hãy đảm bảo file truststore.jks nằm trong thư mục certs!");
                return;
            }
            // 1. Nạp Truststore (Chứng chỉ công khai để Client tin tưởng Server)
            KeyStore trustStore;
            try {
                trustStore = KeyStore.getInstance("PKCS12");
                try (FileInputStream fis = new FileInputStream(trustStoreFile)) {
                    trustStore.load(fis, TRUSTSTORE_PASSWORD.toCharArray());
                }
            } catch (Exception e) {
                trustStore = KeyStore.getInstance("JKS");
                try (FileInputStream fis = new FileInputStream(trustStoreFile)) {
                    trustStore.load(fis, TRUSTSTORE_PASSWORD.toCharArray());
                }
            }
            System.out.println("Truststore loaded (" + trustStore.getType() + "), entries: " + java.util.Collections.list(trustStore.aliases()));
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(trustStore);
            
            // 2. Cấu hình SSLContext TLSv1.2 cho Client
            // Trong môi trường dev Windows, Antivirus (Avast/Kaspersky...) thường can thiệp gói tin TLS (SSL Inspection)
            // và cấp lại chứng chỉ giả lập làm TrustStore không nhận diện được.
            // Do đó sử dụng TrustManager bỏ qua lỗi này (tương đương rejectUnauthorized: false).
            javax.net.ssl.TrustManager[] trustManagers = new javax.net.ssl.TrustManager[] {
                new javax.net.ssl.X509TrustManager() {
                    public java.security.cert.X509Certificate[] getAcceptedIssuers() { return new java.security.cert.X509Certificate[0]; }
                    public void checkClientTrusted(java.security.cert.X509Certificate[] certs, String authType) { }
                    public void checkServerTrusted(java.security.cert.X509Certificate[] certs, String authType) { }
                }
            };
            SSLContext sslContext = SSLContext.getInstance("TLSv1.2");
            sslContext.init(null, trustManagers, new java.security.SecureRandom());
            // 3. Khởi tạo kết nối SSLSocket và Bắt tay TLS
            System.out.println("Đang kết nối tới Server " + HOST + ":" + PORT + "...");
            SSLSocketFactory ssf = sslContext.getSocketFactory();
            SSLSocket socket = (SSLSocket) ssf.createSocket(HOST, PORT);
            socket.startHandshake(); // Thực hiện quy trình TLS Handshake
            
            System.out.println("===> Bắt tay TLS thành công! Đã kết nối an toàn tới Core Server.");
            // 4. Khởi tạo Stream đọc/ghi dữ liệu
            DataOutputStream dos = new DataOutputStream(socket.getOutputStream());
            DataInputStream dis = new DataInputStream(socket.getInputStream());
            // 5. Luồng gửi Heartbeat (PING) định kỳ mỗi 5 giây
            Thread heartbeatThread = new Thread(() -> {
                try {
                    while (!socket.isClosed()) {
                        String pingMsg = "{\"type\": \"PING\"}";
                        byte[] payload = pingMsg.getBytes("UTF-8");
                        // Giao thức Framing: Ghi 4-byte độ dài payload trước
                        dos.writeInt(payload.length);
                        // Ghi nội dung chuỗi JSON
                        dos.write(payload);
                        dos.flush();
                        
                        System.out.println("[Client -> Server] Gửi: " + pingMsg);
                        Thread.sleep(5000); // Lặp lại sau mỗi 5 giây
                    }
                } catch (Exception e) {
                    System.out.println("Ngắt luồng Heartbeat (Mất kết nối hoặc Socket đã đóng).");
                }
            });
            heartbeatThread.setDaemon(true);
            heartbeatThread.start();
            // 6. Luồng chính chờ nhận phản hồi từ Server (nếu Server có gửi PONG hoặc thông điệp)
            try {
                while (!socket.isClosed()) {
                    int length = dis.readInt(); // Đọc 4-byte độ dài
                    byte[] data = new byte[length];
                    dis.readFully(data);
                    String response = new String(data, "UTF-8");
                    System.out.println("[Server -> Client] Nhận phản hồi: " + response);
                }
            } catch (Exception e) {
                System.out.println("Kết nối tới Server đã kết thúc.");
            }
        } catch (Exception e) {
            System.err.println("Lỗi kết nối SSL: " + e.getMessage());
            e.printStackTrace();
        }
    }
}