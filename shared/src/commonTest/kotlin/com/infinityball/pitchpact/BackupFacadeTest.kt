package com.infinityball.pitchpact

import com.infinityball.pitchpact.domain.*
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BackupFacadeTest {
    @Test fun csvQuotesInputAndNeverExportsPersonalDetails() {
        val b = BackupV1(
            teams = listOf(Team("h", "Home", 1), Team("a", "Away", 1)),
            players = listOf(Player("p", "h", "Child", 9, 1)),
            guardianContacts = listOf(GuardianContact("g", "p", "Guardian", phone = "private-number")),
            fixtures = listOf(Fixture("f", "Cup, \"Final\"", "h", "a", 1, 30, resultStatus = ResultStatus.OFFICIAL, officialHome = 2, officialAway = 1)),
            availability = listOf(AvailabilityResponse("f", "p", Availability.YES)),
        )
        val saved = BackupCodec.encode(b)
        val facade = BackupFacade()
        assertTrue(facade.preview(saved).startsWith("OK:"))
        assertTrue(facade.preview("{\"version\":9}").startsWith("ERROR:"))
        for (kind in listOf("schedule", "results", "availability")) {
            val csv = facade.export(saved, kind)
            assertTrue(csv.endsWith("\r\n"))
            assertTrue(csv.contains("\"Cup, \"\"Final\"\"\""))
            assertFalse(csv.contains("Child"))
            assertFalse(csv.contains("Guardian"))
            assertFalse(csv.contains("private-number"))
        }
        assertTrue(facade.export(saved, "schedule").contains("official"))
        assertTrue(facade.export(saved, "results").contains(",2-1"))
        assertTrue(facade.export(saved, "availability").contains("1,0,0,0"))
        assertTrue(facade.export("{\"version\":9}", "schedule").startsWith("ERROR:"))
    }
}
