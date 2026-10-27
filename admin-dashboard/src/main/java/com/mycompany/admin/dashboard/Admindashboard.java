package com.mycompany.admin.dashboard;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.table.DefaultTableCellRenderer;

/**
 * AdminDashboard - Giao diện Swing cho nhà hàng.
 *
 * Nguyên tắc luồng (rất hay bị hỏi khi vấn đáp):
 *  - Luồng mạng (TlsConnection) KHÔNG được chạm vào component Swing.
 *    Mọi cập nhật giao diện đi qua SwingUtilities.invokeLater -> Event Dispatch Thread (EDT).
 *  - Ngược lại, EDT KHÔNG được làm I/O mạng (sẽ đơ giao diện).
 *    Khi bấm nút, việc gửi gói tin được đẩy sang luồng 'sender'.
 *
 * Chạy:  java -cp <classes> com.mycompany.admin.dashboard.AdminDashboard [host] [port]
 * Mật khẩu truststore lấy từ biến môi trường TRUSTSTORE_PASSWORD (mặc định password123 khi dev).
 */
public class AdminDashboard extends JFrame implements TlsConnection.Listener {

    private static final DateTimeFormatter LOG_TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final OrderTableModel tableModel = new OrderTableModel();
    private final JTable table = new JTable(tableModel);
    private final JTextArea detailArea = new JTextArea();
    private final JTextArea logArea = new JTextArea(7, 80);
    private final JLabel connDot = new JLabel("●");
    private final JLabel connText = new JLabel("Chưa kết nối");
    private final JLabel heartbeatLabel = new JLabel("Heartbeat: —");
    private final JLabel statsLabel = new JLabel();
    private final Map<OrderStatus, JButton> actionButtons = new EnumMap<>(OrderStatus.class);

    private final ExecutorService sender = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "admin-sender");
        t.setDaemon(true);
        return t;
    });

    private final String serverLabel;
    private TlsConnection connection;
    private boolean connected;
    private int unseenOrders;

    public AdminDashboard(String host, int port) {
        super("Admin Dashboard - Web Food (Nhóm 16)");
        this.serverLabel = host + ":" + port;
        buildUi();
        refreshStats();
        updateButtons();
    }

    public void attach(TlsConnection connection) {
        this.connection = connection;
    }

    // ==================================================================
    //  XÂY DỰNG GIAO DIỆN
    // ==================================================================

    private void buildUi() {
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if (connection != null) connection.stop();
                dispose();
                System.exit(0);
            }

            @Override
            public void windowActivated(WindowEvent e) {
                unseenOrders = 0;
                updateTitle();
            }
        });

        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        root.add(buildStatusBar(), BorderLayout.NORTH);

        JSplitPane center = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, buildTablePanel(), buildDetailPanel());
        center.setResizeWeight(0.65);

        JSplitPane main = new JSplitPane(JSplitPane.VERTICAL_SPLIT, center, buildLogPanel());
        main.setResizeWeight(0.75);
        root.add(main, BorderLayout.CENTER);

        setContentPane(root);
        setSize(1150, 680);
        setLocationRelativeTo(null);
    }

    private JPanel buildStatusBar() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(0xDDDDDD)),
                BorderFactory.createEmptyBorder(4, 4, 8, 4)));

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        connDot.setFont(connDot.getFont().deriveFont(18f));
        connDot.setForeground(Color.GRAY);
        JLabel server = new JLabel("Core Server " + serverLabel + "  |");
        left.add(connDot);
        left.add(server);
        left.add(connText);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 16, 0));
        right.add(statsLabel);
        right.add(heartbeatLabel);

        bar.add(left, BorderLayout.WEST);
        bar.add(right, BorderLayout.EAST);
        return bar;
    }

    private JPanel buildTablePanel() {
        table.setRowHeight(26);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setAutoCreateRowSorter(false);
        table.getTableHeader().setReorderingAllowed(false);
        table.getColumnModel().getColumn(0).setPreferredWidth(140);
        table.getColumnModel().getColumn(OrderTableModel.COL_STATUS)
             .setCellRenderer(new StatusRenderer());
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                showSelectedDetail();
                updateButtons();
            }
        });

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        addActionButton(buttons, OrderStatus.CONFIRMED, "✔ Xác nhận");
        addActionButton(buttons, OrderStatus.DELIVERING, "🛵 Đang giao");
        addActionButton(buttons, OrderStatus.COMPLETED, "★ Hoàn thành");
        addActionButton(buttons, OrderStatus.CANCELLED, "✖ Hủy đơn");

        JPanel panel = new JPanel(new BorderLayout(0, 6));
        panel.setBorder(BorderFactory.createTitledBorder("Đơn hàng"));
        panel.add(new JScrollPane(table), BorderLayout.CENTER);
        panel.add(buttons, BorderLayout.SOUTH);
        return panel;
    }

    private void addActionButton(JPanel container, OrderStatus target, String text) {
        JButton b = new JButton(text);
        b.addActionListener(e -> requestStatusChange(target));
        actionButtons.put(target, b);
        container.add(b);
    }

    private JPanel buildDetailPanel() {
        detailArea.setEditable(false);
        detailArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        detailArea.setText("Chọn một đơn hàng để xem chi tiết.");
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Chi tiết đơn hàng"));
        panel.add(new JScrollPane(detailArea), BorderLayout.CENTER);
        panel.setPreferredSize(new Dimension(380, 300));
        return panel;
    }

    private JPanel buildLogPanel() {
        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Nhật ký kết nối"));
        panel.add(new JScrollPane(logArea), BorderLayout.CENTER);
        return panel;
    }

    // ==================================================================
    //  CALLBACK TỪ TẦNG MẠNG (đang chạy trên luồng mạng!)
    // ==================================================================

    @Override
    public void onStateChanged(TlsConnection.State state, String detail) {
        SwingUtilities.invokeLater(() -> {
            connected = (state == TlsConnection.State.CONNECTED);
            switch (state) {
                case CONNECTED:
                    connDot.setForeground(new Color(0x2E7D32));
                    connText.setText("Đã kết nối an toàn • " + detail);
                    log("Bắt tay TLS thành công: " + detail);
                    break;
                case CONNECTING:
                    connDot.setForeground(new Color(0xF9A825));
                    connText.setText("Đang kết nối...");
                    log(detail);
                    break;
                default:
                    connDot.setForeground(new Color(0xC62828));
                    connText.setText("Mất kết nối");
                    heartbeatLabel.setText("Heartbeat: —");
                    log("Mất kết nối: " + detail);
            }
            updateButtons();
        });
    }

    @Override
    public void onMessage(Map<String, Object> msg, String rawJson) {
        SwingUtilities.invokeLater(() -> handleMessage(msg, rawJson));
    }

    // ==================================================================
    //  XỬ LÝ THÔNG ĐIỆP (đã ở trên EDT)
    // ==================================================================

    private void handleMessage(Map<String, Object> msg, String raw) {
        if (msg == null) {
            log("⚠ Gói tin không phải JSON hợp lệ: " + raw);
            return;
        }
        String type = String.valueOf(msg.get("type"));
        switch (type) {
            case "PONG":
                heartbeatLabel.setText("Heartbeat: PONG lúc " + LocalTime.now().format(LOG_TIME));
                break;

            case "REGISTER_RESP":
                log("Server xác nhận vai trò: " + msg.get("role"));
                break;

            case "NEW_ORDER_NOTIFICATION":
                onNewOrder(Order.fromNotification(msg));
                break;

            case "STATUS_UPDATE_RESP":
                log("Server đã nhận cập nhật đơn " + msg.get("orderId") + " -> " + msg.get("newStatus"));
                break;

            case "ORDER_STATUS_CHANGED":
                onStatusChanged(String.valueOf(msg.get("orderId")), String.valueOf(msg.get("status")));
                break;

            default:
                log("Nhận [" + type + "]: " + raw);
        }
    }

    private void onNewOrder(Order order) {
        if (!tableModel.addOrder(order)) return;
        log("🔔 ĐƠN MỚI " + order.orderId + " - " + order.customer + " - " + Order.money(order.total));
        Toolkit.getDefaultToolkit().beep();
        table.setRowSelectionInterval(0, 0);
        if (!isActive()) {
            unseenOrders++;
            updateTitle();
        }
        refreshStats();
    }

    /** Server phát ORDER_STATUS_CHANGED cho mọi Admin -> nhiều Dashboard luôn đồng bộ. */
    private void onStatusChanged(String orderId, String statusCode) {
        OrderStatus status = OrderStatus.fromCode(statusCode);
        if (status == null) {
            log("⚠ Trạng thái không xác định '" + statusCode + "' cho đơn " + orderId);
            return;
        }
        if (tableModel.updateStatus(orderId, status)) {
            log("Đơn " + orderId + " chuyển sang: " + status.label);
            showSelectedDetail();
            updateButtons();
            refreshStats();
        } else {
            log("Bỏ qua cập nhật cho đơn không có trong bảng: " + orderId);
        }
    }

    // ==================================================================
    //  THAO TÁC NGƯỜI DÙNG
    // ==================================================================

    private void requestStatusChange(OrderStatus target) {
        Order order = getSelectedOrder();
        if (order == null) return;

        if (target == OrderStatus.CANCELLED) {
            int ok = JOptionPane.showConfirmDialog(this,
                    "Hủy đơn " + order.orderId + " của " + order.customer + "?",
                    "Xác nhận hủy", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (ok != JOptionPane.YES_OPTION) return;
        }

        String json = "{\"type\":\"STATUS_UPDATE\""
                + ",\"orderId\":" + MiniJson.quote(order.orderId)
                + ",\"status\":" + MiniJson.quote(target.name())
                + "}";

        // Bảng chỉ đổi màu khi server phát lại ORDER_STATUS_CHANGED,
        // nên giao diện luôn phản ánh đúng trạng thái trên server.
        log("Gửi STATUS_UPDATE: " + order.orderId + " -> " + target.name());
        sender.submit(() -> {
            try {
                connection.send(json);
            } catch (IOException ex) {
                SwingUtilities.invokeLater(() -> {
                    log("✖ Gửi thất bại: " + ex.getMessage());
                    JOptionPane.showMessageDialog(this, "Không gửi được: " + ex.getMessage(),
                            "Lỗi kết nối", JOptionPane.ERROR_MESSAGE);
                });
            }
        });
    }

    // ==================================================================
    //  TIỆN ÍCH GIAO DIỆN
    // ==================================================================

    private Order getSelectedOrder() {
        int row = table.getSelectedRow();
        return row < 0 ? null : tableModel.getOrderAt(table.convertRowIndexToModel(row));
    }

    private void showSelectedDetail() {
        Order o = getSelectedOrder();
        detailArea.setText(o == null ? "Chọn một đơn hàng để xem chi tiết." : o.toDetailText());
        detailArea.setCaretPosition(0);
    }

    /** Chỉ bật các nút ứng với bước chuyển trạng thái hợp lệ, và chỉ khi đang kết nối. */
    private void updateButtons() {
        Order o = getSelectedOrder();
        for (Map.Entry<OrderStatus, JButton> e : actionButtons.entrySet()) {
            boolean enabled = connected && o != null && o.getStatus().canChangeTo(e.getKey());
            e.getValue().setEnabled(enabled);
        }
    }

    private void refreshStats() {
        statsLabel.setText("Tổng: " + tableModel.getRowCount()
                + "  |  Mới: " + tableModel.countByStatus(OrderStatus.NEW)
                + "  |  Đang giao: " + tableModel.countByStatus(OrderStatus.DELIVERING));
    }

    private void updateTitle() {
        String base = "Admin Dashboard - Web Food (Nhóm 16)";
        setTitle(unseenOrders > 0 ? "(" + unseenOrders + " đơn mới) " + base : base);
    }

    private void log(String line) {
        logArea.append("[" + LocalTime.now().format(LOG_TIME) + "] " + line + "\n");
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }

    /** Tô màu cột Trạng thái. */
    private static class StatusRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object value, boolean selected,
                                                       boolean focus, int row, int col) {
            super.getTableCellRendererComponent(t, value, selected, focus, row, col);
            setHorizontalAlignment(SwingConstants.CENTER);
            setFont(getFont().deriveFont(Font.BOLD));
            if (value instanceof OrderStatus && !selected) {
                setForeground(((OrderStatus) value).color);
            }
            return this;
        }
    }

    // ==================================================================
    //  MAIN
    // ==================================================================

    private static File findTrustStore() {
        String custom = System.getProperty("truststore");
        if (custom != null) return new File(custom);
        for (String p : new String[] {"certs/truststore.jks", "../certs/truststore.jks"}) {
            File f = new File(p);
            if (f.exists()) return f;
        }
        return new File("certs/truststore.jks");
    }

    public static void main(String[] args) {
        String host = args.length > 0 ? args[0] : "127.0.0.1";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 8888;
        String envPass = System.getenv("TRUSTSTORE_PASSWORD");
        char[] password = (envPass != null ? envPass : "password123").toCharArray();

        try {
            UIManager.setLookAndFeel("javax.swing.plaf.nimbus.NimbusLookAndFeel");
        } catch (Exception ignored) {
            // dùng giao diện mặc định
        }

        File trustStore = findTrustStore();
        final javax.net.ssl.SSLSocketFactory factory;
        try {
            factory = TlsConnection.createSocketFactory(trustStore, password);
        } catch (Exception e) {
            JOptionPane.showMessageDialog(null,
                    "Không nạp được truststore:\n" + trustStore.getAbsolutePath() + "\n\n" + e.getMessage(),
                    "Lỗi cấu hình TLS", JOptionPane.ERROR_MESSAGE);
            return;
        }

        SwingUtilities.invokeLater(() -> {
            AdminDashboard ui = new AdminDashboard(host, port);
            TlsConnection conn = new TlsConnection(host, port, factory, ui);
            ui.attach(conn);
            ui.setVisible(true);
            ui.log("Đã nạp truststore: " + trustStore.getAbsolutePath());
            conn.start();
        });
    }
}