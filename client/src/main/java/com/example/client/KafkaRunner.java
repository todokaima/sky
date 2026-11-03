package com.example.client;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.stereotype.Component;

/**
 * Reads one message of one topic for a Kafka card, read only: the consumer is handed the topic's
 * partitions itself (assign + seek, no group.id), so it never joins a consumer group, never commits an
 * offset and never creates a topic. Nothing here can produce.
 */
@Component
public class KafkaRunner {

    private static final int MAX_TIMEOUT_SECONDS = 300;
    private static final Duration POLL = Duration.ofMillis(500);
    private static final DateTimeFormatter AT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault());
    // Set here to keep the connection read only; a config's properties can't change them
    private static final Set<String> FIXED = Set.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, ConsumerConfig.GROUP_ID_CONFIG, ConsumerConfig.GROUP_INSTANCE_ID_CONFIG,
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG,
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG);

    record Message(String topic, int partition, long offset, long timestamp, String key, String value, Map<String, String> headers) {

        // "orders [2] @ 1534 · 2026-10-20 14:02:11.123 · key abc", then one line per header
        String describe() {
            StringBuilder out = new StringBuilder()
                    .append(topic).append(" [").append(partition).append("] @ ").append(offset)
                    .append(" · ").append(AT.format(Instant.ofEpochMilli(timestamp)))
                    .append(" · key ").append(key == null ? "(none)" : key);
            if (value == null) {
                out.append(" · no value (tombstone)");
            }
            headers.forEach((name, header) -> out.append('\n').append(name).append(": ").append(header));
            return out.toString();
        }
    }

    /**
     * The newest message on the topic (latest timestamp over all partitions), or with next the first
     * message that arrives after this call, waiting up to timeoutSeconds. Empty when there's none.
     */
    public Optional<Message> read(KafkaConfig config, String topic, boolean next, int timeoutSeconds) {
        Instant deadline = Instant.now().plusSeconds(Math.max(1, Math.min(MAX_TIMEOUT_SECONDS, timeoutSeconds)));
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(properties(config))) {
            List<PartitionInfo> info = consumer.partitionsFor(topic, left(deadline));
            if (info == null || info.isEmpty()) {
                throw new IllegalArgumentException("there's no topic " + topic + " (or this user can't see it)");
            }
            List<TopicPartition> partitions = info.stream().map(p -> new TopicPartition(topic, p.partition())).toList();
            return next ? next(consumer, partitions, deadline) : latest(consumer, partitions, deadline);
        }
    }

    // The last record of each partition, then the newest of those
    private Optional<Message> latest(KafkaConsumer<String, String> consumer, List<TopicPartition> partitions, Instant deadline) {
        Map<TopicPartition, Long> ends = consumer.endOffsets(partitions, left(deadline));
        Map<TopicPartition, Long> begins = consumer.beginningOffsets(partitions, left(deadline));
        List<TopicPartition> filled = partitions.stream().filter(tp -> ends.get(tp) > begins.get(tp)).toList();
        if (filled.isEmpty()) {
            return Optional.empty();
        }
        consumer.assign(filled);
        filled.forEach(tp -> consumer.seek(tp, ends.get(tp) - 1));
        Map<TopicPartition, ConsumerRecord<String, String>> last = new HashMap<>();
        // A partition is done once its position reaches the end (its last offset can be a transaction
        // marker, which comes back as no record at all)
        Set<TopicPartition> pending = new HashSet<>(filled);
        while (!pending.isEmpty() && Instant.now().isBefore(deadline)) {
            for (ConsumerRecord<String, String> record : consumer.poll(poll(deadline))) {
                last.merge(new TopicPartition(record.topic(), record.partition()), record,
                        (a, b) -> b.offset() > a.offset() ? b : a);
            }
            pending.removeIf(tp -> consumer.position(tp) >= ends.get(tp));
        }
        if (last.isEmpty() && !pending.isEmpty()) {
            throw new IllegalArgumentException("timed out before the last message of " + partitions.getFirst().topic() + " came in");
        }
        return last.values().stream()
                .max(Comparator.comparingLong((ConsumerRecord<String, String> r) -> r.timestamp()).thenComparingLong(ConsumerRecord::offset))
                .map(KafkaRunner::message);
    }

    // Wait at the end of every partition for the first new record
    private Optional<Message> next(KafkaConsumer<String, String> consumer, List<TopicPartition> partitions, Instant deadline) {
        consumer.assign(partitions);
        consumer.seekToEnd(partitions);
        // seekToEnd is lazy: look the ends up now, so a message sent from here on counts as new
        partitions.forEach(tp -> consumer.position(tp, left(deadline)));
        while (Instant.now().isBefore(deadline)) {
            var records = consumer.poll(poll(deadline));
            if (!records.isEmpty()) {
                ConsumerRecord<String, String> first = null;
                for (ConsumerRecord<String, String> record : records) {
                    if (first == null || record.timestamp() < first.timestamp()) {
                        first = record;
                    }
                }
                return Optional.of(message(first));
            }
        }
        return Optional.empty();
    }

    private static Properties properties(KafkaConfig config) {
        Properties props = new Properties();
        Map<String, String> custom = config.propertyMap();
        List<String> refused = custom.keySet().stream().filter(FIXED::contains).toList();
        if (!refused.isEmpty()) {
            throw new IllegalArgumentException("these properties are set by the card, to keep the connection read only: " + refused);
        }
        props.putAll(custom);
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, LocalhostAlias.replaceIn(config.getBootstrapServers().trim()));
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        props.put(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, "false");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.putIfAbsent(ConsumerConfig.CLIENT_ID_CONFIG, "client-workbench");
        return props;
    }

    private static Message message(ConsumerRecord<String, String> record) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (Header header : record.headers()) {
            headers.put(header.key(), header.value() == null ? "" : new String(header.value(), StandardCharsets.UTF_8));
        }
        return new Message(record.topic(), record.partition(), record.offset(), record.timestamp(), record.key(), record.value(), headers);
    }

    private static Duration left(Instant deadline) {
        Duration left = Duration.between(Instant.now(), deadline);
        return left.isNegative() ? Duration.ZERO : left;
    }

    private static Duration poll(Instant deadline) {
        Duration left = left(deadline);
        return left.compareTo(POLL) < 0 ? left : POLL;
    }
}
