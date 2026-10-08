package com.be9expensphie.settlement.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Declares the reversal topics here as well as in expense-service.
 *
 * Not redundant -- it closes a startup race that stranded the consumer. Only
 * expense-service declared them, so if settlement-service's listener subscribed
 * first the broker auto-created the topic with ONE partition; expense-service's
 * KafkaAdmin then grew it to three, and the already-subscribed consumer kept its
 * assignment of partition 0 alone. The producer hashed keys across all three, so
 * every request landed on a partition nobody was listening to and every reversal
 * hung in REVERSING. Observed exactly that way, with 7 requests sitting unread
 * on partitions 1 and 2.
 *
 * Declaring in both services means whichever starts first creates the topic at
 * the right width. KafkaAdmin runs on context refresh, before listener
 * containers start, so the topic is correct before anything subscribes.
 * Re-declaring an existing topic with the same partition count is a no-op.
 *
 * Keep the counts here identical to expense-service's KafkaTopicConfig.
 */
@Configuration
public class KafkaTopicConfig {

    @Bean
    public NewTopic expenseReversalRequestsTopic() {
        return TopicBuilder.name("expense-reversal-requests").partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic expenseReversalRepliesTopic() {
        return TopicBuilder.name("expense-reversal-replies").partitions(3).replicas(1).build();
    }
}
