/*
 * Copyright (c) 2024 vitasystems GmbH.
 *
 * This file is part of project EHRbase
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.ehrbase.service.validation;

import static org.assertj.core.api.Assertions.*;

import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.*;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import org.ehrbase.openehr.sdk.validation.terminology.ExternalTerminologyValidationException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BillingTerminologyValidationIT {
    private HttpServer server;
    private final List<Map<String, Object>> requests = new CopyOnWriteArrayList<>();
    private int status = 200;
    private String body;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("POST");
            assertThat(exchange.getRequestHeaders().getFirst("Content-Type")).isEqualTo("application/fhir+json");
            requests.add(JsonPath.parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8))
                    .json());
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/fhir+json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (exchange) {
                exchange.getResponseBody().write(bytes);
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void oneBatchPerCompositionWithAllSystemsAndDeduplication() {
        body = response(4, true);
        FhirTerminologyValidation provider = provider(true);
        List<BillingValidationProfile.CodeSystem> systems = new ArrayList<>();
        for (String name : List.of("icd10", "cpt", "hcpcs", "snomed")) {
            systems.add(new BillingValidationProfile.CodeSystem(
                    name,
                    "http://" + name,
                    List.of(name, name + "-alias"),
                    name.equals("snomed") ? "http://billing-subset" : null));
        }
        BillingCodeValidator validator =
                new BillingCodeValidator(List.of(new BillingValidationProfile("claims", Set.of(), systems, provider)));
        assertThat(validator.validate(
                        "any",
                        BillingCodeValidatorTest.composition(
                                "icd10", "icd10-alias", "cpt", "hcpcs", "snomed", "local")))
                .isEmpty();
        assertThat(requests).hasSize(1);
        var request = JsonPath.parse(requests.get(0));
        assertThat(request.read("$.type", String.class)).isEqualTo("batch");
        assertThat(request.read("$.entry", List.class)).hasSize(4);
        assertThat(request.read("$.entry[3].request.url", String.class)).isEqualTo("ValueSet/$validate-code");
        assertThat(request.read("$.entry[3].resource.parameter[0].valueUri", String.class))
                .isEqualTo("http://billing-subset");
        assertThat(request.read("$.entry[3].resource.parameter[2].valueUri", String.class))
                .isEqualTo("http://snomed");
    }

    @Test
    void invalidCodesReturnFixedDiagnosticAndByNameResult() {
        body = response(1, false);
        var violations = BillingCodeValidatorTest.validator(provider(true), Set.of(), "cpt")
                .validate("any", BillingCodeValidatorTest.composition("alias", "alternate"));
        assertThat(violations)
                .singleElement()
                .satisfies(v -> assertThat(v.toString())
                        .contains(
                                "profile=claims",
                                "codeSystem=cpt",
                                "system=http://system",
                                "code=A01",
                                "path=/content[0]/data/items[0]/value",
                                "not found"));
        assertThat(requests).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"http", "malformed", "count", "result", "entry"})
    void failuresRespectFailOnError(String failure) {
        body = switch (failure) {
            case "http" -> {
                status = 503;
                yield "{}";
            }
            case "malformed" -> "{";
            case "count" -> response(0, true);
            case "result" -> response(1, true).replace("result", "other");
            default -> response(1, true).replace("200 OK", "500 Error");
        };
        assertThat(BillingCodeValidatorTest.validator(provider(false), Set.of(), "cpt")
                        .validate("any", BillingCodeValidatorTest.composition("alias")))
                .isEmpty();
        assertThatThrownBy(() -> BillingCodeValidatorTest.validator(provider(true), Set.of(), "cpt")
                        .validate("any", BillingCodeValidatorTest.composition("alias")))
                .isInstanceOf(ExternalTerminologyValidationException.class);
    }

    @Test
    void ioFailureRespectsFailOnError() {
        server.stop(0);
        assertThat(BillingCodeValidatorTest.validator(provider(false), Set.of(), "cpt")
                        .validate("any", BillingCodeValidatorTest.composition("alias")))
                .isEmpty();
        assertThatThrownBy(() -> BillingCodeValidatorTest.validator(provider(true), Set.of(), "cpt")
                        .validate("any", BillingCodeValidatorTest.composition("alias")))
                .isInstanceOf(ExternalTerminologyValidationException.class);
    }

    private FhirTerminologyValidation provider(boolean fail) {
        return new FhirTerminologyValidation(
                "http://localhost:" + server.getAddress().getPort(), fail);
    }

    private static String response(int count, boolean valid) {
        String entry = """
            {"response":{"status":"200 OK"},"resource":{"resourceType":"Parameters",
            "parameter":[{"name":"message","valueString":"not found"},{"name":"display","valueString":"ignored"},
            {"name":"result","valueBoolean":%s}]}}
            """.formatted(valid);
        return "{\"resourceType\":\"Bundle\",\"type\":\"batch-response\",\"entry\":["
                + String.join(",", Collections.nCopies(count, entry)) + "]}";
    }
}
