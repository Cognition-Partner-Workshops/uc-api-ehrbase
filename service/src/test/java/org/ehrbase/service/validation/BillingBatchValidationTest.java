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
import static org.mockito.Mockito.*;

import com.jayway.jsonpath.JsonPath;
import java.util.*;
import org.ehrbase.openehr.sdk.validation.terminology.ExternalTerminologyValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BillingBatchValidationTest {
    private static FhirTerminologyValidation.BillingCode code() {
        return new FhirTerminologyValidation.BillingCode("claims", "icd10", "http://icd10", "A01", "/value", null);
    }

    @Test
    void deduplicatesAndReadsParametersByName() {
        var provider = spy(new FhirTerminologyValidation("http://unused"));
        doReturn(JsonPath.parse("""
            {"resourceType":"Bundle","type":"batch-response","entry":[
            {"response":{"status":"200"},"resource":{"resourceType":"Parameters","parameter":[
            {"name":"message","valueString":"unknown"},{"name":"result","valueBoolean":false}]}}]}
            """)).when(provider).internalBatch(any());
        assertThat(provider.validateCodes(List.of(code(), code())))
                .singleElement()
                .satisfies(v -> assertThat(v.toString())
                        .contains(
                                "profile=claims",
                                "codeSystem=icd10",
                                "system=http://icd10",
                                "code=A01",
                                "path=/value",
                                "unknown"));
        verify(provider).internalBatch(argThat(bundle -> ((List<?>) bundle.get("entry")).size() == 1));
    }

    @Test
    void emptyCodesDoNotCallServer() {
        var provider = spy(new FhirTerminologyValidation("http://unused"));
        assertThat(provider.validateCodes(List.of())).isEmpty();
        verify(provider, never()).internalBatch(any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void malformedBundleRespectsFailOnError(boolean fail) {
        var provider = spy(new FhirTerminologyValidation("http://unused", fail));
        doReturn(JsonPath.parse("{}")).when(provider).internalBatch(any());
        if (fail) {
            assertThatThrownBy(() -> provider.validateCodes(List.of(code())))
                    .isInstanceOf(ExternalTerminologyValidationException.class);
        } else {
            assertThat(provider.validateCodes(List.of(code()))).isEmpty();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void ioErrorRespectsFailOnError(boolean fail) {
        var provider = spy(new FhirTerminologyValidation("http://unused", fail));
        doThrow(new org.springframework.web.reactive.function.client.WebClientRequestException(
                        new java.io.IOException("offline"),
                        org.springframework.http.HttpMethod.POST,
                        java.net.URI.create("http://unused"),
                        org.springframework.http.HttpHeaders.EMPTY))
                .when(provider)
                .internalBatch(any());
        if (fail) {
            assertThatThrownBy(() -> provider.validateCodes(List.of(code())))
                    .isInstanceOf(ExternalTerminologyValidationException.class);
        } else {
            assertThat(provider.validateCodes(List.of(code()))).isEmpty();
        }
    }
}
