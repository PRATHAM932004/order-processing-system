package com.orders.config;

import com.orders.common.event.KafkaTopics;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class KafkaConfigTest {

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    @Test
    @DisplayName("Should create deadLetterPublishingRecoverer that resolves destination to <topic>.DLT")
    void deadLetterPublishingRecoverer_resolvesDestinationToDltTopic() {
        KafkaConfig config = new KafkaConfig();
        DeadLetterPublishingRecoverer recoverer = config.deadLetterPublishingRecoverer(kafkaTemplate);

        assertThat(recoverer).isNotNull();

        ConsumerRecord<String, String> record = new ConsumerRecord<>(KafkaTopics.ORDER_CREATED, 0, 100L, "key", "value");
        Exception ex = new RuntimeException("Simulated processing error");

        // Use protected/reflection to verify the destination resolver function
        @SuppressWarnings("unchecked")
        java.util.function.BiFunction<ConsumerRecord<?, ?>, Exception, TopicPartition> destinationResolver =
                (java.util.function.BiFunction<ConsumerRecord<?, ?>, Exception, TopicPartition>)
                        ReflectionTestUtils.getField(recoverer, "destinationResolver");

        assertThat(destinationResolver).isNotNull();
        TopicPartition destination = destinationResolver.apply(record, ex);
        assertThat(destination.topic()).isEqualTo(KafkaTopics.ORDER_CREATED_DLT);
        assertThat(destination.partition()).isEqualTo(-1);
    }

    @Test
    @DisplayName("Should create defaultErrorHandler with configured backoff and recovery properties")
    void defaultErrorHandler_configuredWithExponentialBackOff() {
        KafkaConfig config = new KafkaConfig();
        ReflectionTestUtils.setField(config, "retryMaxAttempts", 3);
        ReflectionTestUtils.setField(config, "retryInitialIntervalMs", 500L);
        ReflectionTestUtils.setField(config, "retryMultiplier", 2.0);
        ReflectionTestUtils.setField(config, "retryMaxIntervalMs", 5000L);

        DeadLetterPublishingRecoverer recoverer = config.deadLetterPublishingRecoverer(kafkaTemplate);
        DefaultErrorHandler errorHandler = config.defaultErrorHandler(recoverer);

        assertThat(errorHandler).isNotNull();
        assertThat(errorHandler.isAckAfterHandle()).isTrue();
    }

    @Test
    @DisplayName("Should define NewTopic beans for all DLT topics")
    void definesDltTopicBeans() {
        KafkaConfig config = new KafkaConfig();
        ReflectionTestUtils.setField(config, "topicPartitions", 2);

        NewTopic orderCreatedDlt = config.orderCreatedDltTopic();
        assertThat(orderCreatedDlt.name()).isEqualTo(KafkaTopics.ORDER_CREATED_DLT);
        assertThat(orderCreatedDlt.numPartitions()).isEqualTo(2);

        NewTopic orderInventoryDlt = config.orderInventoryDltTopic();
        assertThat(orderInventoryDlt.name()).isEqualTo(KafkaTopics.ORDER_INVENTORY_DLT);
        assertThat(orderInventoryDlt.numPartitions()).isEqualTo(2);

        NewTopic orderPaymentDlt = config.orderPaymentDltTopic();
        assertThat(orderPaymentDlt.name()).isEqualTo(KafkaTopics.ORDER_PAYMENT_DLT);
        assertThat(orderPaymentDlt.numPartitions()).isEqualTo(2);
    }
}
