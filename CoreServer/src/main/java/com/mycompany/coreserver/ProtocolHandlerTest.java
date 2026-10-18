package com.mycompany.coreserver;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.IOException;

/**
 * ProtocolHandlerTest: Kiểm thử độc lập khả năng đóng khung (Framing)
 * và khả năng chống phân mảnh gói tin (TCP Chunking / Fragmentation).
 */
public class ProtocolHandlerTest {

    public static void main(String[] args) {
        System.out.println("========== BẮT ĐẦU KIỂM THỬ PROTOCOL HANDLER ==========\n");

        testBasicFraming();
        testChunkingResistance();
        testMultipleFrames();

        System.out.println("\n========== TẤT CẢ KIỂM THỬ ĐÃ THÀNH CÔNG! ==========");
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
            System.out.println("-> Kích thước toàn bộ gói tin (gồm 4-byte header): " + rawBytes.length + " bytes");

            ByteArrayInputStream bais = new ByteArrayInputStream(rawBytes);
            DataInputStream dis = new DataInputStream(bais);

            String parsedJson = ProtocolHandler.readMessage(dis);
            assert originalJson.equals(parsedJson) : "Nội dung JSON giải mã không khớp!";
            System.out.println("-> Giải mã thành công: " + parsedJson);
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
                    // Cố tình chỉ đọc 1 byte để mô phỏng chunking nặng
                    b[off] = rawBytes[index++];
                    return 1;
                }
            };

            DataInputStream dis = new DataInputStream(chunkedInputStream);

            // readMessage bên trong dùng readFully() nên phải tự động gom đủ toàn bộ byte
            String result = ProtocolHandler.readMessage(dis);
            assert testPayload.equals(result) : "Không đọc đủ dữ liệu do chunking!";
            System.out.println("-> Đọc thành công dữ liệu dù bị phân mảnh: " + result);
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

            System.out.println("-> Nhận Frame 1: " + f1);
            System.out.println("-> Nhận Frame 2: " + f2);
            System.out.println("-> Nhận Frame 3: " + f3);

            assert f1.contains("\"frame\": 1") && f2.contains("\"frame\": 2") && f3.contains("\"frame\": 3");
            System.out.println("[Test 3] PASSED!\n");
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }
}
