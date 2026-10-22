package com.sleeptimer.service;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;

public class AudibleAudioSessionsTest {

    @Test
    public void parsesAndroid10AndCurrentOutputClientsForAudibleUidOnly() throws Exception {
        String dump = "AudioPolicyManager Dump\n"
                + "Outputs dump:\n"
                + "  Client 1:\n"
                + "  - Port Id: 27 Session Id: 123 UID: 10456\n"
                + "  - State: Active\n"
                + "  Port ID: 28; Session ID: 456; uid 10456; State: Active\n"
                + "  Port ID: 29; Session ID: 789; uid 10222; State: Active\n"
                + "  Port ID: 30; Session ID: 0; uid 10456; State: Active\n"
                + "  Port ID: 28; Session ID: 456; uid 10456; State: Active\n"
                + "Inputs dump:\n"
                + "  Port ID: 31; Session ID: 999; uid 10456; State: Active\n";
        assertEquals(new HashSet<>(Arrays.asList(123, 456)),
                AudibleAudioSessions.parseSessions(dump, 10456));
        assertEquals(new HashSet<>(Arrays.asList(123, 456)),
                AudibleAudioSessions.parseSessions(dump.replace("Outputs dump:", "Outputs (2):")
                        .replace("Inputs dump:", "Inputs (1):"), 10456));
    }

    @Test
    public void emptyOutputListIsNotTreatedAsAnError() throws Exception {
        assertEquals(new HashSet<>(),
                AudibleAudioSessions.parseSessions("Outputs dump:\nInputs dump:\n", 10456));
    }

    @Test(expected = IOException.class)
    public void permissionDenialIsNotTreatedAsSuccessfulDetection() throws Exception {
        AudibleAudioSessions.parseSessions("Permission Denial: can't dump AudioPolicyService", 10456);
    }

    @Test(expected = IOException.class)
    public void invalidIdentifiersAreNotSilentlyDiscarded() throws Exception {
        AudibleAudioSessions.parseSessions(
                "Outputs dump:\nPort ID: 1; Session ID: 99999999999; uid 10456;", 10456);
    }
}
