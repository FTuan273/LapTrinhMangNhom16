package com.mycompany.admin.dashboard;

import java.awt.Color;

/**
 * Các trạng thái của đơn hàng và quy tắc chuyển trạng thái hợp lệ.
 *
 *   NEW -> CONFIRMED -> DELIVERING -> COMPLETED
 *     \________\_______-> CANCELLED (chỉ hủy được khi chưa giao)
 */
public enum OrderStatus {
    NEW("Mới", new Color(0x1E88E5)),
    CONFIRMED("Đã xác nhận", new Color(0x8E24AA)),
    DELIVERING("Đang giao", new Color(0xF57C00)),
    COMPLETED("Hoàn thành", new Color(0x2E7D32)),
    CANCELLED("Đã hủy", new Color(0x757575));

    public final String label;
    public final Color color;

    OrderStatus(String label, Color color) {
        this.label = label;
        this.color = color;
    }

    public boolean canChangeTo(OrderStatus next) {
        switch (this) {
            case NEW:        return next == CONFIRMED || next == CANCELLED;
            case CONFIRMED:  return next == DELIVERING || next == CANCELLED;
            case DELIVERING: return next == COMPLETED;
            default:         return false; // COMPLETED, CANCELLED là trạng thái cuối
        }
    }

    /** Chuyển mã từ server (vd "CONFIRMED") sang enum; không nhận ra thì trả về null. */
    public static OrderStatus fromCode(String code) {
        if (code == null) return null;
        try {
            return valueOf(code.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @Override
    public String toString() {
        return label;
    }
}