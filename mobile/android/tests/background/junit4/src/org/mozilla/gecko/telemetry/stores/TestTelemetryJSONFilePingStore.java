/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, you can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.mozilla.gecko.telemetry.stores;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.mozilla.gecko.background.testhelpers.TestRunner;
import org.mozilla.gecko.sync.ExtendedJSONObject;
import org.mozilla.gecko.telemetry.TelemetryPing;
import org.mozilla.gecko.util.FileUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.*;

/**
 * Unit test methods of the {@link TelemetryJSONFilePingStore} class.
 */
@RunWith(TestRunner.class)
public class TestTelemetryJSONFilePingStore {

    private final Pattern ID_PATTERN = Pattern.compile("[^0-9]*([0-9]+)[^0-9]*");

    @Rule
    public TemporaryFolder tempDir = new TemporaryFolder();
    private File testDir;
    private TelemetryJSONFilePingStore testStore;

    @Before
    public void setUp() throws Exception {
        testDir = tempDir.newFolder();
        testStore = new TelemetryJSONFilePingStore(testDir);
    }

    private ExtendedJSONObject generateTelemetryPayload() {
        final ExtendedJSONObject out = new ExtendedJSONObject();
        out.put("str", "a String");
        out.put("int", 42);
        out.put("null", (ExtendedJSONObject) null);
        return out;
    }

    private void assertIsGeneratedPayload(final ExtendedJSONObject actual) throws Exception {
        assertNull("Null field is null", actual.getObject("null"));
        assertEquals("int field is correct", 42, (int) actual.getIntegerSafely("int"));
        assertEquals("str field is correct", "a String", actual.getString("str"));
    }

    private void assertStoreFileCount(final int expectedCount) {
        assertEquals("Store contains " + expectedCount + " item(s)", expectedCount, testDir.list().length);
    }

    @Test
    public void testConstructorOnlyWritesToGivenDir() throws Exception {
        // Constructor is called in @Before method
        assertTrue("Store dir exists", testDir.exists());
        assertEquals("Temp dir contains one dir (the store dir)", 1, tempDir.getRoot().list().length);
    }

    @Test
    public void testStorePingStoresCorrectData() throws Exception {
        assertStoreFileCount(0);

        final int expectedID = 48679;
        final TelemetryPing expectedPing = new TelemetryPing("a/server/url", generateTelemetryPayload(), expectedID);
        testStore.storePing(expectedPing);

        assertStoreFileCount(1);
        final String filename = testDir.list()[0];
        assertTrue("Filename contains expected ID", filename.contains(Integer.toString(expectedID)));
        final JSONObject actual = FileUtils.readJSONObjectFromFile(new File(testDir, filename));
        assertEquals("Ping url paths are equal", expectedPing.getURLPath(), actual.getString(TelemetryJSONFilePingStore.KEY_URL_PATH));
        assertIsGeneratedPayload(new ExtendedJSONObject(actual.getString(TelemetryJSONFilePingStore.KEY_PAYLOAD)));
    }

    @Test
    public void testStorePingMultiplePingsStoreSeparateFiles() throws Exception {
        assertStoreFileCount(0);
        for (int i = 1; i < 10; ++i) {
            testStore.storePing(new TelemetryPing("server " + i, generateTelemetryPayload(), i));
            assertStoreFileCount(i);
        }
    }

    @Test
    public void testStorePingReleasesFileLock() throws Exception {
        assertStoreFileCount(0);
        testStore.storePing(new TelemetryPing("server", generateTelemetryPayload(), 0));
        assertStoreFileCount(1);
        final File file = new File(testDir, testDir.list()[0]);
        final FileOutputStream stream = new FileOutputStream(file);
        try {
            assertNotNull("File lock is released after store write", stream.getChannel().tryLock());
        } finally {
            stream.close(); // releases lock
        }
    }

    @Test
    public void testGetAllPings() throws Exception {
        final String urlPrefix = "url";
        writeTestPingsToStore(3, urlPrefix);

        final ArrayList<TelemetryPing> pings = testStore.getAllPings();
        for (final TelemetryPing ping : pings) {
            final int id = ping.getUniqueID(); // we use ID as a key for specific pings and check against the url values.
            assertEquals("Expected url path value received", urlPrefix + id, ping.getURLPath());
            assertIsGeneratedPayload(ping.getPayload());
        }
    }

    @Test
    public void testMaybePrunePingsDoesNothingIfAtMax() throws Exception {
        final int pingCount = TelemetryJSONFilePingStore.MAX_PING_COUNT;
        writeTestPingsToStore(pingCount, "whatever");
        assertStoreFileCount(pingCount);
        testStore.maybePrunePings();
        assertStoreFileCount(pingCount);
    }

    @Test
    public void testMaybePrunePingsPrunesIfAboveMax() throws Exception {
        final int pingCount = TelemetryJSONFilePingStore.MAX_PING_COUNT + 1;
        writeTestPingsToStore(pingCount, "whatever");
        assertStoreFileCount(pingCount);
        testStore.maybePrunePings();
        assertStoreFileCount(TelemetryJSONFilePingStore.MAX_PING_COUNT);

        final HashSet<Integer> existingIDs = new HashSet<>(TelemetryJSONFilePingStore.MAX_PING_COUNT);
        for (final String filename : testDir.list()) {
            existingIDs.add(getIDFromFilename(filename));
        }
        assertFalse("Smallest ID was removed", existingIDs.contains(1));
    }

    @Test
    public void testOnUploadAttemptCompleted() throws Exception {
        writeTestPingsToStore(10, "url");
        final HashSet<Integer> unuploadedPingIDs = new HashSet<>(Arrays.asList(1, 3, 5, 7, 9));
        final HashSet<Integer> removedPingIDs = new HashSet<>(Arrays.asList(2, 4, 6, 8, 10));
        testStore.onUploadAttemptComplete(removedPingIDs);

        for (final String unuploadedFilePath : testDir.list()) {
            final int unuploadedID = getIDFromFilename(unuploadedFilePath);
            assertFalse("Unuploaded ID is not in removed ping IDs", removedPingIDs.contains(unuploadedID));
            assertTrue("Unuploaded ID is in unuploaded ping IDs", unuploadedPingIDs.contains(unuploadedID));
            unuploadedPingIDs.remove(unuploadedID);
        }
        assertTrue("All non-successful-upload ping IDs were matched", unuploadedPingIDs.isEmpty());
    }

    @Test
    public void testGetPingFileContainsID() throws Exception {
        final int expected = 1234567890;
        final File file = testStore.getPingFile(expected);
        assertTrue("Ping filename contains ID", file.getName().contains(Integer.toString(expected)));
    }

    @Test // assumes {@link TelemetryJSONFilePingStore.getPingFile(String)} is working.
    public void testGetIDFromFilename() throws Exception {
        final int expectedID = 465739201;
        final File file = testStore.getPingFile(expectedID);
        assertEquals("Retrieved ID from filename", expectedID, TelemetryJSONFilePingStore.getIDFromFilename(file.getName()));
    }

    private int getIDFromFilename(final String filename) {
        final Matcher matcher = ID_PATTERN.matcher(filename);
        assertTrue("Filename contains ID", matcher.matches());
        return Integer.parseInt(matcher.group(1));
    }

    /**
     * Writes pings to store without using store API with:
     *   id = 1 to count (inclusive)
     *   server = urlPrefix + id
     *   payload = generated payload
     *
     * Note: assumes {@link TelemetryJSONFilePingStore#getPingFile(long)} works.
     */
    private void writeTestPingsToStore(final int count, final String urlPrefix) throws Exception {
        for (int i = 1; i <= count; ++i) {
            final JSONObject obj = new JSONObject()
                    .put(TelemetryJSONFilePingStore.KEY_URL_PATH, urlPrefix + i)
                    .put(TelemetryJSONFilePingStore.KEY_PAYLOAD, generateTelemetryPayload());
            FileUtils.writeJSONObjectToFile(testStore.getPingFile(i), obj);
        }
    }
}
