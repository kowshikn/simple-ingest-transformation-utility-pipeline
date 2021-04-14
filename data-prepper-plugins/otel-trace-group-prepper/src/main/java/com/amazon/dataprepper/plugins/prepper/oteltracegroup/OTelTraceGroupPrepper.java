package com.amazon.dataprepper.plugins.prepper.oteltracegroup;

import com.amazon.dataprepper.model.PluginType;
import com.amazon.dataprepper.model.annotations.DataPrepperPlugin;
import com.amazon.dataprepper.model.configuration.PluginSetting;
import com.amazon.dataprepper.model.prepper.AbstractPrepper;
import com.amazon.dataprepper.model.record.Record;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Strings;
import io.micrometer.core.instrument.Counter;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.common.document.DocumentField;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

@DataPrepperPlugin(name = "otel_trace_group_prepper", type = PluginType.PREPPER)
public class OTelTraceGroupPrepper extends AbstractPrepper<Record<String>, Record<String>> {

    public static final String RECORDS_IN_MISSING_TRACE_GROUP = "recordsInMissingTraceGroup";
    public static final String RECORDS_OUT_FIXED_TRACE_GROUP = "recordsOutFixedTraceGroup";
    public static final String RECORDS_OUT_MISSING_TRACE_GROUP = "recordsOutMissingTraceGroup";

    private static final Logger LOG = LoggerFactory.getLogger(OTelTraceGroupPrepper.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE_REFERENCE = new TypeReference<Map<String, Object>>() {};

    private final OTelTraceGroupPrepperConfig otelTraceGroupPrepperConfig;
    private final RestHighLevelClient restHighLevelClient;

    private final Counter recordsInMissingTraceGroupCounter;
    private final Counter recordsOutFixedTraceGroupCounter;
    private final Counter recordsOutMissingTraceGroupCounter;

    public OTelTraceGroupPrepper(final PluginSetting pluginSetting) {
        super(pluginSetting);
        otelTraceGroupPrepperConfig = OTelTraceGroupPrepperConfig.buildConfig(pluginSetting);
        restHighLevelClient = otelTraceGroupPrepperConfig.getEsConnectionConfig().createClient();

        recordsInMissingTraceGroupCounter = pluginMetrics.counter(RECORDS_IN_MISSING_TRACE_GROUP);
        recordsOutFixedTraceGroupCounter = pluginMetrics.counter(RECORDS_OUT_FIXED_TRACE_GROUP);
        recordsOutMissingTraceGroupCounter = pluginMetrics.counter(RECORDS_OUT_MISSING_TRACE_GROUP);
    }

    @Override
    public Collection<Record<String>> doExecute(final Collection<Record<String>> rawSpanStringRecords) {
        final List<Record<String>> recordsOut = new LinkedList<>();
        final Map<Record<String>, Map<String, Object>> recordMissingTraceGroupToRawSpanMap = new HashMap<>();
        final Set<String> traceIdsToLookUp = new HashSet<>();
        for (Record<String> record: rawSpanStringRecords) {
            try {
                final Map<String, Object> rawSpanMap = OBJECT_MAPPER.readValue(record.getData(), MAP_TYPE_REFERENCE);
                final String traceGroup = (String) rawSpanMap.get(OTelTraceGroupPrepperConfig.TRACE_GROUP_FIELD);
                final String traceId = (String) rawSpanMap.get(OTelTraceGroupPrepperConfig.TRACE_ID_FIELD);
                if (traceGroup == null || traceGroup.equals("")) {
                    traceIdsToLookUp.add(traceId);
                    recordMissingTraceGroupToRawSpanMap.put(record, rawSpanMap);
                    recordsInMissingTraceGroupCounter.increment();
                } else {
                    recordsOut.add(record);
                }
            } catch (JsonProcessingException e) {
                LOG.error("Failed to parse the record: [{}]", record.getData());
            }
        }

        final Map<String, String> traceIdToTraceGroup = searchTraceGroupByTraceIds(traceIdsToLookUp);
        for (final Map.Entry<Record<String>, Map<String, Object>> entry: recordMissingTraceGroupToRawSpanMap.entrySet()) {
            final Record<String> record = entry.getKey();
            final Map<String, Object> rawSpanMap = entry.getValue();
            final String traceId = (String) rawSpanMap.get(OTelTraceGroupPrepperConfig.TRACE_ID_FIELD);
            final String traceGroup = traceIdToTraceGroup.get(traceId);
            if (!Strings.isNullOrEmpty(traceGroup)) {
                rawSpanMap.put(OTelTraceGroupPrepperConfig.TRACE_GROUP_FIELD, traceGroup);
                try {
                    final String newData = OBJECT_MAPPER.writeValueAsString(rawSpanMap);
                    recordsOut.add(new Record<>(newData, record.getMetadata()));
                    recordsOutFixedTraceGroupCounter.increment();
                } catch (JsonProcessingException e) {
                    recordsOut.add(record);
                    LOG.error("Failed to process the raw span: [{}]", record.getData(), e);
                }
            } else {
                recordsOut.add(record);
                recordsOutMissingTraceGroupCounter.increment();
                final String spanId = (String) rawSpanMap.get(OTelTraceGroupPrepperConfig.SPAN_ID_FIELD);
                LOG.warn("Failed to find traceGroup for spanId: {} due to traceGroup missing for traceId: {}", spanId, traceId);
            }
        }

        return recordsOut;
    }

    private Map<String, String> searchTraceGroupByTraceIds(final Collection<String> traceIds) {
        final Map<String, String> traceIdToTraceGroup = new HashMap<>();
        final SearchRequest searchRequest = createSearchRequest(traceIds);

        try {
            final SearchResponse searchResponse = restHighLevelClient.search(searchRequest, RequestOptions.DEFAULT);
            final SearchHit[] searchHits = searchResponse.getHits().getHits();
            Arrays.asList(searchHits).forEach(searchHit -> {
                final DocumentField traceIdDocField = searchHit.field(OTelTraceGroupPrepperConfig.TRACE_ID_FIELD);
                final DocumentField traceGroupDocField = searchHit.field(OTelTraceGroupPrepperConfig.TRACE_GROUP_FIELD);
                if (Stream.of(traceIdDocField, traceGroupDocField).allMatch(Objects::nonNull)) {
                    final String traceId = searchHit.field(OTelTraceGroupPrepperConfig.TRACE_ID_FIELD).getValue();
                    final String traceGroup = searchHit.field(OTelTraceGroupPrepperConfig.TRACE_GROUP_FIELD).getValue();
                    traceIdToTraceGroup.put(traceId, traceGroup);
                }
            });
        } catch (Exception e) {
            // TODO: retry for status code 429 of ElasticsearchException?
            LOG.error("Search request for traceGroup failed for traceIds: {} due to {}", traceIds, e.getMessage());
        }

        return traceIdToTraceGroup;
    }

    private SearchRequest createSearchRequest(final Collection<String> traceIds) {
        final SearchRequest searchRequest = new SearchRequest(OTelTraceGroupPrepperConfig.RAW_INDEX_ALIAS);
        final SearchSourceBuilder searchSourceBuilder = new SearchSourceBuilder();
        searchSourceBuilder.query(
                QueryBuilders.boolQuery()
                        .must(QueryBuilders.termsQuery(OTelTraceGroupPrepperConfig.TRACE_ID_FIELD, traceIds))
                        .must(QueryBuilders.termQuery(OTelTraceGroupPrepperConfig.PARENT_SPAN_ID_FIELD, ""))
        );
        searchSourceBuilder.docValueField(OTelTraceGroupPrepperConfig.TRACE_ID_FIELD);
        searchSourceBuilder.docValueField(OTelTraceGroupPrepperConfig.TRACE_GROUP_FIELD);
        searchSourceBuilder.fetchSource(false);
        searchRequest.source(searchSourceBuilder);

        return searchRequest;
    }

    @Override
    public void shutdown() {
        try {
            restHighLevelClient.close();
        } catch (IOException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }
}