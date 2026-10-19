package com.mycompany.coreserver;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.KeyStore;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocketFactory;

/**
 * ProtocolHandlerTest: Kiểm thử toàn diện module của Triều.
 * 
 * 1. Chế độ kiểm thử tự động (mặc định):
 *    Kiểm tra Framing, Chống phân mảnh TCP (Chunking), và Multi-frame.
 * 
 * 2. Chế độ Server TLS (chạy với tham số 'server' hoặc '--server'):
 *    Khởi chạy TLS Server tại port 8888 để giao tiếp trực tiếp với Node.js client.js.
 */
public class ProtocolHandlerTest {

    public static void main(String[] args) {
        if (args.length > 0 && (args[0].equalsIgnoreCase("server") || args[0].equalsIgnoreCase("--server"))) {
            startTlsServer();
        } else {
            runUnitTests();
        }
    }

    /**
     * Chạy các bài test logic độc lập
     */
    private static void runUnitTests() {
        System.out.println("=======================================================");
        System.out.println("  KIỂM THỬ TỰ ĐỘNG PROTOCOL HANDLER (TRIỀU)  ");
        System.out.println("=======================================================\n");

        testBasicFraming();
        testChunkingResistance();
        testMultipleFrames();

        System.out.println("=======================================================");
        System.out.println("  TẤT CẢ 3 BÀI KIỂM THỬ ĐÃ VƯỢT QUA XUẤT SẮC!  ");
        System.out.println("=======================================================");
        System.out.println("\n[Mẹo] Để chạy Demo TLS Server giao tiếp với Node.js client.js, hãy chạy:");
        System.out.println("java -cp CoreServer/target/classes com.mycompany.coreserver.ProtocolHandlerTest server\n");
    }

    /**
     * 1. Kiểm thử đóng khung và giải khung cơ bản
     */
    private static void testBasicFraming() {
        System.out.println("[Test 1] Kiểm thử đóng khung và giải mã gói tin JSON Unicode...");
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);

            String originalJson = "{\"sender\":\"Triều\",\"action\":\"TEST\",\"message\":\"Tiếng Việt có dấu: Ắ, Ằ, Ộ, Đ, Ư\"}";
            ProtocolHandler.sendMessage(dos, originalJson);

            byte[] rawBytes = baos.toByteArray();
            System.out.println("  -> Kích thước toàn bộ gói tin (gồm 4-byte header): " + rawBytes.length + " bytes");

            ByteArrayInputStream bais = new ByteArrayInputStream(rawBytes);
            DataInputStream dis = new DataInputStream(bais);

            String parsedJson = ProtocolHandler.readMessage(dis);
            if (!originalJson.equals(parsedJson)) {
                throw new RuntimeException("Nội dung giải mã không khớp!");
            }
            System.out.println("  -> Giải mã thành công: " + parsedJson);
            System.out.println("[Test 1] PASSED!\n");
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * 2. Kiểm thử chống Chunking (Mô phỏng mạng TCP phân mảnh 1 byte / lần đọc)
     */
    private static void testChunkingResistance() {
        System.out.println("[Test 2] Kiểm thử chống hiện tượng TCP Chunking (Mạng phân mảnh từng byte)...");
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);

            String testPayload = "{\"message\":\"Dữ liệu bị chia nhỏ thành nhiều mảnh TCP nhỏ lẻ\"}";
            ProtocolHandler.sendMessage(dos, testPayload);

            byte[] rawBytes = baos.toByteArray();

            // Tạo InputStream mô phỏng mạng bị chunking (chỉ trả về tối đa 1 byte mỗi lần gọi read)
            InputStream chunkedInputStream = new InputStream() {
                private int index = 0;

                @Override
                public int read() {
                    if (index >= rawBytes.length) return -1;
                    return rawBytes[index++] & 0xFF;
                }

                @Override
                public int read(byte[] b, int off, int len) {
                    if (index >= rawBytes.length) return -1;
                    b[off] = rawBytes[index++];
                    return 1;
                }
            };

            DataInputStream dis = new DataInputStream(chunkedInputStream);

            // readMessage bên trong dùng readFully() nên tự động gom đủ toàn bộ byte
            String result = ProtocolHandler.readMessage(dis);
            if (!testPayload.equals(result)) {
                throw new RuntimeException("Không đọc đủ dữ liệu do chunking!");
            }
            System.out.println("  -> Đọc thành công dữ liệu dù bị phân mảnh: " + result);
            System.out.println("[Test 2] PASSED (Chống chunking hoạt động hoàn hảo)!\n");
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * 3. Kiểm thử gửi nhiều frame liên tiếp trong cùng một luồng stream
     */
    private static void testMultipleFrames() {
        System.out.println("[Test 3] Kiểm thử đọc liên tiếp nhiều frame (Back-to-back frames)...");
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);

            ProtocolHandler.sendMessage(dos, "{\"frame\": 1}");
            ProtocolHandler.sendMessage(dos, "{\"frame\": 2}");
            ProtocolHandler.sendMessage(dos, "{\"frame\": 3}");

            ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
            DataInputStream dis = new DataInputStream(bais);

            String f1 = ProtocolHandler.readMessage(dis);
            String f2 = ProtocolHandler.readMessage(dis);
            String f3 = ProtocolHandler.readMessage(dis);

            System.out.println("  -> Nhận Frame 1: " + f1);
            System.out.println("  -> Nhận Frame 2: " + f2);
            System.out.println("  -> Nhận Frame 3: " + f3);

            if (!f1.contains("\"frame\": 1") || !f2.contains("\"frame\": 2") || !f3.contains("\"frame\": 3")) {
                throw new RuntimeException("Thứ tự các frame không khớp!");
            }
            System.out.println("[Test 3] PASSED!\n");
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * Khởi chạy Server TLS tại port 8888 để kết nối với Node.js client.js
     */
    private static void startTlsServer() {
        int port = 8888;
        String keystorePath = findKeystorePath();
        String password = "password123";

        System.out.println("=======================================================");
        System.out.println("  KHỞI CHẠY TLS DEMO SERVER CHO PROTOCOL HANDLER");
        System.out.println("=======================================================");
        System.out.println("Keystore: " + keystorePath);
        System.out.println("Cổng: " + port);

        try {
            KeyStore keyStore = KeyStore.getInstance("JKS");
            keyStore.load(new FileInputStream(keystorePath), password.toCharArray());

            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keyStore, password.toCharArray());

            SSLContext sslContext = SSLContext.getInstance("TLSv1.2");
            sslContext.init(kmf.getKeyManagers(), null, null);

            SSLServerSocketFactory ssf = sslContext.getServerSocketFactory();
            ServerSocket serverSocket = ssf.createServerSocket(port);

            System.out.println("[Server] Đang lắng nghe kết nối TLS an toàn tại port " + port + "...");
            System.out.println("[Server] Hãy mở terminal khác và gõ: node client.js\n");

            ExecutorService pool = Executors.newCachedThreadPool();

            while (true) {
                Socket socket = serverSocket.accept();
                System.out.println("[Server] Client đã kết nối: " + socket.getRemoteSocketAddress());
                pool.submit(() -> handleClientSocket(socket));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void handleClientSocket(Socket socket) {
        try (
            DataInputStream in = new DataInputStream(socket.getInputStream());
            DataOutputStream out = new DataOutputStream(socket.getOutputStream())
        ) {
            while (!socket.isClosed()) {
                // Đọc gói tin bằng ProtocolHandler của Triều
                String requestJson = ProtocolHandler.readMessage(in);
                System.out.println("[Server] Nhận JSON hợp lệ: " + requestJson);

                // Tạo phản hồi echo JSON
                String responseJson = String.format(
                    "{\"status\":\"SUCCESS\",\"timestamp\":%d,\"echo\":%s}",
                    System.currentTimeMillis(),
                    (requestJson.trim().startsWith("{") && requestJson.trim().endsWith("}")) ? requestJson.trim() : "\"" + requestJson.replace("\"", "\\\"") + "\""
                );

                // Gửi lại client bằng ProtocolHandler
                ProtocolHandler.sendMessage(out, responseJson);
                System.out.println("[Server] Đã gửi phản hồi về client: " + responseJson);
            }
        } catch (Exception e) {
            System.out.println("[Server] Client kết thúc phiên (" + e.getMessage() + ")");
        } finally {
            try { socket.close(); } catch (Exception ignored) {}
        }
    }

    private static String findKeystorePath() {
        String[] paths = {
            "certs/keystore.jks",
            "../certs/keystore.jks",
            "CoreServer/certs/keystore.jks",
            "C:\\Users\\Admin\\Downloads\\LapTrinhMangNhom16\\certs\\keystore.jks"
        };
        for (String p : paths) {
            if (new File(p).exists()) return p;
        }
        return "certs/keystore.jks";
    }
}
