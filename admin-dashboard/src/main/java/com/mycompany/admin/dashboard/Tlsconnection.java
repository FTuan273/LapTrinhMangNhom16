package com.mycompany.admin.dashboard;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

/**
 * TlsConnection - Tầng mạng của Admin Dashboard (KHÔNG chứa code giao diện).
 *
 * - Nạp truststore.jks và XÁC THỰC chứng chỉ server thật sự (không trust-all).
 * - Bắt tay TLS, đọc/ghi frame [4-byte độ dài Big-Endian] + [JSON UTF-8].
 * - Heartbeat: gửi PING mỗi 5 giây.
 * - Phát hiện mất kết nối: nếu 15 giây không nhận được byte nào (kể cả PONG)
 *   thì read() ném SocketTimeoutException -> đóng socket.
 * - Tự kết nối lại với thời gian chờ tăng dần 1s, 2s, 4s ... tối đa 30s.
 *
 * Mọi callback của Listener được gọi trên luồng mạng, KHÔNG phải luồng giao diện.
 * Lớp giao diện phải tự chuyển sang Event Dispatch Thread bằng SwingUtilities.invokeLater.
 */
public class TlsConnection {

    public enum State { CONNECTING, CONNECTED, DISCONNECTED }

    public interface Listener {
        void onStateChanged(State state, String detail);
        void onMessage(Map<String, Object> message, String rawJson);
    }

    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int HEARTBEAT_INTERVAL_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 15_000;     // 3 nhịp heartbeat
    private static final int MAX_FRAME_SIZE = 64 * 1024;   // 64 KB là quá đủ cho 1 đơn hàng
    private static final int MAX_BACKOFF_SECONDS = 30;

    private final String host;
    private final int port;
    private final SSLSocketFactory socketFactory;
    private final Listener listener;
    private final ScheduledExecutorService heartbeat =
            Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "admin-heartbeat"));

    private volatile boolean running;
    private volatile SSLSocket socket;
    private DataOutputStream out; // chỉ truy cập trong khối synchronized

    public TlsConnection(String host, int port, SSLSocketFactory socketFactory, Listener listener) {
        this.host = host;
        this.port = port;
        this.socketFactory = socketFactory;
        this.listener = listener;
    }

    /** Tạo SSLSocketFactory tin tưởng DUY NHẤT các chứng chỉ có trong truststore. */
    public static SSLSocketFactory createSocketFactory(File trustStoreFile, char[] password) throws Exception {
        KeyStore trustStore = loadKeyStore(trustStoreFile, password);
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);

        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, tmf.getTrustManagers(), null); // dùng TrustManager thật từ truststore
        return ctx.getSocketFactory();
    }

    private static KeyStore loadKeyStore(File file, char[] password) throws Exception {
        Exception last = null;
        for (String type : new String[] {"PKCS12", "JKS"}) {
            try (FileInputStream fis = new FileInputStream(file)) {
                KeyStore ks = KeyStore.getInstance(type);
                ks.load(fis, password);
                return ks;
            } catch (Exception e) {
                last = e;
            }
        }
        throw new IOException("Không đọc được truststore " + file + ": " + last.getMessage(), last);
    }

    // ------------------------------------------------------------------

    public void start() {
        if (running) return;
        running = true;
        Thread io = new Thread(this::connectLoop, "admin-tls-io");
        io.setDaemon(true);
        io.start();

        heartbeat.scheduleAtFixedRate(() -> {
            if (socket != null) {
                try {
                    send("{\"type\":\"PING\"}");
                } catch (IOException ignored) {
                    // luồng đọc sẽ tự phát hiện lỗi và kết nối lại
                }
            }
        }, HEARTBEAT_INTERVAL_MS, HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    public void stop() {
        running = false;
        heartbeat.shutdownNow();
        closeSocket();
    }

    /**
     * Gửi 1 frame JSON. synchronized để luồng heartbeat và luồng xử lý nút bấm
     * không ghi xen kẽ vào cùng socket (sẽ làm hỏng frame).
     */
    public synchronized void send(String json) throws IOException {
        if (out == null) throw new IOException("Chưa kết nối tới Core Server");
        byte[] payload = json.getBytes(StandardCharsets.UTF_8);
        out.writeInt(payload.length);
        out.write(payload);
        out.flush();
    }

    // ------------------------------------------------------------------

    private void connectLoop() {
        int backoff = 1;
        while (running) {
            listener.onStateChanged(State.CONNECTING, "Đang kết nối tới " + host + ":" + port + "...");
            String reason;
            try {
                SSLSocket s = (SSLSocket) socketFactory.createSocket();
                socket = s;
                s.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
                s.setSoTimeout(READ_TIMEOUT_MS); // áp dụng cho cả handshake lẫn các lần read sau

                // Bật dòng dưới SAU KHI sinh lại chứng chỉ có SAN khớp host (xem README):
                // kiểm tra thêm tên miền/IP của server, chống giả mạo bằng chứng chỉ hợp lệ khác.
                // javax.net.ssl.SSLParameters p = s.getSSLParameters();
                // p.setEndpointIdentificationAlgorithm("HTTPS");
                // s.setSSLParameters(p);

                s.startHandshake(); // sai chứng chỉ -> SSLHandshakeException tại đây
                SSLSession session = s.getSession();

                DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
                synchronized (this) {
                    out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));
                }
                backoff = 1;
                listener.onStateChanged(State.CONNECTED,
                        session.getProtocol() + " • " + session.getCipherSuite());

                // Khai báo vai trò ngay sau khi kết nối
                send("{\"type\":\"REGISTER\",\"role\":\"ADMIN\"}");
                readLoop(in);
                reason = "Server đã đóng kết nối";
            } catch (SocketTimeoutException e) {
                reason = "Quá " + READ_TIMEOUT_MS / 1000 + "s không nhận được phản hồi (mất heartbeat)";
            } catch (EOFException e) {
                reason = "Server đã đóng kết nối";
            } catch (javax.net.ssl.SSLHandshakeException e) {
                reason = "Bắt tay TLS thất bại (chứng chỉ không tin cậy?): " + e.getMessage();
            } catch (IOException e) {
                reason = e.getMessage();
            } finally {
                closeSocket();
            }

            if (!running) break;
            listener.onStateChanged(State.DISCONNECTED, reason + " — thử lại sau " + backoff + "s");
            sleepSeconds(backoff);
            backoff = Math.min(backoff * 2, MAX_BACKOFF_SECONDS);
        }
        listener.onStateChanged(State.DISCONNECTED, "Đã dừng");
    }

    private void readLoop(DataInputStream in) throws IOException {
        while (running) {
            int length = in.readInt();
            if (length < 0 || length > MAX_FRAME_SIZE) {
                throw new IOException("Frame không hợp lệ, độ dài = " + length);
            }
            byte[] data = new byte[length];
            in.readFully(data);
            String json = new String(data, StandardCharsets.UTF_8);

            Map<String, Object> msg;
            try {
                msg = MiniJson.parseObject(json);
            } catch (IllegalArgumentException badJson) {
                // Không ngắt kết nối vì 1 gói lỗi định dạng; chuyển nguyên văn lên để ghi log
                msg = null;
            }
            listener.onMessage(msg, json);
        }
    }

    private void closeSocket() {
        synchronized (this) {
            out = null;
        }
        SSLSocket s = socket;
        socket = null;
        if (s != null) {
            try { s.close(); } catch (IOException ignored) { }
        }
    }

    private static void sleepSeconds(int seconds) {
        try {
            Thread.sleep(seconds * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }
}