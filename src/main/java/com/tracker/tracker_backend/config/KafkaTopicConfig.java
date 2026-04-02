package com.tracker.tracker_backend.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicConfig {

    @Value("${flight.kafka.topic.state-updates}")
    private String stateUpdatesTopic;

    @Value("${flight.kafka.topic.notifications}")
    private String notificationsTopic;

    @Value("${flight.kafka.topic.notifications-dlq}")
    private String notificationsDlqTopic;

    @Value("${flight.kafka.topic.notification-sent}")
    private String notificationSentTopic;

    // 4 partitions — one consumer thread per partition in the matcher
    @Bean
    public NewTopic flightStateUpdatesTopic() {
        return TopicBuilder.name(stateUpdatesTopic).partitions(4).replicas(1).build();
    }

    @Bean
    public NewTopic flightNotificationsTopic() {
        return TopicBuilder.name(notificationsTopic).partitions(4).replicas(1).build();
    }

    @Bean
    public NewTopic flightNotificationsDlqTopic() {
        return TopicBuilder.name(notificationsDlqTopic).partitions(1).replicas(1).build();
    }

    @Bean
    public NewTopic notificationSentTopic() {
        return TopicBuilder.name(notificationSentTopic).partitions(4).replicas(1).build();
    }
}
