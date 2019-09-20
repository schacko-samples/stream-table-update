package com.example.streamtableupdate;

import java.util.Collections;
import java.util.Map;

import com.example.BalanceOnHandUpdateEvent;
import io.confluent.kafka.serializers.AbstractKafkaAvroSerDeConfig;
import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerializer;

public class CustomAvroSerializer extends SpecificAvroSerializer<BalanceOnHandUpdateEvent> {

	@Override
	public void configure(Map<String, ?> serializerConfig, boolean isSerializerForRecordKeys) {
		final Map<String, String> serdeConfig = Collections.singletonMap(
				AbstractKafkaAvroSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, "http://localhost:8081");
		super.configure(serdeConfig, isSerializerForRecordKeys);
	}
}