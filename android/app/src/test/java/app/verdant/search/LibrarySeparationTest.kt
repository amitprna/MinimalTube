package app.verdant.search

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LibrarySeparationTest {
    @Test fun tabsRememberIndependentSelectionsAndAllAffectsOnlyOneTab() {
        var groups=selectTabGroup(emptyMap(),0,"Finance")
        groups=selectTabGroup(groups,2,"AI/ML")
        assertNull(groups[1]);assertEquals("Finance",groups[0]);assertEquals("AI/ML",groups[2])
        groups=selectTabGroup(groups,1,"Health")
        groups=selectTabGroup(groups,1,null)
        assertNull(groups[1]);assertEquals("Finance",groups[0]);assertEquals("AI/ML",groups[2]);assertNull(groups[3])
    }
    @Test fun durableOrganisationSurvivesEmptyCacheAndExplicitDeletionWinsOverOldCopy() {
        val organisation=LibraryData(groups=setOf("Finance"),groupIds=mapOf("Finance" to "FIN"),rules=mapOf("UC1" to ChannelRule(groups=setOf("Finance"))))
        val durable=LibraryStore.decode(LibraryStore.encode(organisation))
        val recovered=withOrganisation(LibraryData(),durable)
        assertEquals(organisation.groups,recovered.groups);assertEquals(organisation.rules,recovered.rules)
        val channel=Channel("UC1","One")
        val library=organisation.copy(channels=listOf(channel))
        assertEquals(listOf(channel),withOrganisation(library,durable).channels)
        assertTrue(withOrganisation(library,LibraryData()).groups.isEmpty())
        assertEquals(listOf(channel),withOrganisation(library,LibraryData()).channels)
    }
    @Test fun signedInUnsubscribeDoesNotRemoveLocalChannelOnRemoteFailure()=runBlocking {
        var removed=false
        try {
            changeSubscription(true,{throw SearchFailure("forbidden","Permission required")},{removed=true})
            fail("Must propagate the YouTube error")
        } catch(_:SearchFailure) { }
        assertFalse(removed)
    }
    @Test fun signedInChangesWaitForGoogleAndGuestsStayLocal()=runBlocking {
        val events=mutableListOf<String>()
        changeSubscription(true,{events+="YouTube confirmed"},{events+="local update"})
        assertEquals(listOf("YouTube confirmed","local update"),events)
        events.clear()
        changeSubscription(false,{fail("Guest must not call YouTube")},{events+="local update"})
        assertEquals(listOf("local update"),events)
    }
}
