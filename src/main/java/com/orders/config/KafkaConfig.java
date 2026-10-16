package com.orders.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.orders.common.event.KafkaTopics;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

@Configuration
@Slf4j
public class KafkaConfig {

    @Value("${orders.kafka.topic-partitions:1}")
    private int topicPartitions;

    @Value("${orders.kafka.retry.max-attempts:3}")
    private int retryMaxAttempts;

    @Value("${orders.kafka.retry.initial-interval-ms:1000}")
    private long retryInitialIntervalMs;

    @Value("${orders.kafka.retry.multiplier:2.0}")
    private double retryMultiplier;

    @Value("${orders.kafka.retry.max-interval-ms:10000}")
    private long retryMaxIntervalMs;

    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        return objectMapper;
    }

    @Bean
    public DeadLetterPublishingRecoverer deadLetterPublishingRecoverer(KafkaTemplate<String, String> kafkaTemplate) {
        return new DeadLetterPublishingRecoverer(kafkaTemplate, (record, exception) -> {
            log.warn("Recovering failed record from topic '{}' partition {} offset {} to DLT. Error: {}",
                    record.topic(), record.partition(), record.offset(), exception.getMessage());
            return new TopicPartition(record.topic() + KafkaTopics.DLT_SUFFIX, -1);
        });
    }

    @Bean
    public DefaultErrorHandler defaultErrorHandler(DeadLetterPublishingRecoverer deadLetterPublishingRecoverer) {
        int maxRetries = Math.max(0, retryMaxAttempts - 1);
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(maxRetries);
        backOff.setInitialInterval(retryInitialIntervalMs);
        backOff.setMultiplier(retryMultiplier);
        backOff.setMaxInterval(retryMaxIntervalMs);

        DefaultErrorHandler errorHandler = new DefaultErrorHandler(deadLetterPublishingRecoverer, backOff);
        errorHandler.setCommitRecovered(true);
        errorHandler.setAckAfterHandle(true);
        errorHandler.setRetryListeners((record, ex, deliveryAttempt) ->
                log.warn("Retry attempt {} for message on topic '{}' partition {} offset {} failed: {}",
                        deliveryAttempt, record.topic(), record.partition(), record.offset(), ex.getMessage())
        );
        return errorHandler;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory,
            DefaultErrorHandler defaultErrorHandler) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        factory.setCommonErrorHandler(defaultErrorHandler);
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

    @Bean
    public NewTopic orderCreatedDltTopic() {
        return TopicBuilder.name(KafkaTopics.ORDER_CREATED_DLT)
                .partitions(topicPartitions)
                .build();
    }

    @Bean
    public NewTopic orderInventoryDltTopic() {
        return TopicBuilder.name(KafkaTopics.ORDER_INVENTORY_DLT)
                .partitions(topicPartitions)
                .build();
    }

    @Bean
    public NewTopic orderPaymentDltTopic() {
        return TopicBuilder.name(KafkaTopics.ORDER_PAYMENT_DLT)
                .partitions(topicPartitions)
                .build();
    }
}
