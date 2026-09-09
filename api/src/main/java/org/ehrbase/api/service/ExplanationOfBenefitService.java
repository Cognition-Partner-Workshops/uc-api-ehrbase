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
package org.ehrbase.api.service;

import java.util.List;
import org.ehrbase.api.dto.fhir.FhirExplanationOfBenefit;

public interface ExplanationOfBenefitService {

    record EobPage(List<FhirExplanationOfBenefit> resources, int count, int offset, boolean hasMore) {}

    /**
     * Searches EOBs for a FHIR Patient logical ID resolved against the configured subject namespace.
     *
     * @param subjectId FHIR Patient logical ID
     * @param count requested page size
     * @param offset requested page offset
     * @return a page of EOB resources
     */
    EobPage search(String subjectId, int count, int offset);
}
