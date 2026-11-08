/*
 * Copyright (C) 2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.server.appbackup;

import static android.app.appbackup.AppDataBackupRestoreManager.DATA_TYPE_CALL_LOGS;
import static android.app.appbackup.AppDataBackupRestoreManager.DATA_TYPE_MESSAGES;
import static android.app.appbackup.AppDataBackupRestoreManager.DATA_TYPE_WIFI;
import static com.android.server.appbackup.SwiftBackupArchive.require;

import android.app.appbackup.BackupRecord;
import android.app.appbackup.BackupResult;
import android.util.JsonReader;
import android.util.JsonToken;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

final class SwiftSystemDataImport {
    static final class Result {
        final BackupManifest manifest;
        final BackupResult outcome;

        Result(BackupManifest manifest, BackupResult outcome) {
            this.manifest = manifest;
            this.outcome = outcome;
        }
    }

    private static final class IncompleteMmsException extends IOException {
        IncompleteMmsException(String message) { super(message); }
    }

    private static final SystemDataArchive.Check CHECK = () -> {};
    private static final String[] CALL_MAP = {"numberPresentation:presentation", "countryIso:countryiso",
            "dataUsage:data_usage", "newCall:new", "isRead:is_read", "geoCodedLocation:geocoded_location"};
    private static final String[] SMS_MAP = {"dateSent:date_sent", "errorCode:error_code",
            "replyPathPresent:reply_path_present", "serviceCenter:service_center"};
    private static final String[] MMS_MAP = {"box:msg_box", "dateInSeconds:date", "dateSentInSeconds:date_sent",
            "subject:sub", "subjectCharset:sub_cs", "contentType:ct_t", "contentLocation:ct_l",
            "expiry:exp", "messageClass:m_cls", "type:m_type", "mmsVersion:v", "messageSize:m_size",
            "priority:pri", "readReport:rr", "reportAllowed:rpt_a", "responseStatus:resp_st", "status:st",
            "transactionId:tr_id", "retrieveStatus:retr_st", "retrieveText:retr_txt",
            "retrieveTextCharset:retr_txt_cs", "readStatus:read_status", "contentClass:ct_cls",
            "responseText:resp_txt", "deliveryTime:d_tm", "deliveryReport:d_rpt", "textOnly:text_only"};
    private static final String[] PART_MAP = {"contentType:ct", "charset:chset", "fileName:fn",
            "contentDisposition:cd", "contentId:cid", "contentLocation:cl", "contentStartType:ctt_s",
            "contentTypeType:ctt_t"};

    static Result convert(File source, int type, File staging, int userId) throws IOException {
        SystemDataSchema.requireType(type);
        int[] skipped = {0};
        String[] firstSkipped = {null};
        int imported;
        try {
            File metadata = source;
            File unpacked = new File(staging, "swift-system");
            require(unpacked.mkdir(), "Cannot stage Swift system backup");
            byte[] magic;
            try (InputStream in = new FileInputStream(source)) { magic = in.readNBytes(4); }
            if (magic.length == 4 && magic[0] == 'S' && magic[1] == 'B' && magic[2] == 'A' && magic[3] == '1') {
                require(type != DATA_TYPE_WIFI, "Unexpected Swift Wi-Fi container");
                String root = type == DATA_TYPE_CALL_LOGS ? "calls_backup" : "messages_backup";
                try (SwiftBackupArchive archive = new SwiftBackupArchive(source, "")) {
                    require(archive.entries.size() == 1 && archive.entries.get(0).name.equals(root),
                            "Wrong Swift system backup category");
                    File tar = new File(staging, "swift-system.tar");
                    try {
                        archive.extract(archive.entries.get(0), tar);
                        extractTar(tar, unpacked, root);
                    } finally { tar.delete(); }
                }
                metadata = new File(unpacked, root + (type == DATA_TYPE_CALL_LOGS ? "/call_logs" : "/conversations"));
            } else if (magic.length >= 2 && magic[0] == 'P' && magic[1] == 'K') {
                require(type != DATA_TYPE_WIFI, "Unexpected Swift Wi-Fi container");
                String root = type == DATA_TYPE_CALL_LOGS ? "calls_backup" : "messages_backup";
                extractZip(source, unpacked, root);
                metadata = new File(unpacked, root + (type == DATA_TYPE_CALL_LOGS ? "/call_logs" : "/conversations"));
            }
            require(metadata.isFile() && metadata.length() <= (64L << 20),
                    "Missing Swift metadata or metadata exceeds 64 MiB");
            try (InputStream in = new FileInputStream(metadata)) {
                int first;
                do { first = in.read(); } while (first == ' ' || first == '\n' || first == '\r' || first == '\t');
                if (first != '{') {
                    File clear = new File(staging, "swift-system.json");
                    SwiftBackupGuest.decodeSystemFile(metadata, clear);
                    metadata = clear;
                }
            }
            File payload = new File(staging, SystemDataArchive.FILE_NAME);
            try (SystemDataArchive.Writer writer = new SystemDataArchive.Writer(payload, CHECK);
                    JsonReader reader = new JsonReader(new InputStreamReader(
                            new FileInputStream(metadata), StandardCharsets.UTF_8.newDecoder()))) {
                reader.beginObject();
                require(reader.hasNext() && reader.nextName().equals("items"), "Invalid Swift system backup wrapper");
                reader.beginArray();
                int count = 0;
                while (reader.hasNext()) {
                    require(++count <= SystemDataArchive.MAX_RECORDS, "Too many Swift records");
                    if (type == DATA_TYPE_MESSAGES) {
                        conversation(reader, writer, unpacked, skipped, firstSkipped);
                    } else {
                        JSONObject item = object(reader);
                        if (type == DATA_TYPE_CALL_LOGS) {
                            JSONObject values = map(item, CALL_MAP, SystemDataSchema.CALLS);
                            require(values.optInt("type") != 4, "Voicemail records are not supported");
                            JSONObject row = new JSONObject().put("kind", "call").put("values", values);
                            if (!item.isNull("sourceSimSlotIndex")) row.put("simSlot", item.getInt("sourceSimSlotIndex"));
                            writer.row(row);
                        } else {
                            writer.row(new JSONObject().put("kind", "wifi").put("values", wifi(item)));
                        }
                    }
                }
                reader.endArray();
                reader.endObject();
                require(reader.peek() == JsonToken.END_DOCUMENT, "Trailing Swift system data");
                writer.finish();
                imported = writer.count();
            }
            try (SystemDataArchive archive = new SystemDataArchive(payload)) {
                archive.visit(row -> SystemDataSchema.validate(row, type, archive, CHECK), CHECK);
            }
            require(imported > 0 || skipped[0] == 0,
                    "No complete messages could be imported. " + firstSkipped[0]);
            BackupResult outcome = skipped[0] == 0 ? BackupResult.ok() : BackupResult.partial(
                    "Imported " + imported + " messages; skipped " + skipped[0]
                            + " incomplete or unsupported MMS. " + firstSkipped[0]);
            BackupManifest manifest = SystemDataEngine.pack(type, userId, staging, null,
                    skipped[0] == 0 ? BackupRecord.STATE_OK : BackupRecord.STATE_PARTIAL);
            return new Result(manifest, outcome);
        } catch (JSONException | IllegalStateException e) {
            throw new IOException("Invalid Swift system backup", e);
        }
    }

    private static void conversation(JsonReader reader, SystemDataArchive.Writer writer, File unpacked,
            int[] skipped, String[] firstSkipped)
            throws IOException, JSONException {
        reader.beginObject();
        boolean found = false;
        Set<String> names = new HashSet<>();
        while (reader.hasNext()) {
            String name = reader.nextName();
            require(names.add(name), "Duplicate Swift conversation field");
            if (name.equals("smsItemList") || name.equals("mmsItemList")) {
                found = true;
                if (reader.peek() == JsonToken.NULL) { reader.nextNull(); continue; }
                reader.beginArray();
                while (reader.hasNext()) {
                    JSONObject item = object(reader);
                    if (name.equals("smsItemList")) {
                        writer.row(new JSONObject().put("kind", "sms")
                                .put("values", map(item, SMS_MAP, SystemDataSchema.SMS)));
                    } else {
                        try {
                            writer.row(mms(item, writer, unpacked));
                        } catch (IncompleteMmsException e) {
                            require(++skipped[0] <= SystemDataArchive.MAX_RECORDS,
                                    "Too many Swift messages");
                            if (firstSkipped[0] == null) firstSkipped[0] = e.getMessage();
                        }
                    }
                }
                reader.endArray();
            } else {
                value(reader, 0, new int[]{0});
            }
        }
        reader.endObject();
        require(found, "Swift conversation has no message lists");
    }

    private static JSONObject mms(JSONObject item, SystemDataArchive.Writer writer, File unpacked)
            throws IOException, JSONException {
        JSONObject values = map(item, MMS_MAP, SystemDataSchema.MMS);
        int messageType = values.getInt("m_type");
        if (messageType != 128 && messageType != 132) {
            throw new IncompleteMmsException("MMS delivery reports and pending downloads cannot be imported.");
        }
        JSONArray addresses = new JSONArray();
        JSONArray sourceAddresses = item.getJSONArray("addressItems");
        JSONArray recipients = item.optJSONArray("recipients");
        if (recipients == null || recipients.length() == 0) {
            throw new IncompleteMmsException("Swift MMS backup is missing conversation recipients.");
        }
        for (int i = 0; i < sourceAddresses.length(); i++) {
            JSONObject address = SystemDataSchema.fields(sourceAddresses.getJSONObject(i), SystemDataSchema.ADDRESS);
            addresses.put(address);
        }
        JSONArray parts = new JSONArray();
        JSONArray sourceParts = item.getJSONArray("partItems");
        for (int i = 0; i < sourceParts.length(); i++) {
            JSONObject original = sourceParts.getJSONObject(i);
            JSONObject data = map(original, PART_MAP, SystemDataSchema.PART);
            String cached = original.isNull("cachedFileName") ? "" : original.getString("cachedFileName");
            if (!cached.isEmpty()) requireAttachmentName(cached);
            boolean inline = SystemDataSchema.inlinePart(data.getString("ct"));
            boolean stored = !cached.isEmpty()
                    && new File(unpacked, "messages_backup/mms_data/" + cached).isFile();
            if (!stored && !(inline && (cached.isEmpty() || !data.isNull("text")))) {
                throw new IncompleteMmsException("Referenced MMS attachments are missing from the Swift file.");
            }
        }
        for (int i = 0; i < sourceParts.length(); i++) {
            JSONObject original = sourceParts.getJSONObject(i);
            JSONObject data = map(original, PART_MAP, SystemDataSchema.PART);
            JSONObject part = new JSONObject().put("values", data);
            String cached = original.isNull("cachedFileName") ? "" : original.getString("cachedFileName");
            String ct = data.getString("ct");
            boolean text = SystemDataSchema.inlinePart(ct);
            File file = new File(unpacked, "messages_backup/mms_data/" + cached);
            if (!cached.isEmpty() && file.isFile()) {
                if (text) {
                    require(file.length() <= (1 << 20), "MMS text is too large");
                    data.put("text", Files.readString(file.toPath(), StandardCharsets.UTF_8));
                } else {
                    try (InputStream in = new FileInputStream(file)) {
                        JSONObject blob = writer.blob(in);
                        part.put("file", blob.getString("file")).put("sha256", blob.getString("sha256"));
                    }
                }
            } else {
                require(text, "Swift MMS backup is missing an attachment");
            }
            parts.put(part);
        }
        return new JSONObject().put("kind", "mms").put("values", values)
                .put("addresses", addresses).put("recipients", recipients).put("parts", parts);
    }

    private static JSONObject wifi(JSONObject item) throws IOException, JSONException {
        JSONObject values = new JSONObject();
        JSONObject password = item.optJSONObject("passwordInfo");
        if (password != null) {
            require(password.optString("entEapMethod", "").isEmpty(),
                    "Enterprise Wi-Fi credentials cannot be imported");
        }
        if (!item.isNull("hiddenSSID")) values.put("hiddenSSID", item.getBoolean("hiddenSSID"));
        JSONArray keyManagement = item.optJSONArray("allowedKeyManagement");
        if (keyManagement != null) values.put("allowedKeyManagement", keyManagement);
        SystemWifiBackup.supplementSwift(values, password);
        if (!values.has("SSID")) {
            String ssid = item.getString("SSID");
            values.put("SSID", swiftWifiString(password == null ? ssid : unescape(ssid), true));
        }
        if (!values.has("preSharedKey")) {
            String psk = item.isNull("preSharedKey") ? "" : item.getString("preSharedKey");
            if (password != null) {
                if (psk.isEmpty() && !password.isNull("psk")) psk = password.getString("psk");
                psk = unescape(psk);
            }
            values.put("preSharedKey", swiftWifiString(psk, false));
        }
        JSONArray ciphers = item.optJSONArray("allowedGroupCiphers");
        if (values.getJSONArray("allowedKeyManagement").optInt(0) == 1 && ciphers != null) {
            require(ciphers.optInt(0) != 1 && ciphers.optInt(1) != 1,
                    "Swift WEP Wi-Fi backups are not supported");
        }
        SystemWifiBackup.configuration(values);
        return values;
    }

    private static String swiftWifiString(String value, boolean ssid) {
        if (value.isEmpty() || (!ssid && value.equals("*"))
                || (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\""))) {
            return value;
        }
        if (value.length() % 2 == 0 && (ssid || value.length() == 64)
                && value.matches("[0-9a-fA-F]+")) {
            return value;
        }
        return "\"" + value + "\"";
    }

    private static String unescape(String text) throws IOException {
        Matcher matcher = Pattern.compile("&(#x[0-9a-fA-F]+|#[0-9]+|quot|apos|amp|lt|gt);").matcher(text);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String entity = matcher.group(1);
            String replacement;
            switch (entity) {
                case "quot": replacement = "\""; break;
                case "apos": replacement = "'"; break;
                case "amp": replacement = "&"; break;
                case "lt": replacement = "<"; break;
                case "gt": replacement = ">"; break;
                default:
                    try {
                        int code = Integer.parseInt(entity.substring(entity.startsWith("#x") ? 2 : 1),
                                entity.startsWith("#x") ? 16 : 10);
                        replacement = new String(Character.toChars(code));
                    } catch (IllegalArgumentException e) { throw new IOException("Invalid Wi-Fi XML entity", e); }
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static JSONObject map(JSONObject source, String[] mapping, String[] columns)
            throws IOException, JSONException {
        JSONObject values = SystemDataSchema.fields(source, columns);
        for (String pair : mapping) {
            String[] names = pair.split(":");
            if (source.has(names[0])) values.put(names[1], source.get(names[0]));
        }
        return SystemDataSchema.fields(values, columns);
    }

    private static JSONObject object(JsonReader reader) throws IOException, JSONException {
        Object value = value(reader, 0, new int[]{0});
        require(value instanceof JSONObject, "Expected Swift record object");
        return (JSONObject) value;
    }

    private static Object value(JsonReader reader, int depth, int[] budget) throws IOException, JSONException {
        require(depth <= 12 && ++budget[0] <= SystemDataArchive.MAX_ROW, "Swift record is too large or deeply nested");
        switch (reader.peek()) {
            case BEGIN_OBJECT:
                JSONObject object = new JSONObject();
                reader.beginObject();
                while (reader.hasNext()) {
                    String name = reader.nextName();
                    require(name.length() <= 256 && !object.has(name), "Invalid or duplicate Swift field");
                    object.put(name, value(reader, depth + 1, budget));
                }
                reader.endObject();
                return object;
            case BEGIN_ARRAY:
                JSONArray array = new JSONArray();
                reader.beginArray();
                while (reader.hasNext()) {
                    require(array.length() < 1000, "Swift record array is too large");
                    array.put(value(reader, depth + 1, budget));
                }
                reader.endArray();
                return array;
            case STRING:
            case NUMBER:
                String text = reader.nextString();
                budget[0] += text.length();
                require(budget[0] <= SystemDataArchive.MAX_ROW, "Swift record is too large");
                return text;
            case BOOLEAN: return reader.nextBoolean();
            case NULL: reader.nextNull(); return JSONObject.NULL;
            default: throw new IOException("Invalid Swift JSON value");
        }
    }

    private static File destination(File output, String name, String root, boolean directory) throws IOException {
        name = SwiftBackupTar.safePath(name);
        require(name.equals(root) || name.startsWith(root + "/"), "Unexpected Swift system backup root");
        String relative = name.equals(root) ? "" : name.substring(root.length() + 1);
        boolean attachment = !directory && relative.startsWith("mms_data/");
        if (attachment) requireAttachmentName(relative.substring("mms_data/".length()));
        require(directory ? relative.isEmpty() || relative.equals("mms_data")
                : relative.equals("call_logs") || relative.equals("conversations") || attachment,
                "Unexpected Swift system backup file");
        File destination = new File(output, name);
        File parent = directory ? destination : destination.getParentFile();
        require(parent.isDirectory() || parent.mkdirs(), "Cannot stage Swift system backup");
        return destination;
    }

    private static void requireAttachmentName(String name) throws IOException {
        require(name.equals(SwiftBackupTar.safePath(name)) && name.indexOf('/') < 0
                && name.getBytes(StandardCharsets.UTF_8).length <= 255,
                "Invalid Swift MMS attachment name");
    }

    private static void extractTar(File tar, File output, String root) throws IOException {
        long total = 0;
        Set<String> names = new HashSet<>();
        try (TarArchiveInputStream in = new TarArchiveInputStream(new BufferedInputStream(new FileInputStream(tar)))) {
            TarArchiveEntry entry;
            while ((entry = in.getNextTarEntry()) != null) {
                require(names.size() < 100_000 && names.add(SwiftBackupTar.safePath(entry.getName()))
                        && entry.isCheckSumOK() && !entry.isSparse() && !entry.isLink() && !entry.isSymbolicLink()
                        && (entry.isDirectory() || entry.isFile()) && entry.getSize() >= 0
                        && entry.getSize() <= SystemDataArchive.MAX_BLOB
                        && entry.getSize() <= SystemDataArchive.MAX_BYTES - total, "Invalid Swift tar entry");
                total += entry.getSize();
                File target = destination(output, entry.getName(), root, entry.isDirectory());
                if (entry.isDirectory()) { require(entry.getSize() == 0, "Invalid Swift directory"); continue; }
                try (FileOutputStream out = new FileOutputStream(target)) {
                    SwiftBackupTar.copy(in, out, entry.getSize());
                }
            }
        }
    }

    private static void extractZip(File source, File output, String root) throws IOException {
        long total = 0;
        Set<String> names = new HashSet<>();
        try (ZipInputStream in = new ZipInputStream(new FileInputStream(source))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                require(names.size() < 100_000 && names.add(SwiftBackupTar.safePath(entry.getName())),
                        "Duplicate Swift ZIP entry");
                File target = destination(output, entry.getName(), root, entry.isDirectory());
                if (entry.isDirectory()) { require(in.read() == -1, "Invalid Swift directory"); continue; }
                try (FileOutputStream out = new FileOutputStream(target)) {
                    SystemDataArchive.hash(in, out,
                            Math.min(SystemDataArchive.MAX_BLOB, SystemDataArchive.MAX_BYTES - total), CHECK);
                }
                total += target.length();
                in.closeEntry();
            }
        }
    }
}
