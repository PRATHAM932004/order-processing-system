package com.orders.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.orders.common.event.KafkaTopics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;

@Configuration
public class KafkaConfig {

    @Value("${orders.kafka.topic-partitions:1}")
    private int topicPartitions;

    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        return objectMapper;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        return factory;
    }

    @Bean
    public NewTopic orderCreatedTopic() {
        return TopicBuilder.name(KafkaTopics.ORDER_CREATED)
                .partitions(topicPartitions)
                .build();
    }

    @Bean
    public NewTopic orderInventoryTopic() {
        return TopicBuilder.name(KafkaTopics.ORDER_INVENTORY)
                .partitions(topicPartitions)
                .build();
    }

    @Bean
    public NewTopic orderPaymentTopic() {
        return TopicBuilder.name(KafkaTopics.ORDER_PAYMENT)
                .partitions(topicPartitions)
                .build();
    }
}
