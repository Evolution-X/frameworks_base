/*
 * Copyright (C) 2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.server.appbackup;

import static com.android.server.appbackup.SwiftBackupArchive.require;

import android.content.Context;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.util.BitSet;
import java.util.List;
import java.nio.charset.StandardCharsets;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;

final class SystemWifiBackup {
    private static final int MAX_WIFI_BYTES = 4 << 20;

    static void backup(Context context, SystemDataArchive.Writer writer)
            throws IOException, JSONException {
        WifiManager wifi = manager(context);
        byte[] data = wifi.retrieveBackupData();
        require(data != null && data.length > 0 && data.length <= MAX_WIFI_BYTES,
                "Android could not back up saved Wi-Fi networks");
        validateXml(data);
        JSONObject row = writer.blob(new ByteArrayInputStream(data));
        row.put("kind", "wifi-platform");
        writer.row(row);
    }

    static boolean restore(Context context, JSONObject row, SystemDataArchive archive,
            SystemDataArchive.Check check) throws IOException, JSONException {
        WifiManager wifi = manager(context);
        if (row.getString("kind").equals("wifi-platform")) {
            byte[] data = platformData(row, archive, check);
            check.run();
            wifi.restoreBackupData(data);
            return true;
        }
        WifiConfiguration config = configuration(row.getJSONObject("values"));
        List<WifiConfiguration> existing = wifi.getConfiguredNetworks();
        require(existing != null, "Cannot read saved Wi-Fi networks; turn on Wi-Fi and retry");
        for (WifiConfiguration saved : existing) {
            if (config.SSID.equals(saved.SSID)) return false;
        }
        check.run();
        int networkId = wifi.addNetwork(config);
        require(networkId >= 0, "Android rejected a Wi-Fi network; turn on Wi-Fi and retry");
        require(wifi.enableNetwork(networkId, false), "Could not enable a restored Wi-Fi network");
        return false;
    }

    static byte[] platformData(JSONObject row, SystemDataArchive archive, SystemDataArchive.Check check)
            throws IOException, JSONException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = archive.openBlob(row.getString("file"))) {
            require(SystemDataArchive.hash(in, out, MAX_WIFI_BYTES, check).equals(row.getString("sha256")),
                    "Wi-Fi backup checksum mismatch");
        }
        byte[] data = out.toByteArray();
        validateXml(data);
        return data;
    }

    private static void validateXml(byte[] data) throws IOException {
        final boolean[] version = {false};
        final boolean[] networks = {false};
        parseXml(data, "WifiBackupData", new DefaultHandler() {
            private int mDepth;
            @Override public void startElement(String uri, String local, String name, Attributes attrs)
                    throws SAXException {
                mDepth++;
                if (mDepth != 2) return;
                if (!version[0] && (name.equals("float") || name.equals("int"))
                        && "Version".equals(attrs.getValue("name"))) {
                    String value = attrs.getValue("value");
                    if (value == null || !value.matches("1(?:\\.[0-9]+)?")) {
                        throw new SAXException("Unsupported Wi-Fi backup version");
                    }
                    version[0] = true;
                } else if (version[0] && !networks[0] && name.equals("NetworkList")) {
                    networks[0] = true;
                } else {
                    throw new SAXException("Invalid Wi-Fi backup fields");
                }
            }
            @Override public void endElement(String uri, String local, String name) { mDepth--; }
        });
        require(version[0] && networks[0], "Incomplete Android Wi-Fi backup");
    }

    static void supplementSwift(JSONObject values, JSONObject password) throws IOException, JSONException {
        if (password == null || password.isNull("_rawXmlNode")) return;
        String raw = password.getString("_rawXmlNode");
        if (raw.isEmpty()) return;
        final String[] hidden = {null};
        final StringBuilder keyManagement = new StringBuilder();
        parseXml(raw.getBytes(StandardCharsets.UTF_8), null, new DefaultHandler() {
            private boolean mCapture;
            private boolean mWepKeys;
            @Override public void startElement(String uri, String local, String name, Attributes attrs)
                    throws SAXException {
                String field = attrs.getValue("name");
                if (name.equals("boolean") && "HiddenSSID".equals(field)) hidden[0] = attrs.getValue("value");
                mCapture = name.equals("byte-array") && "AllowedKeyMgmt".equals(field);
                if (name.equals("string-array") && "WEPKeys".equals(field)) mWepKeys = true;
                if (mWepKeys && name.equals("item") && attrs.getValue("value") != null
                        && !attrs.getValue("value").isEmpty()) {
                    throw new SAXException("WEP Wi-Fi backups are not supported");
                }
            }
            @Override public void characters(char[] chars, int start, int length) {
                if (mCapture) keyManagement.append(chars, start, length);
            }
            @Override public void endElement(String uri, String local, String name) {
                mCapture = false;
                if (name.equals("string-array")) mWepKeys = false;
            }
        });
        if (hidden[0] != null) {
            require(hidden[0].equals("true") || hidden[0].equals("false"), "Invalid hidden Wi-Fi setting");
            values.put("hiddenSSID", Boolean.parseBoolean(hidden[0]));
        }
        String hex = keyManagement.toString().replaceAll("\\s", "");
        if (!hex.isEmpty()) {
            require(hex.matches("[0-9a-fA-F]{2,8}") && hex.length() % 2 == 0, "Invalid Wi-Fi security data");
            byte[] bytes = new byte[hex.length() / 2];
            for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
            BitSet bits = BitSet.valueOf(bytes);
            JSONArray array = new JSONArray();
            for (int i = 0; i < bits.length(); i++) array.put(bits.get(i) ? 1 : 0);
            values.put("allowedKeyManagement", array);
        }
    }

    private static void parseXml(byte[] data, String root, DefaultHandler handler) throws IOException {
        require(data.length <= MAX_WIFI_BYTES, "Wi-Fi XML exceeds size limit");
        String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(data)).toString();
        require(text.indexOf('\0') < 0 && !text.contains("<!DOCTYPE") && !text.contains("<!ENTITY"),
                "Wi-Fi backup contains unsupported XML declarations");
        try {
            XMLReader parser = SAXParserFactory.newInstance().newSAXParser().getXMLReader();
            parser.setFeature("http://xml.org/sax/features/external-general-entities", false);
            parser.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            parser.setEntityResolver((publicId, systemId) -> {
                throw new SAXException("External entities are not supported");
            });
            parser.setContentHandler(new DefaultHandler() {
                private int mDepth;
                private int mElements;
                @Override public void startElement(String uri, String local, String name, Attributes attributes)
                        throws SAXException {
                    if (++mElements > 100_000 || ++mDepth > 32
                            || mElements == 1 && root != null && !name.equals(root)) {
                        throw new SAXException("Invalid Wi-Fi backup structure");
                    }
                    handler.startElement(uri, local, name, attributes);
                }
                @Override public void characters(char[] chars, int start, int length) throws SAXException {
                    handler.characters(chars, start, length);
                }
                @Override public void endElement(String uri, String local, String name) throws SAXException {
                    handler.endElement(uri, local, name);
                    mDepth--;
                }
            });
            parser.setErrorHandler(new DefaultHandler() {
                @Override public void error(SAXParseException e) throws SAXException { throw e; }
                @Override public void fatalError(SAXParseException e) throws SAXException { throw e; }
            });
            parser.parse(new InputSource(new StringReader(text)));
        } catch (ParserConfigurationException | SAXException e) {
            throw new IOException("Invalid Android Wi-Fi backup", e);
        }
    }

    static WifiConfiguration configuration(JSONObject values) throws IOException, JSONException {
        WifiConfiguration config = new WifiConfiguration();
        String ssid = values.getString("SSID");
        int ssidBytes = decodedLength(ssid);
        require(ssidBytes > 0 && ssidBytes <= 32, "Invalid Wi-Fi SSID");
        config.SSID = ssid;
        config.hiddenSSID = values.optBoolean("hiddenSSID", false);
        config.allowedKeyManagement = bits(values.optJSONArray("allowedKeyManagement"));
        require(!config.allowedKeyManagement.isEmpty(), "Missing Wi-Fi security mode");
        BitSet unsupported = (BitSet) config.allowedKeyManagement.clone();
        for (int supported : new int[]{WifiConfiguration.KeyMgmt.NONE,
                WifiConfiguration.KeyMgmt.WPA_PSK, WifiConfiguration.KeyMgmt.SAE,
                WifiConfiguration.KeyMgmt.OWE}) unsupported.clear(supported);
        require(unsupported.isEmpty(), "Enterprise and unsupported Wi-Fi security modes cannot be imported");
        require(config.allowedKeyManagement.cardinality() == 1,
                "Mixed Wi-Fi security modes cannot be imported");
        String psk = values.isNull("preSharedKey") ? "" : values.getString("preSharedKey");
        if (config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WPA_PSK)
                || config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.SAE)) {
            require(!psk.isEmpty() && !psk.equals("*"),
                    "Missing or masked Wi-Fi password");
            int bytes = decodedLength(psk);
            boolean quoted = psk.startsWith("\"");
            int minimum = config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.SAE) ? 1 : 8;
            require(quoted ? bytes >= minimum && bytes <= 63 : bytes == 32,
                    "Invalid Wi-Fi password length");
            config.preSharedKey = psk;
        } else {
            require(psk.isEmpty(), "Unexpected password for an open Wi-Fi network");
        }
        config.requirePmf = config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.SAE)
                && !config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WPA_PSK)
                || config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.OWE);
        return config;
    }

    private static int decodedLength(String value) throws IOException {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return StandardCharsets.UTF_8.newEncoder().encode(
                    java.nio.CharBuffer.wrap(value.substring(1, value.length() - 1))).remaining();
        }
        require(!value.isEmpty() && value.length() % 2 == 0 && value.matches("[0-9a-fA-F]+"),
                "Wi-Fi names and passwords must be quoted text or hexadecimal");
        return value.length() / 2;
    }

    private static BitSet bits(JSONArray array) throws JSONException, IOException {
        require(array != null && array.length() <= 32, "Invalid Wi-Fi security bitset");
        BitSet bits = new BitSet();
        for (int i = 0; i < array.length(); i++) {
            int bit = array.getInt(i);
            require(bit == 0 || bit == 1, "Invalid Wi-Fi security bit");
            if (bit == 1) bits.set(i);
        }
        return bits;
    }

    private static WifiManager manager(Context context) throws IOException {
        WifiManager wifi = context.getSystemService(WifiManager.class);
        require(wifi != null, "Wi-Fi service is unavailable");
        return wifi;
    }
}
