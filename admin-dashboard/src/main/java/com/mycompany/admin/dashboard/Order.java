package com.mycompany.admin.dashboard;

import java.text.NumberFormat;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Dữ liệu 1 đơn hàng hiển thị trên Dashboard. */
public class Order {

    public static class Item {
        public final String name;
        public final long quantity;
        public final long price;

        Item(String name, long quantity, long price) {
            this.name = name;
            this.quantity = quantity;
            this.price = price;
        }
    }

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final NumberFormat VND = NumberFormat.getInstance(new Locale("vi", "VN"));

    public final String orderId;
    public final String customer;
    public final String phone;
    public final String address;
    public final String paymentMethod;
    public final long total;
    public final List<Item> items;
    public final LocalTime receivedAt;
    private OrderStatus status = OrderStatus.NEW;

    private Order(String orderId, String customer, String phone, String address,
                  String paymentMethod, long total, List<Item> items) {
        this.orderId = orderId;
        this.customer = customer;
        this.phone = phone;
        this.address = address;
        this.paymentMethod = paymentMethod;
        this.total = total;
        this.items = items;
        this.receivedAt = LocalTime.now();
    }

    /**
     * Tạo Order từ gói NEW_ORDER_NOTIFICATION:
     * {"type":"NEW_ORDER_NOTIFICATION","orderId":"...","payload":{ ...ORDER_REQ gốc... }}
     */
    @SuppressWarnings("unchecked")
    public static Order fromNotification(Map<String, Object> msg) {
        Object p = msg.get("payload");
        Map<String, Object> payload = (p instanceof Map) ? (Map<String, Object>) p : msg;

        List<Item> items = new ArrayList<>();
        Object rawItems = payload.get("items");
        if (rawItems instanceof List) {
            for (Object o : (List<Object>) rawItems) {
                if (o instanceof Map) {
                    Map<String, Object> m = (Map<String, Object>) o;
                    items.add(new Item(str(m.get("name")), num(m.get("quantity")), num(m.get("price"))));
                }
            }
        }

        String orderId = str(msg.get("orderId"));
        if (orderId.isEmpty()) orderId = str(payload.get("orderId"));

        return new Order(orderId,
                str(payload.get("customer")),
                str(payload.get("phone")),
                str(payload.get("address")),
                str(payload.get("paymentMethod")),
                num(payload.get("total")),
                items);
    }

    public OrderStatus getStatus() {
        return status;
    }

    public void setStatus(OrderStatus status) {
        this.status = status;
    }

    public String receivedAtText() {
        return receivedAt.format(TIME_FMT);
    }

    public static String money(long amount) {
        return VND.format(amount) + " đ";
    }

    /** Nội dung hiển thị ở khung "Chi tiết đơn hàng". */
    public String toDetailText() {
        StringBuilder sb = new StringBuilder();
        sb.append("Mã đơn:      ").append(orderId).append('\n');
        sb.append("Nhận lúc:    ").append(receivedAtText()).append('\n');
        sb.append("Trạng thái:  ").append(status.label).append("\n\n");
        sb.append("Khách hàng:  ").append(customer).append('\n');
        sb.append("Điện thoại:  ").append(phone).append('\n');
        sb.append("Địa chỉ:     ").append(address).append('\n');
        sb.append("Thanh toán:  ").append(paymentMethod).append("\n\n");
        sb.append("Món đã đặt:\n");
        for (Item it : items) {
            sb.append("  • ").append(it.quantity).append(" x ").append(it.name)
              .append("  (").append(money(it.price)).append(")\n");
        }
        sb.append("\nTỔNG CỘNG:   ").append(money(total));
        return sb.toString();
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static long num(Object o) {
        if (o instanceof Number) return ((Number) o).longValue();
        try {
            return Long.parseLong(String.valueOf(o));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}