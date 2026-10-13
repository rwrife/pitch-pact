# M6 Team Registry & Shared Fixture Coordination — Privacy Review

This artifact satisfies the privacy review gate of Milestone 6 / Issue #6.

Youth soccer rosters carry names, dates of birth, jersey numbers, and guardian contact info (phones/emails). The Pitch Pact privacy posture is contractual: **none of this data ever touches the global registry or wire coordination paths**.

## Surface Analysis: Stored vs. Registry-Visible Fields

| Field | Local Device (GRDB/Room) | Server State (`registry-state.json`) | Visible in Public Registry Search (`/v0/registry/teams`) | Visible to Linked Opponent (`/v0/links/{id}/fixtures`) |
|---|---|---|---|---|
| **Club/Team Name** | Yes | Yes (`RegistryListing.clubName`) | **Yes** (when `listed = true`) | Yes |
| **Region** | Yes | Yes (`RegistryListing.region`) | **Yes** (when `listed = true`) | Yes |
| **Age Group** (e.g. U10, U12) | Yes | Yes (`RegistryListing.ageGroup`) | **Yes** (when `listed = true`) | Yes |
| **Team Code** (`TEAM-…`) | Yes (Keychain / SharedPreferences) | Yes (index table `codeToTeamId`) | **No** (only visible to team owner) | **No** (used only during link request handshake) |
| **Owner Capability Secret** (`OWN-…`) | Yes (Keychain / SharedPreferences) | Yes (`TeamRecord.ownerKey`) | **No** (never returned by read APIs, never logged) | **No** |
| **Fixture Kickoff & Location** | Yes | Yes (`FixtureRecord`) | **No** | **Yes** (after link accepted, before/after confirmation) |
| **Uniform Colors** (Home/Away kits) | Yes | Yes (`FixtureRecord`) | **No** | **Yes** (enables clash prevention before match day) |
| **Player Rosters & Names** | Yes (local DB only) | **ABSENT** (no schema, no DTO, no endpoint) | **ABSENT** | **ABSENT** |
| **Guardian Names / Phone / Email** | Yes (local DB only) | **ABSENT** (no schema, no DTO, no endpoint) | **ABSENT** | **ABSENT** |
| **Player RSVP / Availability** | Yes (local DB only) | **ABSENT** (no schema, no DTO, no endpoint) | **ABSENT** | **ABSENT** |
| **Player Jersey Numbers** | Yes (local DB only) | **ABSENT** (no schema, no DTO, no endpoint) | **ABSENT** | **ABSENT** |

## Enforcement Mechanisms

1. **Compile-Time & Serialization Tests**:
   - `shared/src/commonTest/kotlin/com/infinityball/pitchpact/dto/M6PrivacyTest.kt` reflexively asserts that neither `RegistryListing`, `RegistryTeam`, `LinkRequestView`, `LinkView`, `CoordinatedFixture`, nor `FixtureProposal` reference `Player`, `GuardianContact`, `Availability`, or `AvailabilityResponse`.
   - Serialized registry payloads in tests are scanned for strings like `"guardian"`, `"player"`, `"roster"`, and fail closed.

2. **Server-Side Data Isolation**:
   - The `RegistryRepository` data classes (`TeamRecord`, `LinkRequestRecord`, `LinkRecord`, `FixtureRecord`) contain zero player/guardian fields.
   - Even when a server snapshot is written to disk, it contains only the minimal scheduling metadata above.

3. **Client-Side Secret Storage**:
   - iOS: `Credentials` (owner secret and team code) stored in `kSecClassGenericPassword` with `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`. Never included in app-to-app share sheets, URL query parameters, or iCloud backups.
   - Android: `SharedPreferences` in `Context.MODE_PRIVATE`.

4. **Opt-in & Deletion Guarantees**:
   - A team must explicitly opt in to be listed. Visibility can be toggled off at any time (`listed = false`).
   - Rotating a team code invalidates the prior code atomically.
   - Revoking a link terminates access to unconfirmed fixture proposals immediately with HTTP 403.
   - Deleting a team cascades server-side: all owned link requests, links, coordinated fixtures, and lookup keys are destroyed.
