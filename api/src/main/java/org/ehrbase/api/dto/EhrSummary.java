/*
 * Copyright (c) 2026 vitasystems GmbH.
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
package org.ehrbase.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Summary of the live (head) compositions of an EHR.
 *
 * @param ehrId            Identifier of the EHR
 * @param compositionCount Number of live compositions
 * @param lastModified     Time of the most recent composition change, <code>null</code> if there are no compositions
 * @param templateIds      Distinct template IDs used by the live compositions
 */
public record EhrSummary(
        @JsonProperty(value = "ehr_id") UUID ehrId,
        @JsonProperty(value = "composition_count") long compositionCount,

        @JsonProperty(value = "last_modified") @JsonInclude(JsonInclude.Include.ALWAYS)
        OffsetDateTime lastModified,

        @JsonProperty(value = "template_ids") List<String> templateIds) {}
