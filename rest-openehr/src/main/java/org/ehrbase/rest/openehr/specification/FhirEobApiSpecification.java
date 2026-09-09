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
package org.ehrbase.rest.openehr.specification;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.ehrbase.api.dto.fhir.FhirBundle;
import org.springframework.http.ResponseEntity;

@Tag(name = "FHIR R4 Billing")
public interface FhirEobApiSpecification {

    @Operation(
            summary = "Search ExplanationOfBenefit resources",
            responses = {
                @ApiResponse(
                        responseCode = "200",
                        description = "Search results returned as a FHIR searchset Bundle.",
                        content =
                                @Content(
                                        mediaType = "application/fhir+json",
                                        schema = @Schema(implementation = FhirBundle.class))),
                @ApiResponse(
                        responseCode = "400",
                        description = "The patient reference or paging parameters are invalid."),
                @ApiResponse(responseCode = "401", description = "Authentication is required."),
                @ApiResponse(responseCode = "403", description = "The authenticated principal is not authorized.")
            })
    ResponseEntity<FhirBundle> search(
            @Parameter(
                            description = "FHIR Patient logical reference in the form Patient/{id}.",
                            required = true,
                            example = "Patient/123")
                    String patient,
            @Parameter(description = "Maximum number of resources to return.") Integer count,
            @Parameter(description = "Number of resources to skip.") Integer offset);
}
