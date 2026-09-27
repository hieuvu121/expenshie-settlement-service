package com.be9expensphie.settlement.config;
import com.be9expensphie.common.event.ExpenseEvent;
import com.be9expensphie.common.event.HouseholdMemberEvent;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.support.serializer.JsonDeserializer;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class KafkaConsumerConfig {
    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Bean
    public ConsumerFactory<String, ExpenseEvent> expenseEventConsumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "settlement-service-group");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        props.put(JsonDeserializer.TRUSTED_PACKAGES, "com.be9expensphie.common.event");
        props.put(JsonDeserializer.VALUE_DEFAULT_TYPE, ExpenseEvent.class.getName());
        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, ExpenseEvent> expenseEventKafkaListenerContainerFactory() {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, ExpenseEvent>();
        factory.setConsumerFactory(expenseEventConsumerFactory());
        return factory;
    }

    /**
     * Feeds the membership projection authorization depends on.
     *
     * Its own group, independent of settlement-service-group, so replaying
     * membership does not disturb expense-events consumption.
     * auto-offset-reset=earliest comes from application.properties and is what
     * lets a fresh deployment rebuild the projection from the topic.
     */
    @Bean
    public ConsumerFactory<String, HouseholdMemberEvent> householdMemberEventConsumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "settlement-membership-group");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        props.put(JsonDeserializer.TRUSTED_PACKAGES, "com.be9expensphie.common.event");
        props.put(JsonDeserializer.VALUE_DEFAULT_TYPE, HouseholdMemberEvent.class.getName());
        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, HouseholdMemberEvent>
            householdMemberEventKafkaListenerContainerFactory() {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, HouseholdMemberEvent>();
        factory.setConsumerFactory(householdMemberEventConsumerFactory());
        return factory;
    }
}
