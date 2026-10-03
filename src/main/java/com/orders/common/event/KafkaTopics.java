package com.orders.common.event;

public final class KafkaTopics {

    private KafkaTopics() {
        // Prevent instantiation
    }

    public static final String ORDER_CREATED = "order.created";
    public static final String ORDER_INVENTORY = "order.inventory";
    public static final String ORDER_PAYMENT = "order.payment";
}
