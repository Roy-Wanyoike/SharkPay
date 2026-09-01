package com.sharkpay.fx.events;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Minimal structural JSON-Schema (2020-12 subset) checker used by the event
 * tests: loads a merged contract schema from {@code contracts/events/} and
 * validates a serialized CloudEvent against it. Supports the keywords the
 * fx event schema uses: {@code type, required, properties,
 * additionalProperties(false), enum, const, pattern, minimum, minItems,
 * items, oneOf, $ref (#/$defs/...)} and {@code format} (uuid, date-time —
 * regex-level). No external validator dependency (offline build). Ported
 * from the wallet service's event test toolkit.
 */
final class JsonSchemaCheck {

    private static final Pattern UUID_PATTERN =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final Pattern DATE_TIME_PATTERN =
            Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?([Zz]|[+-]\\d{2}:\\d{2})$");

    private final JsonNode schema;
    private final JsonNode root;

    private JsonSchemaCheck(JsonNode schema) {
        this.schema = schema;
        this.root = schema;
    }

    static JsonSchemaCheck load(String contractFileName) {
        JsonMapper mapper = JsonMapper.builder().build();
        for (Path candidate : List.of(
                Path.of("..", "..", "contracts", "events", contractFileName),   // CWD services/fx
                Path.of("contracts", "events", contractFileName),               // CWD repo root
                Path.of("..", "contracts", "events", contractFileName))) {
            File file = candidate.toFile();
            if (file.isFile()) {
                JsonNode node = mapper.readValue(file, JsonNode.class);
                return new JsonSchemaCheck(node);
            }
        }
        throw new IllegalStateException("contract schema not found: " + contractFileName
                + " (working dir " + Path.of("").toAbsolutePath() + ")");
    }

    /**
     * Validates the instance; returns the (possibly empty) list of violations.
     */
    List<String> errors(JsonNode instance) {
        List<String> errors = new ArrayList<>();
        validate(instance, schema, "$", errors);
        return errors;
    }

    private void validate(JsonNode instance, JsonNode schema, String path, List<String> errors) {
        if (schema.has("$ref")) {
            validate(instance, resolveRef(schema.get("$ref").asString()), path, errors);
            return;
        }
        if (schema.has("oneOf")) {
            validateOneOf(instance, schema.get("oneOf"), path, errors);
            return;
        }
        String type = schema.path("type").asString(null);
        if (type != null && !typeMatches(instance, type)) {
            errors.add(path + ": expected type " + type + " but got " + jsonType(instance));
            return;
        }
        if (schema.has("const")) {
            String expected = schema.get("const").asString();
            if (!expected.equals(instance.asString())) {
                errors.add(path + ": expected const " + expected + " but got " + instance.asString());
            }
        }
        if (schema.has("enum")) {
            List<String> allowed = new ArrayList<>();
            schema.get("enum").forEach(value -> allowed.add(value.asString()));
            if (!allowed.contains(instance.asString())) {
                errors.add(path + ": value " + instance.asString() + " not in enum " + allowed);
            }
        }
        if (schema.has("pattern") && instance.isTextual()) {
            if (!Pattern.compile(schema.get("pattern").asString()).matcher(instance.asString())
                    .matches()) {
                errors.add(path + ": value " + instance.asString() + " does not match pattern "
                        + schema.get("pattern").asString());
            }
        }
        if (schema.has("format") && instance.isTextual()) {
            String format = schema.get("format").asString();
            Pattern pattern = "uuid".equals(format) ? UUID_PATTERN : DATE_TIME_PATTERN;
            if (!pattern.matcher(instance.asString()).matches()) {
                errors.add(path + ": value " + instance.asString() + " is not a " + format);
            }
        }
        if (schema.has("minimum") && instance.isNumber()) {
            if (instance.decimalValue().compareTo(schema.get("minimum").decimalValue()) < 0) {
                errors.add(path + ": value " + instance.asString() + " below minimum "
                        + schema.get("minimum").asString());
            }
        }
        if (instance.isObject()) {
            validateObject(instance, schema, path, errors);
        } else if (instance.isArray()) {
            validateArray(instance, schema, path, errors);
        }
    }

    private void validateObject(JsonNode instance, JsonNode schema, String path,
                                List<String> errors) {
        JsonNode properties = schema.path("properties");
        if (schema.has("required")) {
            schema.get("required").forEach(required -> {
                String name = required.asString();
                if (!instance.has(name)) {
                    errors.add(path + ": missing required property '" + name + "'");
                }
            });
        }
        boolean closed = schema.path("additionalProperties").isBoolean()
                && !schema.get("additionalProperties").asBoolean();
        for (Map.Entry<String, JsonNode> field : instance.properties()) {
            String childPath = path + "." + field.getKey();
            if (properties.has(field.getKey())) {
                validate(field.getValue(), properties.get(field.getKey()), childPath, errors);
            } else if (closed) {
                errors.add(childPath + ": additional property not allowed by schema");
            }
        }
    }

    private void validateArray(JsonNode instance, JsonNode schema, String path,
                               List<String> errors) {
        if (schema.has("minItems") && instance.size() < schema.get("minItems").asInt()) {
            errors.add(path + ": expected at least " + schema.get("minItems").asInt()
                    + " items, got " + instance.size());
        }
        if (schema.has("items")) {
            for (int i = 0; i < instance.size(); i++) {
                validate(instance.get(i), schema.get("items"), path + "[" + i + "]", errors);
            }
        }
    }

    private void validateOneOf(JsonNode instance, JsonNode oneOf, String path, List<String> errors) {
        int matches = 0;
        List<String> branchErrors = new ArrayList<>();
        for (JsonNode branch : oneOf) {
            List<String> branchResult = new ArrayList<>();
            validate(instance, branch, path, branchResult);
            if (branchResult.isEmpty()) {
                matches++;
            } else if (matches == 0) {
                branchErrors.addAll(branchResult);
            }
        }
        if (matches == 0) {
            errors.add(path + ": matches none of the oneOf branches: " + branchErrors);
        } else if (matches > 1) {
            errors.add(path + ": matches " + matches + " oneOf branches (exactly one required)");
        }
    }

    private JsonNode resolveRef(String reference) {
        if (!reference.startsWith("#/$defs/")) {
            throw new IllegalArgumentException("only local #/$defs/ refs are supported: " + reference);
        }
        JsonNode resolved = root.path("$defs").path(reference.substring("#/$defs/".length()));
        if (resolved.isMissingNode()) {
            throw new IllegalArgumentException("unresolvable ref: " + reference);
        }
        return resolved;
    }

    private static boolean typeMatches(JsonNode instance, String type) {
        return switch (type) {
            case "object" -> instance.isObject();
            case "array" -> instance.isArray();
            case "string" -> instance.isTextual();
            case "integer" -> instance.isIntegralNumber();
            case "number" -> instance.isNumber();
            case "boolean" -> instance.isBoolean();
            case "null" -> instance.isNull();
            default -> throw new IllegalArgumentException("unsupported schema type: " + type);
        };
    }

    private static String jsonType(JsonNode instance) {
        if (instance.isObject()) {
            return "object";
        }
        if (instance.isArray()) {
            return "array";
        }
        if (instance.isTextual()) {
            return "string";
        }
        if (instance.isIntegralNumber()) {
            return "integer";
        }
        if (instance.isNumber()) {
            return "number";
        }
        if (instance.isBoolean()) {
            return "boolean";
        }
        return instance.isNull() ? "null" : "unknown";
    }
}
