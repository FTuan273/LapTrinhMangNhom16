package com.mycompany.coreserver;

import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * PlainTextDemoServer - SERVER ĐỐI CHỨNG KHÔNG MÃ HÓA (chỉ dùng cho kịch bản Wireshark).
 *
 * Dùng CÙNG giao thức đóng khung (ProtocolHandler: 4 byte độ dài + JSON) như CoreServer,
 * nhưng chạy trên TCP thuần ở cổng 9999, KHÔNG có TLS.
 * Mục đích: cho giảng viên thấy nếu không có TLS thì Wireshark đọc được toàn bộ đơn hàng,
 * sau đó so sánh với cổng 8888 (TLS) chỉ thấy dữ liệu đã mã hóa.
 *
 * KHÔNG dùng server này trong hệ thống thật.
 */
public class PlainTextDemoServer {

    private static final int PORT = 9999;

    public static void main(String[] args) throws Exception {
        ExecutorService pool = Executors.newCachedThreadPool();
        try (ServerSocket server = new ServerSocket(PORT)) {
            System.out.println("[PLAIN] Server KHÔNG MÃ HÓA (đối chứng) đang lắng nghe cổng " + PORT);
            while (true) {
                Socket s = server.accept();
                s.setTcpNoDelay(true);
                pool.submit(() -> handle(s));
            }
        }
    }

    private static void handle(Socket s) {
        try (Socket socket = s) {
            DataInputStream in = new DataInputStream(socket.getInputStream());
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            while (true) {
                String msg = ProtocolHandler.readMessage(in);
                System.out.println("[PLAIN] Nhận (dạng rõ): " + msg);
                ProtocolHandler.sendMessage(out,
                        "{\"type\":\"ORDER_RESP\",\"status\":\"SUCCESS\",\"message\":\"Server doi chung KHONG ma hoa\"}");
            }
        } catch (Exception e) {
            System.out.println("[PLAIN] Client ngắt kết nối");
        }
    }
}