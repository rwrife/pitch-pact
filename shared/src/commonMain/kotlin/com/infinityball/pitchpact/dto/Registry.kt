package com.infinityball.pitchpact.dto

import kotlinx.serialization.Serializable

/** Public listing: only these three fields are eligible for search or broadcast. */
@Serializable data class RegistryListing(val clubName: String, val region: String, val ageGroup: String)
@Serializable data class RegistryCreate(val listing: RegistryListing, val idempotencyKey: String)
@Serializable data class RegistryTeam(val id: String, val listing: RegistryListing, val listed: Boolean)
@Serializable data class RegistryCredentials(val team: RegistryTeam, val ownerKey: String, val teamCode: String)
@Serializable data class TeamCode(val teamCode: String)
@Serializable data class RegistryVisibility(val listed: Boolean)
@Serializable data class RegistryResult(val status: String)
@Serializable data class LinkRequestInput(val requesterTeamId: String, val idempotencyKey: String)
@Serializable data class LinkRequestView(val id: String, val requesterTeamId: String, val recipientTeamId: String, val state: String, val expiresAt: Long)
@Serializable data class LinkView(val id: String, val firstTeamId: String, val secondTeamId: String, val state: String)
@Serializable data class CoordinatedFixture(
    val id: String, val linkId: String, val homeTeamId: String, val awayTeamId: String,
    val startEpochMillis: Long, val location: String, val homeUniform: String, val awayUniform: String,
    val state: String, val confirmedBy: Set<String> = emptySet(), val revision: Int = 1,
)
@Serializable data class FixtureProposal(
    val startEpochMillis: Long, val location: String, val homeUniform: String, val awayUniform: String,
    val idempotencyKey: String,
)
