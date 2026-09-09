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
import java.util.HashMap;
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

    private final Map<String, Provider> provider = new HashMap<>();

    private final Map<String, BillingProfile> billingProfiles = new HashMap<>();

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

        private boolean enabled = true;

        private String provider;

        private final List<String> templateIds = new ArrayList<>();

        private final List<String> codeSystems = new ArrayList<>();

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
            this.templateIds.clear();
            if (templateIds != null) {
                this.templateIds.addAll(templateIds);
            }
        }

        public List<String> getCodeSystems() {
            return codeSystems;
        }

        public void setCodeSystems(List<String> codeSystems) {
            this.codeSystems.clear();
            if (codeSystems != null) {
                this.codeSystems.addAll(codeSystems);
            }
        }
    }
}
