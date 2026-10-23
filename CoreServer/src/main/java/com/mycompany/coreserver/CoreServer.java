package com.mycompany.coreserver;

import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.KeyStore;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSocket;

/**
 * CoreServer - Java Core Security Server (Lõi kỹ thuật chính)
 * Phụ trách: Phan Anh Tuấn (MSSV: 2280603576)
 *
 * Chức năng cốt lõi:
 * 1. Tự cấu hình SSLContext với keystore/truststore (TLSv1.2) an toàn.
 * 2. Khởi tạo SSLServerSocket đa luồng (ExecutorService Thread Pool).
 * 3. Quản lý danh sách phiên kết nối đồng thời (Session Management).
 * 4. Tích hợp module ProtocolHandler (4-byte length header + JSON framing).
 * 5. Xử lý Heartbeat PING/PONG để duy trì Persistent Connection.
 * 6. Định tuyến (Routing) và Phát sóng (Broadcast) đơn hàng thời gian thực (Real-time)
 *    giữa Web Backend (Node.js) và Admin Dashboard (Java Client).
 */
public class CoreServer {
    private static final int PORT = 8888;
    private static final String KEYSTORE_PASSWORD = "password123";

    // Danh sách các Client đang kết nối an toàn (Thread-safe collection)
    private static final List<ClientHandler> activeClients = new CopyOnWriteArrayList<>();
    private static final AtomicInteger clientIdCounter = new AtomicInteger(1000);

    /**
     * Tự động dò tìm file keystore.jks ở các vị trí tương đối
     */
    private static File findKeyStoreFile() {
        File f1 = new File("../certs/keystore.jks");
        if (f1.exists()) return f1;
        File f2 = new File("certs/keystore.jks");
        if (f2.exists()) return f2;
        File f3 = new File("CoreServer/certs/keystore.jks");
        if (f3.exists()) return f3;
        return f1;
    }

    public static void main(String[] args) {
        try {
            File keyStoreFile = findKeyStoreFile();
            System.out.println("===============================================================");
            System.out.println("    CORE SECURITY SERVER - HỆ THỐNG ĐẶT ĐỒ ĂN TRỰC TUYẾN");
            System.out.println("    Phụ trách: Phan Anh Tuấn (2280603576)");
            System.out.println("===============================================================");
            System.out.println("[CoreServer] Đang tìm keystore tại: " + keyStoreFile.getAbsolutePath());

            if (!keyStoreFile.exists()) {
                System.err.println("LỖI: Không tìm thấy file " + keyStoreFile.getAbsolutePath());
                System.err.println("Vui lòng đảm bảo keystore.jks tồn tại trong thư mục certs!");
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

            System.out.println("[CoreServer] TLS Server khởi động thành công trên cổng: " + PORT);
            System.out.println("[CoreServer] Sẵn sàng tiếp nhận kết nối bảo mật qua SSL/TLS...");
            System.out.println("---------------------------------------------------------------");

            // 5. Khởi tạo Thread Pool để xử lý đa luồng (hỗ trợ nhiều kết nối đồng thời)
            ExecutorService executor = Executors.newCachedThreadPool();

            while (true) {
                Socket clientSocket = serverSocket.accept();
                int clientId = clientIdCounter.incrementAndGet();
                System.out.println("\n[CoreServer] Client mới kết nối từ: " + clientSocket.getRemoteSocketAddress() + " (ID: #" + clientId + ")");

                ClientHandler handler = new ClientHandler(clientSocket, clientId);
                executor.submit(handler);
            }
        } catch (Exception e) {
            System.err.println("[CoreServer] Lỗi nghiêm trọng tại Server: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Phát sóng (Broadcast) một thông điệp JSON tới TẤT CẢ các Client đang kết nối
     */
    public static void broadcast(String jsonMessage) {
        System.out.println("[CoreServer BROADCAST -> ALL (" + activeClients.size() + " clients)]: " + jsonMessage);
        for (ClientHandler client : activeClients) {
            try {
                client.sendMessage(jsonMessage);
            } catch (IOException e) {
                System.err.println("[CoreServer] Lỗi gửi broadcast tới Client #" + client.getClientId() + ": " + e.getMessage());
            }
        }
    }

    /**
     * Phát sóng tới các Client có vai trò ADMIN (hoặc chưa phân loại)
     */
    public static void broadcastToAdmins(String jsonMessage) {
        System.out.println("[CoreServer BROADCAST -> ADMINS]: " + jsonMessage);
        for (ClientHandler client : activeClients) {
            // Gửi cho các client role ADMIN hoặc client đang duy trì kết nối chờ nhận thông báo
            if ("ADMIN".equalsIgnoreCase(client.getRole()) || "UNKNOWN".equalsIgnoreCase(client.getRole())) {
                try {
                    client.sendMessage(jsonMessage);
                } catch (IOException e) {
                    System.err.println("[CoreServer] Lỗi gửi thông báo đơn tới Admin #" + client.getClientId() + ": " + e.getMessage());
                }
            }
        }
    }

    /**
     * Lớp ClientHandler quản lý từng phiên kết nối khách hàng
     */
    public static class ClientHandler implements Runnable {
        private final Socket socket;
        private final int clientId;
        private DataInputStream dis;
        private DataOutputStream dos;
        private String role = "UNKNOWN"; // UNKNOWN, ADMIN, WEB_BACKEND

        public ClientHandler(Socket socket, int clientId) {
            this.socket = socket;
            this.clientId = clientId;
        }

        public int getClientId() {
            return clientId;
        }

        public String getRole() {
            return role;
        }

        public void setRole(String role) {
            this.role = role;
        }

        public synchronized void sendMessage(String jsonMessage) throws IOException {
            if (dos != null && !socket.isClosed()) {
                ProtocolHandler.sendMessage(dos, jsonMessage);
            }
        }

        @Override
        public void run() {
            try {
                // Thực hiện bắt tay TLS nếu là SSLSocket
                if (socket instanceof SSLSocket) {
                    SSLSocket sslSocket = (SSLSocket) socket;
                    sslSocket.startHandshake();
                    System.out.println("[CoreServer] Bắt tay TLS thành công với Client #" + clientId + " (" + sslSocket.getRemoteSocketAddress() + ")");
                }

                dis = new DataInputStream(socket.getInputStream());
                dos = new DataOutputStream(socket.getOutputStream());

                // Đăng ký client vào danh sách quản lý phiên
                activeClients.add(this);
                System.out.println("[CoreServer] Đã đăng ký Client #" + clientId + " vào hệ thống. Số lượng client online: " + activeClients.size());

                // Vòng lặp đọc gói tin sử dụng Module ProtocolHandler (Framing 4-byte + JSON)
                while (!socket.isClosed()) {
                    String msg;
                    try {
                        msg = ProtocolHandler.readMessage(dis);
                    } catch (EOFException eof) {
                        System.out.println("[CoreServer] Client #" + clientId + " đã đóng kết nối (EOF).");
                        break;
                    } catch (IOException ioe) {
                        System.out.println("[CoreServer] Lỗi luồng đọc từ Client #" + clientId + ": " + ioe.getMessage());
                        break;
                    }

                    if (msg == null) break;

                    System.out.println("[CoreServer NHẬN từ #" + clientId + " (" + role + ")]: " + msg);
                    processRequest(msg);
                }
            } catch (Exception e) {
                System.out.println("[CoreServer] Phiên Client #" + clientId + " kết thúc (" + e.getMessage() + ")");
            } finally {
                cleanup();
            }
        }

        /**
         * Bộ định tuyến thông điệp (Message Router)
         */
        private void processRequest(String msg) {
            try {
                // 1. XỬ LÝ HEARTBEAT (PING/PONG)
                if (msg.contains("\"PING\"") || msg.contains("\"type\": \"PING\"") || msg.contains("\"type\":\"PING\"")) {
                    // Nếu client gửi Heartbeat PING thường xuyên, nhận diện là AdminClient (Persistent Connection)
                    if ("UNKNOWN".equals(this.role)) {
                        this.role = "ADMIN";
                    }
                    String pong = "{\"type\":\"PONG\",\"timestamp\":" + System.currentTimeMillis() + "}";
                    sendMessage(pong);
                    System.out.println("[CoreServer -> #" + clientId + "]: Phản hồi PONG");
                    return;
                }

                // 2. XỬ LÝ ĐĂNG KÝ VAI TRÒ (REGISTER)
                if (msg.contains("\"REGISTER\"") || msg.contains("\"role\": \"ADMIN\"") || msg.contains("\"role\":\"ADMIN\"")) {
                    this.role = "ADMIN";
                    String resp = "{\"type\":\"REGISTER_RESP\",\"status\":\"SUCCESS\",\"role\":\"ADMIN\",\"message\":\"Đã đăng ký vai trò ADMIN Dashboard thành công\"}";
                    sendMessage(resp);
                    System.out.println("[CoreServer] Client #" + clientId + " đã xác định là ADMIN Dashboard.");
                    return;
                }

                // 3. XỬ LÝ ĐƠN HÀNG MỚI TỪ WEB CLIENT (ORDER_REQ)
                if (msg.contains("\"ORDER_REQ\"") || msg.contains("\"type\": \"ORDER_REQ\"") || msg.contains("\"type\":\"ORDER_REQ\"")) {
                    if ("UNKNOWN".equals(this.role)) {
                        this.role = "WEB_BACKEND";
                    }

                    String orderId = extractField(msg, "orderId");
                    if (orderId == null || orderId.isEmpty()) {
                        orderId = "ORD-" + System.currentTimeMillis();
                    }

                    System.out.println("\n---------------------------------------------------------------");
                    System.out.println(">>> [ĐƠN HÀNG MỚI ĐƯỢC TIẾP NHẬN QUA KÊNH BẢO MẬT TLS] <<<");
                    System.out.println("Mã đơn hàng: " + orderId);
                    System.out.println("Nguồn gốc: Client #" + clientId + " (" + socket.getRemoteSocketAddress() + ")");
                    System.out.println("Chi tiết: " + msg);
                    System.out.println("---------------------------------------------------------------\n");

                    // a) Phản hồi xác nhận cho Web Client gửi đơn
                    String resp = "{"
                            + "\"type\":\"ORDER_RESP\","
                            + "\"status\":\"SUCCESS\","
                            + "\"orderId\":\"" + orderId + "\","
                            + "\"message\":\"Đơn hàng đã được Core Security Server tiếp nhận an toàn qua TLS\","
                            + "\"timestamp\":" + System.currentTimeMillis()
                            + "}";
                    sendMessage(resp);

                    // b) BROADCAST phát sóng đơn hàng mới ngay lập tức tới các Admin Dashboard
                    String broadcastOrderMsg = "{"
                            + "\"type\":\"NEW_ORDER_NOTIFICATION\","
                            + "\"orderId\":\"" + orderId + "\","
                            + "\"timestamp\":" + System.currentTimeMillis() + ","
                            + "\"payload\":" + (msg.trim().startsWith("{") ? msg.trim() : "\"" + msg + "\"")
                            + "}";
                    broadcastToAdmins(broadcastOrderMsg);
                    return;
                }

                // 4. XỬ LÝ CẬP NHẬT TRẠNG THÁI TỪ ADMIN (STATUS_UPDATE)
                if (msg.contains("\"STATUS_UPDATE\"") || msg.contains("\"type\": \"STATUS_UPDATE\"") || msg.contains("\"type\":\"STATUS_UPDATE\"")) {
                    String orderId = extractField(msg, "orderId");
                    String newStatus = extractField(msg, "status");
                    if (newStatus == null) newStatus = "CONFIRMED";

                    System.out.println("[CoreServer] Admin #" + clientId + " cập nhật đơn hàng [" + orderId + "] -> " + newStatus);

                    // a) Phản hồi cho Admin
                    String resp = "{"
                            + "\"type\":\"STATUS_UPDATE_RESP\","
                            + "\"status\":\"SUCCESS\","
                            + "\"orderId\":\"" + orderId + "\","
                            + "\"newStatus\":\"" + newStatus + "\""
                            + "}";
                    sendMessage(resp);

                    // b) Broadcast cập nhật tới toàn bộ hệ thống
                    String broadcastUpdate = "{"
                            + "\"type\":\"ORDER_STATUS_CHANGED\","
                            + "\"orderId\":\"" + orderId + "\","
                            + "\"status\":\"" + newStatus + "\","
                            + "\"timestamp\":" + System.currentTimeMillis()
                            + "}";
                    broadcast(broadcastUpdate);
                    return;
                }

                // 5. CÁC GÓI TIN KHÁC (VD: TEST_FRAMING TỪ client.js)
                String genericResp = "{"
                        + "\"type\":\"ACK\","
                        + "\"status\":\"SUCCESS\","
                        + "\"clientId\":#" + clientId + ","
                        + "\"message\":\"CoreServer đã nhận thông điệp qua kênh mã hóa TLS\","
                        + "\"timestamp\":" + System.currentTimeMillis()
                        + "}";
                sendMessage(genericResp);

            } catch (Exception e) {
                System.err.println("[CoreServer] Lỗi xử lý gói tin từ Client #" + clientId + ": " + e.getMessage());
            }
        }

        /**
         * Trích xuất giá trị trường đơn giản từ chuỗi JSON (không cần phụ thuộc thư viện ngoài)
         */
        private String extractField(String json, String key) {
            try {
                String pattern = "\"" + key + "\"\\s*:\\s*\"?([^,\"}]+)\"?";
                java.util.regex.Pattern p = java.util.regex.Pattern.compile(pattern);
                java.util.regex.Matcher m = p.matcher(json);
                if (m.find()) {
                    return m.group(1).trim();
                }
            } catch (Exception ignored) {}
            return null;
        }

        private void cleanup() {
            activeClients.remove(this);
            System.out.println("[CoreServer] Đã hủy đăng ký Client #" + clientId + ". Số lượng client online còn lại: " + activeClients.size());
            try {
                if (socket != null && !socket.isClosed()) {
                    socket.close();
                }
            } catch (IOException ignored) {}
        }
    }
}
