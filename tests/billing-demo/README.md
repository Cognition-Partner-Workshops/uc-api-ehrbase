# EM-51 billing terminology demo

This demo runs EHRbase with an in-process-style mock FHIR terminology server and enables the `us-claims` billing profile. It uses Basic authentication from `.env.ehrbase`; source that file in your shell and keep its password values out of logs.

## Build and start

From the repository root:

```bash
export JAVA_HOME="$HOME/jdk-25"
export PATH="$JAVA_HOME/bin:$PATH"
mvn -P release package
docker build -t ehrbase/ehrbase:em51-billing .
docker compose -f docker-compose.yml -f tests/billing-demo/docker-compose.billing-demo.yml up -d
```

The Compose override injects the provider and billing-profile maps through `SPRING_APPLICATION_JSON` to preserve their hyphenated keys. `BILLING_PROFILE_ENABLED` defaults to `true` and controls the `us-claims` profile.

Upload the billing and non-billing templates, then create an EHR:

```bash
source .env.ehrbase
API=http://localhost:8080/ehrbase/rest/openehr/v1
curl -i -u "$SECURITY_AUTHADMINUSER:$SECURITY_AUTHADMINPASSWORD" \
  -H 'Content-Type: application/xml' \
  --data-binary @tests/billing-demo/billing_claim.v1.opt \
  "$API/definition/template/adl1.4"
curl -i -u "$SECURITY_AUTHADMINUSER:$SECURITY_AUTHADMINPASSWORD" \
  -H 'Content-Type: application/xml' \
  --data-binary @tests/billing-demo/non-billing-template.opt \
  "$API/definition/template/adl1.4"
curl -i -u "$SECURITY_AUTHADMINUSER:$SECURITY_AUTHADMINPASSWORD" \
  -X POST "$API/ehr"
```

Copy the created EHR UUID from the `Location` response header into `EHR_ID`, then exercise the expected results:

```bash
EHR_ID='<created EHR UUID>'
for file in valid invalid-icd10 invalid-cpt; do
  curl -i -u "$SECURITY_AUTHADMINUSER:$SECURITY_AUTHADMINPASSWORD" \
    -H 'Content-Type: application/json' \
    -H 'Prefer: return=representation' \
    --data-binary "@tests/billing-demo/$file.json" \
    "$API/ehr/$EHR_ID/composition"
done
curl -i -u "$SECURITY_AUTHADMINUSER:$SECURITY_AUTHADMINPASSWORD" \
  -H 'Content-Type: application/json' \
  -H 'Prefer: return=representation' \
  --data-binary @tests/billing-demo/non-billing.json \
  "$API/ehr/$EHR_ID/composition"
```

Expected status codes are `201` for `valid.json`, `422` for `invalid-icd10.json`, `422` for `invalid-cpt.json`, and `201` for `non-billing.json`. With the profile enabled, the mock server logs one `POST /fhir entries=4` line for each billing composition and none for the non-billing template. The profile-disabled billing POST is accepted without a FHIR batch request.

To prove profile opt-out, restart EHRbase with the profile disabled, POST `invalid-icd10.json` again (expected `201`), then restore the enabled stack:

```bash
BILLING_PROFILE_ENABLED=false docker compose -f docker-compose.yml -f tests/billing-demo/docker-compose.billing-demo.yml up -d --force-recreate ehrbase
# POST invalid-icd10.json again; it should return 201.
BILLING_PROFILE_ENABLED=true docker compose -f docker-compose.yml -f tests/billing-demo/docker-compose.billing-demo.yml up -d --force-recreate ehrbase
```

Swagger UI: `http://localhost:8080/ehrbase/swagger-ui/index.html`. Authentication uses the administrator account in `.env.ehrbase`.
