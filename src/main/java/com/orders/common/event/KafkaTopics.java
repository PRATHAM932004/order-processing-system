package com.orders.common.event;

public final class KafkaTopics {

    private KafkaTopics() {
        // Prevent instantiation
    }

    public static final String DLT_SUFFIX = ".DLT";

    public static final String ORDER_CREATED = "order.created";
    public static final String ORDER_INVENTORY = "order.inventory";
    public static final String ORDER_PAYMENT = "order.payment";

    public static final String ORDER_CREATED_DLT = ORDER_CREATED + DLT_SUFFIX;
    public static final String ORDER_INVENTORY_DLT = ORDER_INVENTORY + DLT_SUFFIX;
    public static final String ORDER_PAYMENT_DLT = ORDER_PAYMENT + DLT_SUFFIX;
}
