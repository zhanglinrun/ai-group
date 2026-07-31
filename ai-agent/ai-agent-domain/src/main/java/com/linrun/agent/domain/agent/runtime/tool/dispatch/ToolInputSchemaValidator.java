package com.linrun.agent.domain.agent.runtime.tool.dispatch;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Small fail-closed validator for the JSON Schema subset used by tool inputs.
 * It intentionally validates only deterministic structural keywords instead
 * of pretending to implement the complete JSON Schema specification.
 */
public final class ToolInputSchemaValidator {

    private static final Set<String> SUPPORTED_TYPES = Set.of(
            "object", "array", "string", "integer", "number", "boolean", "null");

    public ValidationResult validate(Object schema, Object input) {
        if (!(schema instanceof Map<?, ?> schemaMap)) {
            return ValidationResult.invalid("Tool schema root must be an object.");
        }
        ValidationResult schemaResult = validateSchemaDefinition(schemaMap, "$schema");
        if (!schemaResult.valid()) {
            return schemaResult;
        }
        return validateValue(schemaMap, input, "$input");
    }

    private ValidationResult validateSchemaDefinition(Map<?, ?> schema, String path) {
        Object declaredType = schema.get("type");
        if (declaredType != null) {
            TypeDeclaration types = readTypes(declaredType);
            if (!types.valid()) {
                return ValidationResult.invalid(path + ".type " + types.error());
            }
        }

        Object propertiesValue = schema.get("properties");
        if (propertiesValue != null) {
            if (!(propertiesValue instanceof Map<?, ?> properties)) {
                return ValidationResult.invalid(path + ".properties must be an object.");
            }
            for (Map.Entry<?, ?> property : properties.entrySet()) {
                if (!(property.getKey() instanceof String propertyName)) {
                    return ValidationResult.invalid(path + ".properties contains a non-string property name.");
                }
                if (!(property.getValue() instanceof Map<?, ?> propertySchema)) {
                    return ValidationResult.invalid(
                            path + ".properties." + propertyName + " must be an object.");
                }
                ValidationResult nested = validateSchemaDefinition(
                        propertySchema, path + ".properties." + propertyName);
                if (!nested.valid()) {
                    return nested;
                }
            }
        }

        Object requiredValue = schema.get("required");
        if (requiredValue != null) {
            List<Object> required = asList(requiredValue);
            if (required == null) {
                return ValidationResult.invalid(path + ".required must be an array of strings.");
            }
            for (Object name : required) {
                if (!(name instanceof String)) {
                    return ValidationResult.invalid(path + ".required must contain only strings.");
                }
            }
        }

        Object additionalProperties = schema.get("additionalProperties");
        if (additionalProperties != null
                && !(additionalProperties instanceof Boolean)
                && !(additionalProperties instanceof Map<?, ?>)) {
            return ValidationResult.invalid(
                    path + ".additionalProperties must be a boolean or an object.");
        }
        if (additionalProperties instanceof Map<?, ?> additionalSchema) {
            ValidationResult nested = validateSchemaDefinition(
                    additionalSchema, path + ".additionalProperties");
            if (!nested.valid()) {
                return nested;
            }
        }

        Object items = schema.get("items");
        if (items != null) {
            if (!(items instanceof Map<?, ?> itemSchema)) {
                return ValidationResult.invalid(path + ".items must be an object.");
            }
            ValidationResult nested = validateSchemaDefinition(itemSchema, path + ".items");
            if (!nested.valid()) {
                return nested;
            }
        }
        return ValidationResult.validResult();
    }

    private ValidationResult validateValue(Map<?, ?> schema, Object value, String path) {
        Object declaredType = schema.get("type");
        if (declaredType != null) {
            TypeDeclaration types = readTypes(declaredType);
            if (!matchesAnyType(types.types(), value)) {
                return ValidationResult.invalid(
                        path + " must be " + formatTypes(types.types())
                                + ", but was " + describeType(value) + ".");
            }
        }

        if (value instanceof Map<?, ?> objectValue) {
            ValidationResult objectResult = validateObject(schema, objectValue, path);
            if (!objectResult.valid()) {
                return objectResult;
            }
        }

        if (isArray(value) && schema.get("items") instanceof Map<?, ?> itemSchema) {
            List<Object> values = asList(value);
            for (int index = 0; index < values.size(); index++) {
                ValidationResult itemResult = validateValue(
                        itemSchema, values.get(index), path + "[" + index + "]");
                if (!itemResult.valid()) {
                    return itemResult;
                }
            }
        }
        return ValidationResult.validResult();
    }

    private ValidationResult validateObject(Map<?, ?> schema,
                                            Map<?, ?> objectValue,
                                            String path) {
        Map<?, ?> properties = schema.get("properties") instanceof Map<?, ?> value
                ? value
                : Map.of();

        Object requiredValue = schema.get("required");
        if (requiredValue != null) {
            for (Object requiredName : asList(requiredValue)) {
                if (!objectValue.containsKey(requiredName)) {
                    return ValidationResult.invalid(
                            path + " is missing required property '" + requiredName + "'.");
                }
            }
        }

        Object additionalProperties = schema.get("additionalProperties");
        for (Map.Entry<?, ?> property : objectValue.entrySet()) {
            Object propertyName = property.getKey();
            Object propertySchema = properties.get(propertyName);
            if (propertySchema instanceof Map<?, ?> nestedSchema) {
                ValidationResult nested = validateValue(
                        nestedSchema, property.getValue(), childPath(path, String.valueOf(propertyName)));
                if (!nested.valid()) {
                    return nested;
                }
                continue;
            }
            if (Boolean.FALSE.equals(additionalProperties)) {
                return ValidationResult.invalid(
                        path + " contains unexpected property '" + propertyName + "'.");
            }
            if (additionalProperties instanceof Map<?, ?> additionalSchema) {
                ValidationResult nested = validateValue(
                        additionalSchema, property.getValue(), childPath(path, String.valueOf(propertyName)));
                if (!nested.valid()) {
                    return nested;
                }
            }
        }
        return ValidationResult.validResult();
    }

    private TypeDeclaration readTypes(Object declaredType) {
        List<Object> rawTypes;
        if (declaredType instanceof String) {
            rawTypes = List.of(declaredType);
        } else {
            rawTypes = asList(declaredType);
            if (rawTypes == null || rawTypes.isEmpty()) {
                return TypeDeclaration.invalid("must be a string or a non-empty array of strings.");
            }
        }

        List<String> types = new ArrayList<>(rawTypes.size());
        for (Object rawType : rawTypes) {
            if (!(rawType instanceof String type) || !SUPPORTED_TYPES.contains(type)) {
                return TypeDeclaration.invalid("contains an unsupported type: " + rawType + ".");
            }
            types.add(type);
        }
        return TypeDeclaration.valid(types);
    }

    private boolean matchesAnyType(List<String> types, Object value) {
        for (String type : types) {
            if (matchesType(type, value)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesType(String type, Object value) {
        return switch (type) {
            case "object" -> value instanceof Map<?, ?>;
            case "array" -> isArray(value);
            case "string" -> value instanceof String;
            case "integer" -> isInteger(value);
            case "number" -> value instanceof Number;
            case "boolean" -> value instanceof Boolean;
            case "null" -> value == null;
            default -> false;
        };
    }

    private boolean isInteger(Object value) {
        if (value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long || value instanceof BigInteger) {
            return true;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.stripTrailingZeros().scale() <= 0;
        }
        if (value instanceof Float number) {
            return Float.isFinite(number) && Math.rint(number.doubleValue()) == number.doubleValue();
        }
        if (value instanceof Double number) {
            return Double.isFinite(number) && Math.rint(number) == number;
        }
        return false;
    }

    private boolean isArray(Object value) {
        return value instanceof Collection<?>
                || (value != null && value.getClass().isArray());
    }

    private List<Object> asList(Object value) {
        if (value instanceof Collection<?> collection) {
            return new ArrayList<>(collection);
        }
        if (value != null && value.getClass().isArray()) {
            int length = Array.getLength(value);
            List<Object> result = new ArrayList<>(length);
            for (int index = 0; index < length; index++) {
                result.add(Array.get(value, index));
            }
            return result;
        }
        return null;
    }

    private String formatTypes(List<String> types) {
        if (types.size() == 1) {
            return "type '" + types.get(0) + "'";
        }
        return "one of types " + types;
    }

    private String describeType(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Map<?, ?>) {
            return "object";
        }
        if (isArray(value)) {
            return "array";
        }
        if (value instanceof String) {
            return "string";
        }
        if (value instanceof Boolean) {
            return "boolean";
        }
        if (isInteger(value)) {
            return "integer";
        }
        if (value instanceof Number) {
            return "number";
        }
        return value.getClass().getSimpleName();
    }

    private String childPath(String parent, String property) {
        return parent + "." + property;
    }

    public record ValidationResult(boolean valid, String message) {
        static ValidationResult validResult() {
            return new ValidationResult(true, null);
        }

        static ValidationResult invalid(String message) {
            return new ValidationResult(false, message);
        }
    }

    private record TypeDeclaration(boolean valid, List<String> types, String error) {
        static TypeDeclaration valid(List<String> types) {
            return new TypeDeclaration(true, List.copyOf(types), null);
        }

        static TypeDeclaration invalid(String error) {
            return new TypeDeclaration(false, List.of(), error);
        }
    }
}
