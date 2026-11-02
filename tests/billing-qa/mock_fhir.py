#!/usr/bin/env python3
"""Local EM-51 QA fixture, not a clinical terminology service."""
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

VALID_CODES = {
    "http://hl7.org/fhir/sid/icd-10-cm": {"E11.9", "I10"},
    "http://www.ama-assn.org/go/cpt": {"99213", "99214"},
    "https://www.cms.gov/Medicare/Coding/HCPCSReleaseCodeSets": {"J0129", "G0008"},
    "http://snomed.info/sct": {"44054006", "38341003"},
}
BILLING_SUBSET = "http://mock-fhir:8090/ValueSet/billing-subset"


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        self.reply(200, {"status": "ok"})

    def do_POST(self):
        try:
            bundle = json.loads(self.rfile.read(int(self.headers.get("Content-Length", "0"))))
            if bundle.get("resourceType") != "Bundle" or bundle.get("type") != "batch":
                raise ValueError("Expected a batch Bundle")
            entries = []
            for entry in bundle["entry"]:
                request = entry["request"]
                if request["method"] != "POST" or request["url"] not in (
                    "CodeSystem/$validate-code", "ValueSet/$validate-code"
                ):
                    raise ValueError("Expected POST CodeSystem/ValueSet $validate-code")
                parameters = {
                    p["name"]: next(v for k, v in p.items() if k.startswith("value"))
                    for p in entry["resource"]["parameter"]
                }
                isValueSet = request["url"].startswith("ValueSet/")
                system = parameters["system"] if isValueSet else parameters["url"]
                valid = parameters["code"] in VALID_CODES.get(system, set())
                if isValueSet:
                    valid = valid and parameters["url"] == BILLING_SUBSET
                entries.append({
                    "response": {"status": "200 OK"},
                    "resource": {
                        "resourceType": "Parameters",
                        "parameter": [
                            {"name": "message", "valueString": "Known QA code" if valid else "Unknown QA billing code"},
                            {"name": "result", "valueBoolean": valid},
                        ],
                    },
                })
            print(f"FHIR batch: {len(entries)} checks", flush=True)
            self.reply(200, {"resourceType": "Bundle", "type": "batch-response", "entry": entries})
        except (ValueError, KeyError, TypeError, StopIteration) as error:
            self.reply(400, {"resourceType": "OperationOutcome", "issue": [{
                "severity": "error", "code": "invalid", "diagnostics": str(error)
            }]})

    def reply(self, status, resource):
        body = json.dumps(resource).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/fhir+json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


if __name__ == "__main__":
    ThreadingHTTPServer(("0.0.0.0", 8090), Handler).serve_forever()
