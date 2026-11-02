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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ca.uhn.fhir.context.FhirContext;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.internal.JsonContext;
import com.nedap.archie.rm.datavalues.DvCodedText;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.IllegalFormatException;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import net.java.quickcheck.generator.PrimitiveGenerators;
import org.ehrbase.openehr.sdk.validation.terminology.ExternalTerminologyValidationException;
import org.ehrbase.openehr.sdk.validation.terminology.TerminologyParam;
import org.ehrbase.service.validation.FhirTerminologyValidation.ValueSetConverter;
import org.hl7.fhir.r4.model.ValueSet;
import org.hl7.fhir.r4.model.ValueSet.ValueSetExpansionComponent;
import org.hl7.fhir.r4.model.ValueSet.ValueSetExpansionContainsComponent;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.function.client.WebClientRequestException;

class FhirTerminologyValidationTest {

    @Test
    public void guaranteePrefix() {
        Assertions.assertEquals("url=ABC", FhirTerminologyValidation.guaranteePrefix("url=", "url=ABC"));
        Assertions.assertEquals("url=ABC", FhirTerminologyValidation.guaranteePrefix("url=", "ABC"));
        Assertions.assertNull(FhirTerminologyValidation.guaranteePrefix("url=", ""));
        Assertions.assertEquals(
                "xyz=XYZ&url=ABC", FhirTerminologyValidation.guaranteePrefix("url=", "xyz=XYZ&url=ABC"));
    }

    @Test
    public void renderTempl() {
        String ref = String.format(FhirTerminologyValidation.SUPPORTS_CODE_SYS_TEMPL, "abc", "123");
        String render1 =
                FhirTerminologyValidation.renderTempl(FhirTerminologyValidation.SUPPORTS_CODE_SYS_TEMPL, "abc", "123");
        Assertions.assertEquals(ref, render1);

        String render2 = FhirTerminologyValidation.renderTempl(
                FhirTerminologyValidation.SUPPORTS_CODE_SYS_TEMPL, "abc", "123", "xyz");
        Assertions.assertEquals(ref, render2);
        Assertions.assertThrows(
                IllegalFormatException.class,
                () -> FhirTerminologyValidation.renderTempl(FhirTerminologyValidation.SUPPORTS_CODE_SYS_TEMPL, "abc"));
    }

    @Test
    void valueSetConverter() throws Exception {
        ValueSet values = anyValueSet();

        String json = FhirContext.forR4().newJsonParser().encodeResourceToString(values);
        DocumentContext ctx = JsonPath.parse(json);

        List<DvCodedText> dv = ValueSetConverter.convert(ctx);

        Assertions.assertEquals(values.getExpansion().getContains().size(), dv.size());
    }

    @Test
    void stringjoin() {
        List<String> params = new ArrayList<>();
        String reqParam = params.stream().collect(Collectors.joining("&"));
        Assertions.assertEquals("", reqParam);

        params = List.of("a");
        reqParam = params.stream().collect(Collectors.joining("&"));
        Assertions.assertEquals("a", reqParam);

        params = List.of("a", "b");
        reqParam = params.stream().collect(Collectors.joining("&"));
        Assertions.assertEquals("a&b", reqParam);
    }

    @Test
    void supports_ValidParams_ReturnsTrue() {
        String baseUrl = "http://terminology.local";
        String valueSetUrl = "http://snomed.info/sct?fhir_vs=ecl/%3C306206005";

        FhirTerminologyValidation validation = spy(new FhirTerminologyValidation(baseUrl));
        TerminologyParam param =
                TerminologyParam.ofFhir("//fhir.hl7.org/ValueSet/$expand?url=" + valueSetUrl + "&activeOnly=true");

        JsonContext jsonContext = mock(JsonContext.class);
        when(jsonContext.read("$.total", int.class)).thenReturn(1);

        doReturn(jsonContext).when(validation).internalGet(Mockito.anyString());

        assertTrue(validation.supports(param));

        verify(validation)
                .internalGet(FhirTerminologyValidation.renderTempl(
                        FhirTerminologyValidation.SUPPORTS_VALUE_SET_TEMPL, baseUrl, valueSetUrl));
    }

    @Test
    void supports_InvalidParams_ReturnsFalse() {
        FhirTerminologyValidation validation = spy(new FhirTerminologyValidation("http://terminology.local"));

        assertFalse(validation.supports(TerminologyParam.ofFhir("//fhir.hl7.org/ValueSet/$expand?activeOnly=true")));
        assertFalse(validation.supports(TerminologyParam.ofFhir(
                "//invalid/ValueSet/$expand?url=http://snomed.info/sct?fhir_vs=ecl/%3C306206005&activeOnly=true")));

        verify(validation, never()).internalGet(Mockito.anyString());
    }

    @Test
    void validateCodesEmptyInputDoesNotPost() {
        FhirTerminologyValidation validation = spy(new FhirTerminologyValidation("http://terminology.local"));

        assertTrue(validation.validateCodes(List.of()).isEmpty());

        verify(validation, never()).internalPost(Mockito.anyString(), Mockito.anyString());
    }

    @Test
    void validateCodesBuildsBatchAndParsesParametersByName() {
        FhirTerminologyValidation validation = spy(new FhirTerminologyValidation("http://terminology.local"));
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        List<CodeValidationRequest> requests = List.of(
                new CodeValidationRequest("http://hl7.org/fhir/sid/icd-10-cm", "E11.9", null),
                new CodeValidationRequest(
                        "http://snomed.info/sct", "44054006", "http://ehrbase.org/fhir/ValueSet/billing-snomed"));
        doReturn(JsonPath.parse("""
                {
                  "resourceType":"Bundle",
                  "type":"batch-response",
                  "entry":[
                    {"response":{"status":"200 OK"},"resource":{"resourceType":"Parameters","parameter":[
                      {"name":"message","valueString":"not valid"},
                      {"name":"result","valueBoolean":false}
                    ]}},
                    {"response":{"status":"200 OK"},"resource":{"resourceType":"Parameters","parameter":[
                      {"name":"result","valueBoolean":true}
                    ]}}
                  ]
                }
                """))
                .when(validation)
                .internalPost(Mockito.eq("http://terminology.local"), body.capture());

        List<CodeValidationResult> results = validation.validateCodes(requests);

        assertEquals(2, results.size());
        assertEquals(CodeValidationResult.Status.INVALID, results.get(0).status());
        assertEquals("not valid", results.get(0).message());
        assertEquals(CodeValidationResult.Status.VALID, results.get(1).status());
        DocumentContext requestBundle = JsonPath.parse(body.getValue());
        assertEquals("Bundle", requestBundle.read("$.resourceType", String.class));
        assertEquals("batch", requestBundle.read("$.type", String.class));
        assertEquals(2, requestBundle.read("$.entry.length()", Integer.class));
        assertEquals("POST", requestBundle.read("$.entry[0].request.method", String.class));
        assertEquals("CodeSystem/$validate-code", requestBundle.read("$.entry[0].request.url", String.class));
        assertEquals(
                "http://hl7.org/fhir/sid/icd-10-cm",
                requestBundle.read("$.entry[0].resource.parameter[0].valueUri", String.class));
        assertEquals("E11.9", requestBundle.read("$.entry[0].resource.parameter[1].valueCode", String.class));
        assertEquals("ValueSet/$validate-code", requestBundle.read("$.entry[1].request.url", String.class));
        assertEquals(
                "http://ehrbase.org/fhir/ValueSet/billing-snomed",
                requestBundle.read("$.entry[1].resource.parameter[0].valueUri", String.class));
        assertEquals(
                "http://snomed.info/sct",
                requestBundle.read("$.entry[1].resource.parameter[1].valueUri", String.class));
        assertEquals("44054006", requestBundle.read("$.entry[1].resource.parameter[2].valueCode", String.class));
    }

    @Test
    void validateCodesInvalidResultUsesMessageParameter() {
        FhirTerminologyValidation validation = validationWithResponse("""
                {
                  "resourceType":"Bundle","type":"batch-response",
                  "entry":[{"response":{"status":"200"},"resource":{"resourceType":"Parameters","parameter":[
                    {"name":"message","valueString":"code is retired"},
                    {"name":"result","valueBoolean":false}
                  ]}}]
                }
                """);

        CodeValidationResult result = validation
                .validateCodes(List.of(new CodeValidationRequest("system", "bad-code", null)))
                .getFirst();

        assertEquals(CodeValidationResult.Status.INVALID, result.status());
        assertEquals("code is retired", result.message());
    }

    @ParameterizedTest
    @MethodSource("validationFailures")
    void validateCodesHonorsFailOnError(
            String scenario, RuntimeException exception, String responseBody, boolean failOnError) {
        FhirTerminologyValidation validation =
                spy(new FhirTerminologyValidation("http://terminology.local", failOnError));
        if (exception != null) {
            doThrow(exception).when(validation).internalPost(Mockito.anyString(), Mockito.anyString());
        } else {
            doReturn(JsonPath.parse(responseBody))
                    .when(validation)
                    .internalPost(Mockito.anyString(), Mockito.anyString());
        }

        if (failOnError) {
            assertThrows(
                    ExternalTerminologyValidationException.class,
                    () -> validation.validateCodes(List.of(new CodeValidationRequest("system", "code", null))),
                    scenario);
        } else {
            CodeValidationResult result = validation
                    .validateCodes(List.of(new CodeValidationRequest("system", "code", null)))
                    .getFirst();
            assertEquals(CodeValidationResult.Status.UNAVAILABLE, result.status(), scenario);
        }
    }

    private static Stream<Arguments> validationFailures() {
        RuntimeException httpFailure = new ExternalTerminologyValidationException("HTTP status 500");
        RuntimeException connectionFailure = new WebClientRequestException(
                new IOException("connection refused"),
                HttpMethod.POST,
                URI.create("http://terminology.local"),
                HttpHeaders.EMPTY);
        String badStatus = """
                {"resourceType":"Bundle","type":"batch-response","entry":[
                  {"response":{"status":"500"},"resource":{"resourceType":"Parameters","parameter":[]}}
                ]}
                """;
        String missingResult = """
                {"resourceType":"Bundle","type":"batch-response","entry":[
                  {"response":{"status":"200"},"resource":{"resourceType":"Parameters","parameter":[]}}
                ]}
                """;
        return Stream.of(
                Arguments.of("HTTP 500 fail-closed", httpFailure, null, true),
                Arguments.of("HTTP 500 fail-open", httpFailure, null, false),
                Arguments.of("connection fail-closed", connectionFailure, null, true),
                Arguments.of("connection fail-open", connectionFailure, null, false),
                Arguments.of("entry status fail-closed", null, badStatus, true),
                Arguments.of("entry status fail-open", null, badStatus, false),
                Arguments.of("missing result fail-closed", null, missingResult, true),
                Arguments.of("missing result fail-open", null, missingResult, false));
    }

    private static FhirTerminologyValidation validationWithResponse(String responseBody) {
        FhirTerminologyValidation validation = spy(new FhirTerminologyValidation("http://terminology.local"));
        doReturn(JsonPath.parse(responseBody)).when(validation).internalPost(Mockito.anyString(), Mockito.anyString());
        return validation;
    }

    static ValueSet anyValueSet() {
        List<ValueSetExpansionContainsComponent> values = IntStream.range(0, 16)
                .mapToObj(i -> anyValueSetExpansionContainsComponent())
                .collect(Collectors.toList());

        ValueSetExpansionComponent ext = new ValueSetExpansionComponent();
        ext.setId(anyString());
        ext.setContains(values);

        ValueSet valueSet = new ValueSet();
        valueSet.setId(anyString());
        valueSet.setExpansion(ext);

        return valueSet;
    }

    static ValueSetExpansionContainsComponent anyValueSetExpansionContainsComponent() {
        ValueSetExpansionContainsComponent cmp = new ValueSetExpansionContainsComponent();
        cmp.setId(anyString());
        cmp.setCode(anyString());
        cmp.setSystem(anyString());
        cmp.setDisplay(anyString());
        return cmp;
    }

    static String anyString() {
        return PrimitiveGenerators.letterStrings(1, 16).next();
    }
}
