package com.mycompany.client; 

import java.io.DataOutputStream;
import java.io.FileInputStream;
import java.security.KeyStore;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

public class AdminClient {
    private static final String HOST = "127.0.0.1";
    private static final int PORT = 8888;
    
    // Đường dẫn lùi ra 1 cấp (../) để trỏ tới thư mục certs nằm ở root repo
    private static final String TRUSTSTORE_PATH = "../certs/truststore.jks"; 
    private static final String TRUSTSTORE_PASSWORD = "password123";

    public static void main(String[] args) {
        try {
            // 1. Nạp Truststore (Chứng chỉ công khai để tin tưởng Server)
            KeyStore trustStore = KeyStore.getInstance("JKS");
            trustStore.load(new FileInputStream(TRUSTSTORE_PATH), TRUSTSTORE_PASSWORD.toCharArray());

            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(trustStore);

            // 2. Thiết lập cấu hình SSLContext cho Client
            SSLContext sslContext = SSLContext.getInstance("TLSv1.2");
            sslContext.init(null, tmf.getTrustManagers(), null);

            // 3. Khởi tạo kết nối SSLSocket lên Server
            SSLSocketFactory ssf = sslContext.getSocketFactory();
            SSLSocket socket = (SSLSocket) ssf.createSocket(HOST, PORT);
            socket.startHandshake(); // Bắt buộc thực hiện quy trình bắt tay TLS
            
            System.out.println("Đã bắt tay TLS thành công và kết nối tới Core Server!");

            // 4. Khởi tạo luồng dữ liệu (DataOutputStream)
            DataOutputStream dos = new DataOutputStream(socket.getOutputStream());
            
            // 5. Khởi tạo luồng chạy ngầm (Heartbeat Thread) gửi PING mỗi 5 giây
            Thread heartbeatThread = new Thread(() -> {
                try {
                    while (true) {
                        String pingMsg = "{\"type\": \"PING\"}";
                        
                        // Thực hiện Framing đơn giản: Ghi 4-byte độ dài trước
                        dos.writeInt(pingMsg.getBytes("UTF-8").length);
                        // Sau đó mới ghi chuỗi JSON payload
                        dos.write(pingMsg.getBytes("UTF-8"));
                        dos.flush();
                        
                        System.out.println("Client gửi: " + pingMsg);
                        Thread.sleep(5000); // Ngủ 5 giây rồi lặp lại
                    }
                } catch (Exception e) {
                    System.out.println("Mất kết nối tới Server.");
                }
            });
            
            heartbeatThread.start(); // Bắt đầu chạy luồng Heartbeat

        } catch (Exception e) {
            System.err.println("Lỗi kết nối SSL: " + e.getMessage());
            e.printStackTrace();
        }
    }
}