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
package org.ehrbase.configuration.config.validation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@link ConfigurationProperties} for external terminology validation.
 */
@ConfigurationProperties(prefix = "validation.external-terminology")
public class ExternalValidationProperties {

    private boolean enabled = false;

    private boolean authenticate = false;

    private boolean failOnError = false;

    private final Map<String, Provider> provider = new LinkedHashMap<>();

    private final Map<String, BillingProfile> billingProfiles = new LinkedHashMap<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isAuthenticate() {
        return authenticate;
    }

    public void setAuthenticate(boolean authenticate) {
        this.authenticate = authenticate;
    }

    public boolean isFailOnError() {
        return failOnError;
    }

    public void setFailOnError(boolean failOnError) {
        this.failOnError = failOnError;
    }

    public Map<String, Provider> getProvider() {
        return provider;
    }

    public Map<String, BillingProfile> getBillingProfiles() {
        return billingProfiles;
    }

    public enum ProviderType {
        FHIR
    }

    public static class Provider {

        private String oauth2Client;

        private ProviderType type;

        private String url;

        public String getOauth2Client() {
            return oauth2Client;
        }

        public void setOauth2Client(String oauth2Client) {
            this.oauth2Client = oauth2Client;
        }

        public ProviderType getType() {
            return type;
        }

        public void setType(ProviderType type) {
            this.type = type;
        }

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }
    }

    public static class BillingProfile {

        private boolean enabled;

        private String provider;

        private List<String> templateIds = new ArrayList<>();

        private CodeSystems codeSystems = new CodeSystems();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public List<String> getTemplateIds() {
            return templateIds;
        }

        public void setTemplateIds(List<String> templateIds) {
            this.templateIds = templateIds == null ? new ArrayList<>() : new ArrayList<>(templateIds);
        }

        public CodeSystems getCodeSystems() {
            return codeSystems;
        }

        public void setCodeSystems(CodeSystems codeSystems) {
            this.codeSystems = codeSystems == null ? new CodeSystems() : codeSystems;
        }
    }

    public static class CodeSystems {

        private CodeSystem icd10 = new CodeSystem(
                true, "http://hl7.org/fhir/sid/icd-10-cm", List.of("ICD10", "ICD-10", "ICD10CM", "ICD-10-CM"), null);

        private CodeSystem cpt =
                new CodeSystem(true, "http://www.ama-assn.org/go/cpt", List.of("CPT", "CPT4", "CPT-4"), null);

        private CodeSystem hcpcs = new CodeSystem(
                true, "https://www.cms.gov/Medicare/Coding/HCPCSReleaseCodeSets", List.of("HCPCS"), null);

        private CodeSystem snomedCt = new CodeSystem(
                true, "http://snomed.info/sct", List.of("SNOMED-CT", "SNOMED CT", "SNOMEDCT", "SNOMED"), null);

        public CodeSystem getIcd10() {
            return icd10;
        }

        public void setIcd10(CodeSystem icd10) {
            this.icd10 = icd10;
        }

        public CodeSystem getCpt() {
            return cpt;
        }

        public void setCpt(CodeSystem cpt) {
            this.cpt = cpt;
        }

        public CodeSystem getHcpcs() {
            return hcpcs;
        }

        public void setHcpcs(CodeSystem hcpcs) {
            this.hcpcs = hcpcs;
        }

        public CodeSystem getSnomedCt() {
            return snomedCt;
        }

        public void setSnomedCt(CodeSystem snomedCt) {
            this.snomedCt = snomedCt;
        }
    }

    public static class CodeSystem {

        private boolean enabled = true;

        private String system;

        private List<String> terminologyIds = new ArrayList<>();

        private String valueSet;

        public CodeSystem() {}

        private CodeSystem(boolean enabled, String system, List<String> terminologyIds, String valueSet) {
            this.enabled = enabled;
            this.system = system;
            this.terminologyIds = new ArrayList<>(terminologyIds);
            this.valueSet = valueSet;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getSystem() {
            return system;
        }

        public void setSystem(String system) {
            this.system = system;
        }

        public List<String> getTerminologyIds() {
            return terminologyIds;
        }

        public void setTerminologyIds(List<String> terminologyIds) {
            this.terminologyIds = terminologyIds == null ? new ArrayList<>() : new ArrayList<>(terminologyIds);
        }

        public String getValueSet() {
            return valueSet;
        }

        public void setValueSet(String valueSet) {
            this.valueSet = valueSet;
        }
    }
}
