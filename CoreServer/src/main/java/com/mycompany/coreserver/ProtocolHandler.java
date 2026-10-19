package com.mycompany.coreserver;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Module ProtocolHandler: Xử lý Framing cho giao thức tùy biến trên nền TCP/TLS.
 * Phụ trách: Triều (Java Core: Stream Parsing & Custom Protocol)
 * 
 * Cấu trúc gói tin:
 * [4-byte Header (Big-Endian int): Độ dài Payload N] + [N bytes: Dữ liệu JSON (UTF-8)]
 * 
 * - Đọc/ghi luồng I/O an toàn sử dụng DataInputStream và DataOutputStream.
 * - Xử lý Framing: Đọc đúng 4-byte header để tính kích thước, sau đó dùng readFully()
 *   đọc chính xác số byte của JSON payload, chống hiện tượng TCP chunking / fragmentation.
 */
public class ProtocolHandler {

    // Giới hạn độ dài gói tin tối đa (10 MB) để bảo vệ server chống tràn bộ nhớ / DoS
    public static final int MAX_PACKET_SIZE = 10 * 1024 * 1024;

    /**
     * Đóng khung và gửi mảng byte thô qua DataOutputStream.
     * Cấu trúc: [4-byte Big-Endian độ dài] + [payload bytes]
     * 
     * @param out Luồng DataOutputStream
     * @param payload Mảng byte cần gửi
     * @throws IOException khi gặp lỗi ghi luồng socket
     */
    public static void sendPacket(DataOutputStream out, byte[] payload) throws IOException {
        if (payload == null) {
            payload = new byte[0];
        }

        // 1. Ghi 4-byte header chứa kích thước payload (chuẩn Big-Endian)
        out.writeInt(payload.length);

        // 2. Ghi chính xác toàn bộ payload
        if (payload.length > 0) {
            out.write(payload);
        }

        // 3. Đẩy dữ liệu qua socket ngay lập tức (tránh bị nghẽn buffer)
        out.flush();
    }

    /**
     * Đọc và giải khung mảng byte từ DataInputStream (chống TCP stream chunking).
     * 
     * @param in Luồng DataInputStream
     * @return Mảng byte payload nhận được
     * @throws EOFException khi phía client đóng kết nối
     * @throws IOException khi kích thước gói tin không hợp lệ hoặc lỗi I/O
     */
    public static byte[] readPacket(DataInputStream in) throws IOException {
        // 1. Đọc đúng 4-byte header (Big-Endian int) để xác định kích thước payload
        int length = in.readInt();

        // Kiểm tra kích thước hợp lệ để chống tấn công tràn bộ nhớ
        if (length < 0 || length > MAX_PACKET_SIZE) {
            throw new IOException("Kích thước gói tin không hợp lệ: " + length + " bytes (Giới hạn: " + MAX_PACKET_SIZE + " bytes)");
        }

        if (length == 0) {
            return new byte[0];
        }

        // 2. Cấp phát buffer đúng bằng kích thước payload
        byte[] buffer = new byte[length];

        // 3. readFully đảm bảo đọc chính xác và đủ 'length' bytes, tự động lặp khi TCP bị phân mảnh (anti-chunking)
        in.readFully(buffer);

        return buffer;
    }

    /**
     * Đóng khung và gửi chuỗi tin nhắn JSON qua DataOutputStream.
     * 
     * @param out Luồng ghi DataOutputStream của Socket
     * @param jsonMessage Chuỗi JSON cần gửi
     * @throws IOException khi gặp lỗi ghi luồng
     */
    public static void sendMessage(DataOutputStream out, String jsonMessage) throws IOException {
        if (jsonMessage == null) {
            jsonMessage = "{}";
        }

        // Chuyển chuỗi JSON thành mảng byte UTF-8
        byte[] payload = jsonMessage.getBytes(StandardCharsets.UTF_8);

        // Gửi gói tin đã đóng khung
        sendPacket(out, payload);
    }

    /**
     * Đọc và giải khung chuỗi tin nhắn JSON từ DataInputStream.
     * 
     * @param in Luồng đọc DataInputStream của Socket
     * @return Chuỗi JSON đọc được (UTF-8)
     * @throws EOFException khi đầu bên kia đóng kết nối
     * @throws IOException khi gặp lỗi dữ liệu hoặc vượt quá kích thước hợp lệ
     */
    public static String readMessage(DataInputStream in) throws IOException {
        byte[] payload = readPacket(in);
        return new String(payload, StandardCharsets.UTF_8);
    }
}
