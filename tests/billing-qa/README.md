# EM-51 billing QA

Synthetic local fixtures only: this mock is not a clinical terminology service.
Run commands from the repository root. Requires Docker Compose **2.24.4+**
(`!override` support), curl, jq, and the JDK required by the current pom (Java 25).
The override uses a locally built image, binds HTTP only to loopback, and does
not publish the database port. The root stack's Keycloak service is retained,
but this demo uses Basic auth, not OAuth2.

## Build and start

```bash
# On the prepared QA host (otherwise select your own Java 25 installation):
export JAVA_HOME=/home/ubuntu/jdk-25.0.5+8
export PATH="$JAVA_HOME/bin:/opt/apache-maven-3.9.9/bin:$PATH"
# If application/target/ehrbase.jar is not already built from this branch:
mvn -P full verify
docker build -t ehrbase/em51-billing-qa:local .
dc() { docker compose -p em51-qa -f docker-compose.yml -f docker-compose.billing-qa.yml "$@"; }
BILLING_PROFILE_ENABLED=true dc up -d

export BASE=http://localhost:8080/ehrbase
# Reuse the repository's existing local demo credentials; do not use them in production.
. ./.env.ehrbase
export QA_USER="$SECURITY_AUTHUSER" QA_PASSWORD="$SECURITY_AUTHPASSWORD"
export QA_ADMIN="$SECURITY_AUTHADMINUSER" QA_ADMIN_PASSWORD="$SECURITY_AUTHADMINPASSWORD"
curl --retry 30 --retry-delay 2 --retry-all-errors --max-time 3 \
  -fsS -u "$QA_ADMIN:$QA_ADMIN_PASSWORD" "$BASE/management/health"
# Expected: HTTP 200, status UP.
```

Swagger UI: **http://localhost:8080/ehrbase/swagger-ui/index.html**.
The browser prompts for Basic auth: use `SECURITY_AUTHUSER` and
`SECURITY_AUTHPASSWORD` from the root `.env.ehrbase` file.
The management health endpoint requires the admin credentials above.
Swagger HTML can also be checked with:

```bash
curl -sS -u "$QA_USER:$QA_PASSWORD" -o /dev/null -w 'Swagger HTTP %{http_code}\n' \
  "$BASE/swagger-ui/index.html"
# Expected: Swagger HTTP 200
```

## Upload templates and create an EHR

Do template uploads once per fresh database (a repeat upload returns 409).
Both OPTs contain ICD10 and CPT external terminology constraints, with no local
code list. They differ in template ID; only the billing ID is profile-scoped.
Normal external-template terminology validation is disabled so the billing
profile alone decides whether these codes are accepted.

```bash
for template in billing-qa.en.v1 non-billing-qa.en.v1; do
  curl -sS -i -u "$QA_USER:$QA_PASSWORD" -H 'Content-Type: application/xml' \
    --data-binary "@tests/billing-qa/$template.opt" \
    "$BASE/rest/openehr/v1/definition/template/adl1.4"
done
# Each: HTTP/1.1 201
export EHR_ID=$(curl -fsS -u "$QA_USER:$QA_PASSWORD" -X POST \
  -H 'Prefer: return=representation' "$BASE/rest/openehr/v1/ehr" | jq -r '.ehr_id.value')
echo "$EHR_ID"

post_composition() {
  curl -sS -i -u "$QA_USER:$QA_PASSWORD" \
    -H 'Content-Type: application/json' -H 'Prefer: return=representation' \
    --data-binary "@tests/billing-qa/$1.json" \
    "$BASE/rest/openehr/v1/ehr/$EHR_ID/composition?format=JSON"
}
```

`format=JSON` means canonical JSON here; `CANONICAL_JSON` is not an accepted API
format value. `Prefer: return=representation` selects 201 rather than the default
204 success response.

## Five scenarios

```bash
post_composition valid                       # 201: ICD10 E11.9 and CPT 99213
post_composition invalid-icd10               # 422: NOT-ICD10
post_composition invalid-cpt                 # 422: NOT-CPT
post_composition non-billing-invalid-icd10   # 201: non-billing template scope

# Recreate ONLY ehrbase; retain the database, mock, templates and EHR.
# "restart" alone cannot apply changed environment variables.
BILLING_PROFILE_ENABLED=false dc up -d --no-deps --force-recreate ehrbase
curl --retry 30 --retry-delay 2 --retry-all-errors --max-time 3 \
  -fsS -u "$QA_ADMIN:$QA_ADMIN_PASSWORD" "$BASE/management/health"
post_composition invalid-icd10               # 201: profile disabled

# Restore enabled state and LEAVE THE STACK RUNNING for recording.
BILLING_PROFILE_ENABLED=true dc up -d --no-deps --force-recreate ehrbase
curl --retry 30 --retry-delay 2 --retry-all-errors --max-time 3 \
  -fsS -u "$QA_ADMIN:$QA_ADMIN_PASSWORD" "$BASE/management/health"
dc ps
dc logs --tail=20 mock-fhir
```

A 422 body includes `profile=demo`, `codeSystem=icd10` or `cpt`, `system`,
`code`, and `path`, ending with `Unknown QA billing code`.
The mock logs `FHIR batch: 2 checks` for each scoped composition; the non-billing
and disabled-profile requests do not call it.

## Mock and configuration

`mock_fhir.py` uses only Python's standard library in `python:3.12-alpine`.
It accepts batch Bundle POSTs, handles CodeSystem and ValueSet validate-code
entries, and returns a batch-response Bundle. Parameters deliberately put
`message` before `result`, exercising name-based parsing.

| Code system | Known valid fixture codes |
| --- | --- |
| ICD-10-CM | E11.9, I10 |
| CPT | 99213, 99214 |
| HCPCS | J0129, G0008 |
| SNOMED CT | 44054006, 38341003 |

All other codes return false. The SNOMED billing subset URL is
`http://mock-fhir:8090/ValueSet/billing-subset`; any other ValueSet URL returns
false. `application.yml` configures all four systems, aliases, fail-closed
behavior, and the single-template `demo` profile. Its enabled flag defaults to
true in this QA override and is controlled by `BILLING_PROFILE_ENABLED`.

All compositions and both OPTs live alongside this README.
The database remains in the running stack; do not run `dc down -v` before
recording. No new Maven dependencies or production application changes.
