package com.patientping.hl7.inbound.kafka;

import com.patientping.ejb.outbound.kafka.KafkaSenderBean;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.AbstractMessageListenerContainer;
import org.springframework.kafka.listener.BatchAcknowledgingMessageListener;
import org.springframework.kafka.listener.KafkaMessageListenerContainer;
import org.springframework.kafka.listener.MessageListener;
import org.springframework.kafka.listener.config.ContainerProperties;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;

@Configuration
@EnableKafka
public class KafkaConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger(KafkaConfig.class);

    @Value("${kafka.active:false}")
    private boolean kafkaActive;

    @Value("${ping.messaging.kafka.servers:localhost:9092}")
    private String kafkaServers;

    @Value("${ping.messaging.kafka.consumer_group_id:ping}")
    private String consumerGroupId;

    @Bean
    @ConfigurationProperties(prefix = "kafka")
    public KafkaProperties kafkaProperties() {
        return new KafkaProperties();
    }

    @Bean
    public ConsumerFactory<Integer, String> consumerFactory() {
        final Map<String, Object> properties = transformOverriddenProperties(kafkaProperties().getConsumer());
        return new DefaultKafkaConsumerFactory<>(properties);
    }

    public KafkaMessageListenerContainer<Integer, String> buildSingleRecordConsumer(String topic,
                                                                                    final MessageListener<Integer, String> messageListener) {
        return buildKafkaMessageListenerContainer(topic, messageListener,
                AbstractMessageListenerContainer.AckMode.RECORD);
    }

    public KafkaMessageListenerContainer<Integer, String> buildBatchConsumer(String topic,
                                                                             BatchAcknowledgingMessageListener<Integer, String> messageListener) {
        return buildKafkaMessageListenerContainer(topic, messageListener,
                AbstractMessageListenerContainer.AckMode.MANUAL_IMMEDIATE);
    }

    /**
     * Helper method to build a {@link KafkaMessageListenerContainer}. Will respect the kafka.active property (if
     * kafka.active = false, will return a noOp {@link KafkaMessageListenerContainer})
     */
    private KafkaMessageListenerContainer<Integer, String> buildKafkaMessageListenerContainer(String topic,
                                                                                              Object messageListener,
                                                                                              final AbstractMessageListenerContainer.AckMode ackMode) {
        final ContainerProperties containerProperties = new ContainerProperties(topic);
        containerProperties.setAckMode(ackMode);
        containerProperties.setMessageListener(messageListener);

        if (!kafkaActive) {
            LOGGER.info("Kafka not active. No listener for {}", topic);
            return new KafkaMessageListenerContainer<Integer, String>(consumerFactory(), containerProperties) {
                @Override
                protected void doStart() {
                }

                @Override
                public boolean isRunning() {
                    return false;
                }
            };
        }

        final KafkaMessageListenerContainer<Integer, String> container = new KafkaMessageListenerContainer<>(
                consumerFactory(), containerProperties);
        container.setAutoStartup(false);
        return container;
    }

    public KafkaSenderBean buildKafkaSender() {
        final Map<String, Object> pros = transformOverriddenProperties(kafkaProperties().getProducer());
        Properties properties = new Properties();
        properties.putAll(pros);
        return new KafkaSenderBean(kafkaActive, properties);
    }

    private Map<String, Object> transformOverriddenProperties(Map<String, Object> properties) {
        properties = properties.entrySet()
                .stream()
                .collect(Collectors.toMap(
                        entry -> {
                            String key = entry.getKey();
                            if (key.startsWith("override_")) {
                                key = key.replace("override_", "").replace("_", ".");
                            }
                            return key;
                        },
                        Map.Entry::getValue
                ));
        return properties;
    }

    public class KafkaProperties {
        private HashMap<String, Object> consumer = new HashMap<>();
        private HashMap<String, Object> producer = new HashMap<>();

        public KafkaProperties() {
            consumer.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaServers);
            consumer.put(ConsumerConfig.GROUP_ID_CONFIG, consumerGroupId);
            consumer.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
            consumer.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);

            producer.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaServers);
            producer.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
            producer.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        }

        public HashMap<String, Object> getConsumer() {
            return consumer;
        }

        public HashMap<String, Object> getProducer() {
            return producer;
        }
    }
}
