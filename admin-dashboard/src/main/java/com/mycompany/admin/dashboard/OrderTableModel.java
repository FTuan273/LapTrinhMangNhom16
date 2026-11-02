package com.mycompany.admin.dashboard;

import java.util.ArrayList;
import java.util.List;
import javax.swing.table.AbstractTableModel;

/**
 * Model cho JTable: giữ danh sách đơn hàng.
 * CHỈ được gọi từ Event Dispatch Thread (luồng giao diện Swing).
 */
public class OrderTableModel extends AbstractTableModel {

    public static final int COL_STATUS = 6;

    private static final String[] COLUMNS = {
        "Mã đơn", "Giờ nhận", "Khách hàng", "Điện thoại", "Tổng tiền", "Thanh toán", "Trạng thái"
    };

    private final List<Order> orders = new ArrayList<>();

    /** Thêm đơn mới lên đầu bảng. Trả về false nếu đơn đã tồn tại. */
    public boolean addOrder(Order order) {
        if (findIndex(order.orderId) >= 0) return false;
        orders.add(0, order);
        fireTableRowsInserted(0, 0);
        return true;
    }

    /** Cập nhật trạng thái; trả về false nếu không có đơn này trong bảng. */
    public boolean updateStatus(String orderId, OrderStatus status) {
        int i = findIndex(orderId);
        if (i < 0) return false;
        orders.get(i).setStatus(status);
        fireTableRowsUpdated(i, i);
        return true;
    }

    public Order getOrderAt(int row) {
        return orders.get(row);
    }

    public int findIndex(String orderId) {
        for (int i = 0; i < orders.size(); i++) {
            if (orders.get(i).orderId.equals(orderId)) return i;
        }
        return -1;
    }

    public long countByStatus(OrderStatus status) {
        return orders.stream().filter(o -> o.getStatus() == status).count();
    }

    @Override
    public int getRowCount() {
        return orders.size();
    }

    @Override
    public int getColumnCount() {
        return COLUMNS.length;
    }

    @Override
    public String getColumnName(int col) {
        return COLUMNS[col];
    }

    @Override
    public Object getValueAt(int row, int col) {
        Order o = orders.get(row);
        switch (col) {
            case 0: return o.orderId;
            case 1: return o.receivedAtText();
            case 2: return o.customer;
            case 3: return o.phone;
            case 4: return Order.money(o.total);
            case 5: return o.paymentMethod;
            case 6: return o.getStatus();
            default: return "";
        }
    }
}