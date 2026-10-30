"""Poll App Store Connect for processed build; print only public identifiers."""
import json
import os
from pathlib import Path
import sys
import time
from urllib.parse import urlencode
from urllib.request import Request, urlopen

import jwt


def api(path, params):
    now = int(time.time())
    token = jwt.encode(
        {"iss": os.environ["ASC_ISSUER_ID"], "iat": now, "exp": now + 600,
         "aud": "appstoreconnect-v1"},
        Path(os.environ["ASC_KEY_FILE"]).read_text(), algorithm="ES256",
        headers={"kid": os.environ["ASC_KEY_ID"]},
    )
    url = "https://api.appstoreconnect.apple.com" + path + "?" + urlencode(params)
    with urlopen(Request(url, headers={"Authorization": "Bearer " + token}), timeout=30) as response:
        return json.load(response)["data"]


def processed(builds, wanted):
    for build in builds:
        if build["attributes"].get("version") == wanted:
            return build["id"], build["attributes"].get("processingState")
    return None, None


def main(number, output):
    apps = api("/v1/apps", {"filter[bundleId]": "com.infinityball.pitchpact"})
    if len(apps) != 1:
        sys.exit(f"Expected one ASC app record for com.infinityball.pitchpact, found {len(apps)}")
    app = apps[0]["id"]
    deadline = time.monotonic() + 15 * 60
    while time.monotonic() < deadline:
        build, state = processed(api("/v1/builds", {"filter[app]": app, "sort": "-uploadedDate", "limit": "15"}), number)
        print(f"ASC app={app} build={build or 'pending'} number={number} processing={state or 'pending'}", flush=True)
        if state in {"VALID", "COMPLETE"}:
            Path(output).write_text(f"app={app} build={build} number={number} processing={state}\n")
            return
        if state in {"FAILED", "INVALID"}:
            sys.exit(f"TestFlight processing failed: {state}")
        time.sleep(30)
    sys.exit(f"TestFlight build {number} did not finish processing within 15 minutes")


if __name__ == "__main__":
    if len(sys.argv) == 3:
        main(sys.argv[1], sys.argv[2])
    else:
        assert processed([{"id": "42", "attributes": {"version": "9", "processingState": "COMPLETE"}}], "9") == ("42", "COMPLETE")
        assert processed([], "9") == (None, None)
        print("poller self-check OK")
