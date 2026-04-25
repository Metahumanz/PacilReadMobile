package com.metahumanz.pacilread.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.metahumanz.pacilread.sync.WebDavClient;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class SettingsStoreSyncContractTest {
    private static final String PREFS_NAME = "pacil_read_settings";

    private Context context;
    private SettingsStore settingsStore;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        clearSettings();
        settingsStore = new SettingsStore(context);
    }

    @After
    public void tearDown() {
        clearSettings();
    }

    @Test
    public void defaultPathsUsePacilReadRootAndAndroidSettingsDirectory() {
        settingsStore.setWebDavUrl("https://example.com/dav");
        WebDavClient client = new WebDavClient(settingsStore);

        assertEquals("PacilRead/", settingsStore.getWebDavDir());
        assertEquals("android-settings/", settingsStore.getWebDavSettingsSubdir());
        assertEquals("https://example.com/dav/PacilRead/", client.backupRootBaseUrl());
        assertEquals(
                "https://example.com/dav/PacilRead/android-settings/android-settings.json",
                client.androidSettingsSnapshotUrl()
        );
        assertEquals("https://example.com/dav/", settingsStore.getWebDavProgressBaseUrl());
    }

    @Test
    public void customRootAndAndroidSettingsDirectoryBuildExpectedSnapshotPath() {
        settingsStore.setWebDavUrl("https://example.com/dav/");
        settingsStore.setWebDavDir("Library/PacilRead");
        settingsStore.setWebDavSettingsSubdir("mobile");
        WebDavClient client = new WebDavClient(settingsStore);

        assertEquals("Library/PacilRead/", settingsStore.getWebDavDir());
        assertEquals("mobile/", settingsStore.getWebDavSettingsSubdir());
        assertEquals("https://example.com/dav/Library/PacilRead/", client.backupRootBaseUrl());
        assertEquals(
                "https://example.com/dav/Library/PacilRead/mobile/android-settings.json",
                client.androidSettingsSnapshotUrl()
        );
        assertEquals("https://example.com/dav/Library/", settingsStore.getWebDavProgressBaseUrl());
    }

    @Test
    public void exportAndroidSettingsIncludesTtsApiKeyAndExcludesDeviceAndWebDavSecrets() throws Exception {
        settingsStore.setTtsMimoApiKey("mimo-secret");
        settingsStore.setWebDavUrl("https://example.com/dav/");
        settingsStore.setWebDavUser("dav-user");
        settingsStore.setWebDavPassword("dav-password");
        settingsStore.getReadingStatsDeviceId();

        JSONObject json = settingsStore.exportAndroidPrivateSettingsJson();

        assertEquals("android", json.getString("platform"));
        assertEquals("mimo-secret", json.getString("tts_mimo_api_key"));
        assertFalse(json.has("webdav_url"));
        assertFalse(json.has("webdav_user"));
        assertFalse(json.has("webdav_password"));
        assertFalse(json.has("reading_stats_device_id"));
    }

    @Test
    public void importAndroidSettingsKeepsLocalConnectionAndDeviceIdentity() throws Exception {
        settingsStore.setWebDavUrl("https://local.example/dav/");
        settingsStore.setWebDavUser("local-user");
        settingsStore.setWebDavPassword("local-password");
        String localDeviceId = settingsStore.getReadingStatsDeviceId();

        JSONObject json = new JSONObject();
        json.put("platform", "android");
        json.put("tts_mimo_api_key", "synced-tts-key");
        json.put("app_theme_mode", "dark");
        json.put("webdav_url", "https://remote.example/dav/");
        json.put("webdav_user", "remote-user");
        json.put("webdav_password", "remote-password");
        json.put("reading_stats_device_id", "remote-device");

        settingsStore.importAndroidPrivateSettingsJson(json, null);

        assertEquals("synced-tts-key", settingsStore.getTtsMimoApiKey());
        assertEquals("dark", settingsStore.getAppThemeMode());
        assertEquals("https://local.example/dav/", settingsStore.getWebDavUrl());
        assertEquals("local-user", settingsStore.getWebDavUser());
        assertEquals("local-password", settingsStore.getWebDavPassword());
        assertEquals(localDeviceId, settingsStore.getReadingStatsDeviceId());
    }

    @Test
    public void importRejectsDesktopOrMissingPlatformSnapshots() throws Exception {
        assertImportRejected(new JSONObject().put("platform", "desktop"));
        assertImportRejected(new JSONObject());
    }

    private void assertImportRejected(JSONObject jsonObject) {
        try {
            settingsStore.importAndroidPrivateSettingsJson(jsonObject, null);
            fail("Expected Android platform validation to reject snapshot");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    private void clearSettings() {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit();
    }
}
