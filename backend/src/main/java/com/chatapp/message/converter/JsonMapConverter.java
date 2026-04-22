package com.chatapp.message.converter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * JPA AttributeConverter: Map<String, Object> ↔ JSON String.
 *
 * Rationale: @JdbcTypeCode(SqlTypes.JSON) works on PostgreSQL but fails in H2 test mode —
 * H2 stores JSON as VARCHAR and Hibernate 6 reads it back as a raw String rather than parsed
 * JSON, causing Jackson deserialization errors (MismatchedInputException from String → Map).
 *
 * This converter uses Jackson directly and is portable across H2 (VARCHAR) and PostgreSQL (JSONB).
 * The column definition "jsonb" is kept so PostgreSQL uses the JSONB type (supports GIN index,
 * ->> queries in V2).
 *
 * W7-D4 — for systemMetadata on Message entity.
 */
@Converter
public class JsonMapConverter implements AttributeConverter<Map<String, Object>, String> {

    private static final Logger log = LoggerFactory.getLogger(JsonMapConverter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    @Override
    public String convertToDatabaseColumn(Map<String, Object> attribute) {
        if (attribute == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(attribute);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize systemMetadata to JSON string", e);
            throw new IllegalArgumentException("Cannot serialize systemMetadata", e);
        }
    }

    @Override
    public Map<String, Object> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(dbData, MAP_TYPE);
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize systemMetadata from JSON string: {}", dbData, e);
            throw new IllegalArgumentException("Cannot deserialize systemMetadata", e);
        }
    }
}
