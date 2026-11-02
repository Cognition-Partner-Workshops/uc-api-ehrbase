import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MockTerminologyServer {

    private static final String SNOMED_VALUE_SET = "http://ehrbase.org/fhir/ValueSet/billing-snomed";
    private static final Pattern STRING_FIELD = Pattern.compile("\"%s\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern VALUE_URI = Pattern.compile("\"valueUri\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern VALUE_CODE = Pattern.compile("\"valueCode\"\\s*:\\s*\"([^\"]*)\"");
    private static final Set<String> ICD10 = Set.of("E11.9", "I10", "J45.909", "Z00.00");
    private static final Set<String> CPT = Set.of("99213", "99214", "93000");
    private static final Set<String> HCPCS = Set.of("G0438", "J1100");
    private static final Set<String> SNOMED = Set.of("44054006", "38341003", "73211009");
    private static final Set<String> SNOMED_VALUE_SET_CODES = Set.of("44054006", "38341003");

    public static void main(String[] args) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", 8090), 0);
        server.createContext("/fhir", MockTerminologyServer::handle);
        server.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(0)));
        System.out.println("Mock FHIR terminology server listening on :8090/fhir");
    }

    private static void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        String requestUri = exchange.getRequestURI().toString();
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        List<String> entries = method.equals("POST") && path.equals("/fhir") ? splitBatchEntries(body) : List.of();
        System.out.printf("%s %s entries=%d%n", method, requestUri, entries.size());

        if (method.equals("GET") && path.equals("/fhir/metadata")) {
            respond(exchange, 200, """
                    {"resourceType":"CapabilityStatement","status":"active","kind":"instance","fhirVersion":"4.0.1","format":["json"]}
                    """);
        } else if (method.equals("POST") && path.equals("/fhir") && isBatch(body)) {
            respond(exchange, 200, batchResponse(entries));
        } else if ((method.equals("GET") || method.equals("POST")) && path.endsWith("/$validate-code")) {
            Validation validation = method.equals("GET")
                    ? validateQuery(exchange.getRequestURI().getRawQuery(), path)
                    : validateParameters(body, path);
            respond(exchange, 200, validationResponse(validation));
        } else {
            respond(exchange, 404, "{\"resourceType\":\"OperationOutcome\",\"issue\":[]}");
        }
    }

    private static boolean isBatch(String body) {
        return body.contains("\"type\":\"batch\"") || body.contains("\"type\": \"batch\"");
    }

    private static String batchResponse(List<String> entries) {
        List<Map<String, Object>> responses = new ArrayList<>();
        for (String entry : entries) {
            Validation result = validateBatchEntry(entry);
            List<Map<String, Object>> parameters = new ArrayList<>();
            parameters.add(Map.of("name", "result", "valueBoolean", result.valid()));
            if (!result.valid()) {
                parameters.add(Map.of("name", "message", "valueString", result.message()));
            }
            responses.add(Map.of(
                    "response", Map.of("status", "200 OK"),
                    "resource", Map.of("resourceType", "Parameters", "parameter", parameters)));
        }
        return toJson(Map.of("resourceType", "Bundle", "type", "batch-response", "entry", responses));
    }

    private static Validation validateBatchEntry(String entry) {
        String operation = field(entry, "url");
        List<String> uris = valueUris(entry);
        String code = valueCode(entry);
        boolean valueSet = operation.startsWith("ValueSet/");
        String valueSetUrl = valueSet && !uris.isEmpty() ? uris.getFirst() : null;
        String system = valueSet
                ? uris.size() > 1 ? uris.get(1) : null
                : uris.isEmpty() ? null : uris.getFirst();
        return validate(system, code, valueSetUrl);
    }

    private static Validation validateParameters(String body, String path) {
        List<String> uris = valueUris(body);
        String code = valueCode(body);
        boolean valueSet = path.contains("/ValueSet/");
        String valueSetUrl = valueSet && !uris.isEmpty() ? uris.getFirst() : null;
        String system = valueSet
                ? uris.size() > 1 ? uris.get(1) : null
                : uris.isEmpty() ? null : uris.getFirst();
        return validate(system, code, valueSetUrl);
    }

    private static Validation validateQuery(String query, String path) {
        Map<String, String> parameters = queryParameters(query);
        boolean valueSet = path.contains("/ValueSet/");
        String system = valueSet ? parameters.get("system") : parameters.get("url");
        String valueSetUrl = valueSet ? parameters.get("url") : null;
        return validate(system, parameters.get("code"), valueSetUrl);
    }

    private static Validation validate(String system, String code, String valueSetUrl) {
        boolean valid;
        if (SNOMED_VALUE_SET.equals(valueSetUrl)) {
            valid = SNOMED_VALUE_SET_CODES.contains(code);
        } else if ("http://hl7.org/fhir/sid/icd-10-cm".equals(system)) {
            valid = ICD10.contains(code);
        } else if ("http://www.ama-assn.org/go/cpt".equals(system)) {
            valid = CPT.contains(code);
        } else if ("https://www.cms.gov/Medicare/Coding/HCPCSReleaseCodeSets".equals(system)) {
            valid = HCPCS.contains(code);
        } else if ("http://snomed.info/sct".equals(system)) {
            valid = SNOMED.contains(code);
        } else {
            valid = false;
        }
        return new Validation(valid, valid ? "" : "Code is not in the configured terminology set");
    }

    private static String validationResponse(Validation validation) {
        List<Map<String, Object>> parameters = new ArrayList<>();
        parameters.add(Map.of("name", "result", "valueBoolean", validation.valid()));
        if (!validation.valid()) {
            parameters.add(Map.of("name", "message", "valueString", validation.message()));
        }
        return toJson(Map.of("resourceType", "Parameters", "parameter", parameters));
    }

    private static List<String> valueUris(String json) {
        Matcher matcher = VALUE_URI.matcher(json);
        List<String> values = new ArrayList<>();
        while (matcher.find()) {
            values.add(matcher.group(1));
        }
        return values;
    }

    private static String valueCode(String json) {
        Matcher matcher = VALUE_CODE.matcher(json);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String field(String json, String name) {
        Matcher matcher = Pattern.compile(STRING_FIELD.pattern().formatted(Pattern.quote(name))).matcher(json);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static List<String> splitBatchEntries(String body) {
        int entryKey = body.indexOf("\"entry\"");
        int arrayStart = entryKey < 0 ? -1 : body.indexOf('[', entryKey);
        if (arrayStart < 0) {
            return List.of();
        }
        List<String> entries = new ArrayList<>();
        int objectDepth = 0;
        int entryStart = -1;
        boolean quoted = false;
        boolean escaped = false;
        for (int i = arrayStart + 1; i < body.length(); i++) {
            char character = body.charAt(i);
            if (quoted) {
                if (escaped) {
                    escaped = false;
                } else if (character == '\\') {
                    escaped = true;
                } else if (character == '"') {
                    quoted = false;
                }
            } else if (character == '"') {
                quoted = true;
            } else if (character == '{') {
                if (objectDepth == 0) {
                    entryStart = i;
                }
                objectDepth++;
            } else if (character == '}') {
                objectDepth--;
                if (objectDepth == 0 && entryStart >= 0) {
                    entries.add(body.substring(entryStart, i + 1));
                    entryStart = -1;
                }
            } else if (character == ']' && objectDepth == 0) {
                break;
            }
        }
        return entries;
    }

    private static Map<String, String> queryParameters(String query) {
        Map<String, String> values = new LinkedHashMap<>();
        if (query == null || query.isEmpty()) {
            return values;
        }
        for (String pair : query.split("&")) {
            String[] parts = pair.split("=", 2);
            values.put(decode(parts[0]), parts.length == 1 ? "" : decode(parts[1]));
        }
        return values;
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static String toJson(Object value) {
        if (value instanceof Map<?, ?> map) {
            List<String> fields = new ArrayList<>();
            map.forEach((key, fieldValue) -> fields.add("\"" + key + "\":" + toJson(fieldValue)));
            return "{" + String.join(",", fields) + "}";
        }
        if (value instanceof List<?> list) {
            return "[" + String.join(",", list.stream().map(MockTerminologyServer::toJson).toList()) + "]";
        }
        if (value instanceof String string) {
            return "\"" + string.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }
        return value.toString();
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] response = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/fhir+json");
        exchange.sendResponseHeaders(status, response.length);
        try (var output = exchange.getResponseBody()) {
            output.write(response);
        }
    }

    private record Validation(boolean valid, String message) {}
}
