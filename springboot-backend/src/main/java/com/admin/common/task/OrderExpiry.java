package com.admin.common.task;

import com.admin.entity.OrderRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class OrderExpiry {
    public static final long PAYMENT_WINDOW_MS = 15 * 60 * 1000L;
    private final JdbcTemplate jdbc;

    public OrderExpiry(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public static boolean isExpired(OrderRecord order, long now) {
        return order.getCreatedTime() == null || order.getCreatedTime() <= now - PAYMENT_WINDOW_MS;
    }

    @Scheduled(fixedDelay = 30000, initialDelay = 30000)
    public void closeExpiredOrders() {
        long now = System.currentTimeMillis();
        // Conditional update never overwrites a paid order. Empty links prevent accidental reuse.
        jdbc.update("UPDATE order_record SET status=2,pay_url='',updated_time=? " +
                "WHERE status=0 AND (created_time IS NULL OR created_time<=?)", now, now - PAYMENT_WINDOW_MS);
    }
}
