package com.example.streamtableupdate;

import java.util.Collections;
import java.util.Map;

import com.example.BalanceOnHandSummaryEvent;
import com.example.BalanceOnHandUpdateEvent;
import io.confluent.kafka.serializers.AbstractKafkaAvroSerDeConfig;
import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.utils.Bytes;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.Materialized;
import org.apache.kafka.streams.kstream.Serialized;
import org.apache.kafka.streams.state.KeyValueStore;
import org.apache.kafka.streams.state.QueryableStoreTypes;
import org.apache.kafka.streams.state.ReadOnlyKeyValueStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.stream.annotation.EnableBinding;
import org.springframework.cloud.stream.annotation.Input;
import org.springframework.cloud.stream.annotation.Output;
import org.springframework.cloud.stream.annotation.StreamListener;
import org.springframework.cloud.stream.binder.kafka.streams.InteractiveQueryService;
import org.springframework.cloud.stream.binder.kafka.streams.annotations.KafkaStreamsProcessor;
import org.springframework.integration.support.MessageBuilder;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

@SpringBootApplication
@EnableBinding({KafkaStreamsProcessor.class, StreamTableUpdateApplication.ProducerSource.class})
public class StreamTableUpdateApplication {

	public static final String REASON_CODE_DECREMENT = "D";
	public static final String REASON_CODE_INCREMENT = "I";

	@Autowired
	private InteractiveQueryService interactiveQueryService;

	public static void main(String[] args) {
		SpringApplication.run(StreamTableUpdateApplication.class, args);
	}

	@StreamListener
	@SendTo("output")
	public KStream<Integer, BalanceOnHandSummaryEvent> process(@Input("input") KStream<Integer, BalanceOnHandUpdateEvent> bohUpdateEventStream) {

		SpecificAvroSerde<BalanceOnHandUpdateEvent> balanceOnHandUpdateEventSerde = new SpecificAvroSerde<>();
		SpecificAvroSerde<BalanceOnHandSummaryEvent> balanceOnHandSummaryEventSerde = new SpecificAvroSerde<>();

		final Map<String, String> serdeConfig = Collections.singletonMap(
				AbstractKafkaAvroSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, "http://localhost:8081");
		balanceOnHandSummaryEventSerde.configure(serdeConfig, false);
		balanceOnHandUpdateEventSerde.configure(serdeConfig, false);

		return bohUpdateEventStream.groupByKey(Serialized.with(Serdes.Integer(), balanceOnHandUpdateEventSerde))
				.aggregate(() -> BalanceOnHandSummaryEvent.newBuilder().build(), (key, value, aggregate) -> {
					double delta = value.getDelta();
					final String reasonCode = value.getReasonCode().toString();
					if (reasonCode.equals(REASON_CODE_DECREMENT)) {
						aggregate.setDelta(aggregate.getDelta() - delta);
					} else if (reasonCode.equals(REASON_CODE_INCREMENT)) {
						aggregate.setDelta(aggregate.getDelta() + delta);
					} else {
						return null;
					}
					return aggregate;
				}, Materialized.<Integer, BalanceOnHandSummaryEvent, KeyValueStore<Bytes, byte[]>>as("foo-bar-store-name")
						.withKeySerde(Serdes.Integer())
						.withValueSerde(balanceOnHandSummaryEventSerde))
				.toStream();
	}

	@RestController
	public class ProducerApplication {

		@Autowired
		private ProducerSource source;

		@RequestMapping(method = RequestMethod.POST, path = "/updateEvent/{id}/{reasonCode}/{delta}", produces = "text/plain")
		public String sendMessage(@PathVariable("id") Integer id,
								  @PathVariable("reasonCode") String reasonCode,
								  @PathVariable("delta") Double delta) {

			source.output().send(MessageBuilder.withPayload(balanceOnHandUpdateEvent(reasonCode, delta))
					.setHeader("update-event-id", id)
					.build());
			return "Update sent downstream.";
		}

		@RequestMapping("/delta/{id}")
		public Double delta(@PathVariable("id") Integer id) {
			final ReadOnlyKeyValueStore<Integer, BalanceOnHandSummaryEvent> summaryStore =
					interactiveQueryService.getQueryableStore("foo-bar-store-name", QueryableStoreTypes.keyValueStore());

			final BalanceOnHandSummaryEvent summary = summaryStore.get(id);
			if (summary == null) {
				throw new IllegalArgumentException("No data found!");
			}
			return summary.getDelta();
		}

		private BalanceOnHandUpdateEvent balanceOnHandUpdateEvent(String reasonCode, Double delta) {
			BalanceOnHandUpdateEvent balanceOnHandUpdateEvent = new BalanceOnHandUpdateEvent();
			balanceOnHandUpdateEvent.setReasonCode(reasonCode);
			balanceOnHandUpdateEvent.setDelta(delta);
			return balanceOnHandUpdateEvent;
		}

	}
	interface ProducerSource {
		String OUTPUT = "producer-output";

		@Output(OUTPUT)
		MessageChannel output();
	}
}
