"""Create disposable two-team consensus fixture on a local Ktor server for XCUITest."""
import json
import os
import shlex
import sys
import urllib.request
import uuid


def post(endpoint, path, payload, key=None):
    request = urllib.request.Request(
        endpoint + path,
        data=json.dumps(payload).encode(),
        headers={"Content-Type": "application/json", **({"Authorization": "Bearer " + key} if key else {})},
    )
    with urllib.request.urlopen(request, timeout=10) as response:
        return json.load(response)


def main():
    endpoint, output = sys.argv[1:]
    nonce = uuid.uuid4().hex
    def team(name):
        return post(endpoint, "/v0/registry/teams", {
            "listing": {"clubName": name, "region": "Local", "ageGroup": "U10"},
            "idempotencyKey": "ui-team-" + uuid.uuid4().hex,
        })
    home = team("Home " + nonce[:8])
    away = team("Away " + nonce[:8])
    request = post(endpoint, "/v0/registry/teams/" + away["teamCode"] + "/link-requests", {
        "requesterTeamId": home["team"]["id"],
        "idempotencyKey": "ui-link-" + uuid.uuid4().hex,
    }, home["ownerKey"])
    link = post(endpoint, "/v0/registry/links/" + request["id"] + "/accept", {}, away["ownerKey"])
    values = {
        "PITCH_TEST_ENDPOINT": endpoint,
        "PITCH_TEST_LINK": link["id"],
        "PITCH_TEST_HOME": home["team"]["id"],
        "PITCH_TEST_AWAY": away["team"]["id"],
        "PITCH_TEST_HOME_KEY": home["ownerKey"],
        "PITCH_TEST_AWAY_KEY": away["ownerKey"],
    }
    # TEST_RUNNER_ passes environment to XCTest host rather than app; launchEnvironment
    # forwards each side's key only to that side. File stays private on ephemeral runner.
    with open(output, "w", encoding="utf-8") as file:
        os.chmod(output, 0o600)
        for name, value in values.items():
            file.write(f"export TEST_RUNNER_{name}={shlex.quote(value)}\n")
    print("Created local link fixture for XCUITest (credentials withheld)")


if __name__ == "__main__":
    main()
