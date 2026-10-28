package com.omnidude.monitoring;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.TreeMap;

/** Parâmetros específicos de cada tipo de checagem, guardados como JSON em {@code service_checks.params}. */
public final class ParamsCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ParamsCodec() {
    }

    public static Map<String, String> parse(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<TreeMap<String, String>>() { });
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }

    public static String write(Map<String, String> params) {
        if (params == null || params.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(new TreeMap<>(params));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
