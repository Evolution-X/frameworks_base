/*
 * Copyright (C) 2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.server.appbackup;

import static android.app.appbackup.AppDataBackupRestoreManager.DATA_TYPE_CALL_LOGS;
import static com.android.server.appbackup.SwiftBackupArchive.require;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CallLog;
import android.provider.Telephony;
import android.telephony.SubscriptionManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

final class SystemProviderBackup {
    private static final String TELEPHONY_ACCOUNT =
            "com.android.phone/com.android.services.telephony.TelephonyConnectionService";
    private final Context mContext;
    private final ContentResolver mResolver;
    private final SystemDataArchive.Check mCheck;

    SystemProviderBackup(Context context, SystemDataArchive.Check check) {
        mContext = context;
        mResolver = context.getContentResolver();
        mCheck = check;
    }

    void backup(int type, SystemDataArchive.Writer writer) throws IOException, JSONException {
        if (type == DATA_TYPE_CALL_LOGS) {
            backupRows(CallLog.Calls.CONTENT_URI, "type != 4", "call", SystemDataSchema.CALLS, writer);
        } else {
            backupRows(Telephony.Sms.CONTENT_URI, null, "sms", SystemDataSchema.SMS, writer);
            try (Cursor cursor = query(Telephony.Mms.CONTENT_URI, null,
                    "m_type IN (128,132)", null, "_id ASC")) {
                while (cursor.moveToNext()) {
                    mCheck.run();
                    writer.row(mms(cursor, writer));
                }
            }
        }
    }

    private void backupRows(Uri uri, String selection, String kind, String[] columns,
            SystemDataArchive.Writer writer) throws IOException, JSONException {
        try (Cursor cursor = query(uri, null, selection, null, "_id ASC")) {
            while (cursor.moveToNext()) {
                mCheck.run();
                JSONObject row = new JSONObject().put("kind", kind).put("values", values(cursor, columns));
                sourceSim(row, cursor, kind.equals("call"));
                writer.row(row);
            }
        }
    }

    private JSONObject mms(Cursor cursor, SystemDataArchive.Writer writer)
            throws IOException, JSONException {
        long id = cursor.getLong(cursor.getColumnIndexOrThrow("_id"));
        JSONObject row = new JSONObject().put("kind", "mms")
                .put("values", values(cursor, SystemDataSchema.MMS));
        sourceSim(row, cursor, false);
        JSONArray addresses = new JSONArray();
        try (Cursor address = query(Uri.parse("content://mms/" + id + "/addr"),
                null, null, null, "_id ASC")) {
            while (address.moveToNext()) {
                require(addresses.length() < 1000, "Too many MMS addresses");
                addresses.put(values(address, SystemDataSchema.ADDRESS));
            }
        }
        row.put("addresses", addresses);
        if (writer != null) row.put("recipients",
                recipients(cursor.getLong(cursor.getColumnIndexOrThrow("thread_id"))));
        JSONArray parts = new JSONArray();
        try (Cursor part = query(Uri.parse("content://mms/" + id + "/part"),
                null, null, null, "seq ASC, _id ASC")) {
            while (part.moveToNext()) {
                mCheck.run();
                require(parts.length() < 1000, "Too many MMS parts");
                JSONObject data = values(part, SystemDataSchema.PART);
                JSONObject item = new JSONObject().put("values", data);
                String ct = data.getString("ct");
                if (!SystemDataSchema.inlinePart(ct)) {
                    Uri uri = ContentUris.withAppendedId(Telephony.Mms.Part.CONTENT_URI,
                            part.getLong(part.getColumnIndexOrThrow("_id")));
                    try (InputStream in = mResolver.openInputStream(uri)) {
                        require(in != null, "Cannot read an MMS attachment");
                        JSONObject blob = writer == null
                                ? new JSONObject().put("sha256", SystemDataArchive.hash(in, null,
                                        SystemDataArchive.MAX_BLOB, mCheck))
                                : writer.blob(in);
                        if (blob.has("file")) item.put("file", blob.getString("file"));
                        item.put("sha256", blob.getString("sha256"));
                    }
                }
                parts.put(item);
            }
        }
        return row.put("parts", parts);
    }

    private JSONArray recipients(long threadId)
            throws IOException, JSONException {
        Set<String> recipients = new HashSet<>();
        Uri threads = Telephony.Threads.CONTENT_URI.buildUpon().appendQueryParameter("simple", "true").build();
        try (Cursor thread = query(threads, new String[]{"recipient_ids"}, "_id=?",
                new String[]{Long.toString(threadId)}, null)) {
            if (thread.moveToFirst() && !thread.isNull(0)) {
                for (String id : thread.getString(0).split(" ")) {
                    require(id.matches("[0-9]+"), "Invalid message thread recipient");
                    try (Cursor address = query(Uri.parse("content://mms-sms/canonical-address/" + id),
                            null, null, null, null)) {
                        require(address.moveToFirst() && !address.isNull(0)
                                && !address.getString(0).isEmpty(), "Cannot determine all MMS recipients");
                        recipients.add(address.getString(0));
                    }
                }
            }
        }
        require(!recipients.isEmpty(), "Cannot determine MMS recipients");
        return new JSONArray(recipients);
    }

    boolean restore(JSONObject row, SystemDataArchive archive) throws IOException, JSONException {
        mCheck.run();
        String kind = row.getString("kind");
        JSONObject source = row.getJSONObject("values");
        if (kind.equals("mms")) return restoreMms(row, archive);
        boolean calls = kind.equals("call");
        ContentValues values = contentValues(source, calls ? SystemDataSchema.CALLS : SystemDataSchema.SMS);
        restoreSim(row, values, calls);
        if (!calls) {
            int type = values.getAsInteger("type");
            if (type == 4 || type == 6) values.put("type", 5);
        }
        Uri uri = calls ? CallLog.Calls.CONTENT_URI : Telephony.Sms.CONTENT_URI;
        String[] keys = calls ? new String[]{"date", "number", "duration", "type"}
                : new String[]{"date", "address", "body", "type"};
        String account = calls ? "subscription_id" : "sub_id";
        if (values.containsKey(account)) {
            keys = Arrays.copyOf(keys, keys.length + 1);
            keys[keys.length - 1] = account;
        }
        StringBuilder selection = new StringBuilder();
        String[] args = new String[keys.length];
        for (int i = 0; i < keys.length; i++) {
            if (i > 0) selection.append(" AND ");
            selection.append(keys[i]).append(" IS ?");
            args[i] = values.getAsString(keys[i]);
        }
        try (Cursor cursor = query(uri, new String[]{"_id"}, selection.toString(), args, null)) {
            if (cursor.moveToFirst()) return false;
        }
        mCheck.run();
        require(mResolver.insert(uri, values) != null, "Provider refused a restored record");
        return true;
    }

    private boolean restoreMms(JSONObject row, SystemDataArchive archive)
            throws IOException, JSONException {
        JSONObject source = row.getJSONObject("values");
        ContentValues values = contentValues(source, SystemDataSchema.MMS);
        restoreSim(row, values, false);
        if (values.getAsInteger("msg_box") == 4) values.put("msg_box", 3);
        String target = fingerprint(row);
        String selection = "date=? AND msg_box=?";
        String[] args = {values.getAsString("date"), values.getAsString("msg_box")};
        if (values.containsKey("sub_id")) {
            selection += " AND sub_id=?";
            args = new String[]{args[0], args[1], values.getAsString("sub_id")};
        }
        try (Cursor existing = query(Telephony.Mms.CONTENT_URI, null, selection, args, "_id ASC")) {
            while (existing.moveToNext()) {
                mCheck.run();
                JSONObject candidate = mms(existing, null);
                String sourceId = string(source, "m_id");
                String candidateId = string(candidate.getJSONObject("values"), "m_id");
                if ((sourceId.isEmpty() || candidateId.isEmpty() || sourceId.equals(candidateId))
                        && target.equals(fingerprint(candidate))) return false;
            }
        }
        Set<String> recipients = new HashSet<>();
        JSONArray recipientsJson = row.getJSONArray("recipients");
        for (int i = 0; i < recipientsJson.length(); i++) recipients.add(recipientsJson.getString(i));
        mCheck.run();
        values.put("thread_id", Telephony.Threads.getOrCreateThreadId(mContext, recipients));
        values.put("notify", false);
        Uri inserted = mResolver.insert(Telephony.Mms.CONTENT_URI, values);
        require(inserted != null, "Provider refused an MMS record");
        boolean complete = false;
        try {
            long id = ContentUris.parseId(inserted);
            JSONArray addresses = row.getJSONArray("addresses");
            for (int i = 0; i < addresses.length(); i++) {
                mCheck.run();
                require(mResolver.insert(Uri.parse("content://mms/" + id + "/addr"),
                        contentValues(addresses.getJSONObject(i), SystemDataSchema.ADDRESS)) != null,
                        "Provider refused an MMS address");
            }
            JSONArray parts = row.getJSONArray("parts");
            for (int i = 0; i < parts.length(); i++) {
                mCheck.run();
                JSONObject part = parts.getJSONObject(i);
                Uri partUri = mResolver.insert(Uri.parse("content://mms/" + id + "/part"),
                        contentValues(part.getJSONObject("values"), SystemDataSchema.PART));
                require(partUri != null, "Provider refused an MMS part");
                if (part.has("file")) {
                    try (InputStream in = archive.openBlob(part.getString("file"));
                            OutputStream out = mResolver.openOutputStream(partUri, "w")) {
                        require(out != null, "Cannot write an MMS attachment");
                        SystemDataArchive.hash(in, out, SystemDataArchive.MAX_BLOB, mCheck);
                    }
                }
            }
            mCheck.run();
            ContentValues completed = new ContentValues();
            completed.put("msg_box", values.getAsInteger("msg_box"));
            require(mResolver.update(inserted, completed, null, null) == 1,
                    "Provider could not finish the MMS restore");
            complete = true;
            return true;
        } finally {
            if (!complete) mResolver.delete(inserted, null, null);
        }
    }

    private static String fingerprint(JSONObject row) throws IOException, JSONException {
        JSONObject values = row.getJSONObject("values");
        JSONArray key = new JSONArray();
        for (String name : new String[]{"date", "sub", "m_type", "tr_id"}) {
            key.put(string(values, name));
        }
        int box = values.getInt("msg_box");
        key.put(box == 4 ? 3 : box);
        ArrayList<String> addresses = new ArrayList<>();
        JSONArray list = row.getJSONArray("addresses");
        for (int i = 0; i < list.length(); i++) {
            JSONObject address = list.getJSONObject(i);
            addresses.add(string(address, "type") + ":" + string(address, "address"));
        }
        Collections.sort(addresses);
        key.put(new JSONArray(addresses));
        JSONArray parts = row.getJSONArray("parts");
        ArrayList<String> content = new ArrayList<>();
        for (int i = 0; i < parts.length(); i++) {
            JSONObject part = parts.getJSONObject(i);
            JSONObject data = part.getJSONObject("values");
            JSONArray partKey = new JSONArray();
            for (String name : new String[]{"seq", "ct"}) partKey.put(string(data, name));
            partKey.put(SystemDataSchema.inlinePart(data.getString("ct")) ? string(data, "text") : "");
            partKey.put(string(part, "sha256"));
            content.add(partKey.toString());
        }
        Collections.sort(content);
        key.put(new JSONArray(content));
        return SystemDataArchive.hash(new ByteArrayInputStream(key.toString().getBytes(StandardCharsets.UTF_8)),
                null, SystemDataArchive.MAX_ROW, () -> {});
    }

    private static String string(JSONObject object, String name) {
        return object.isNull(name) ? "" : object.optString(name, "");
    }

    private static void sourceSim(JSONObject row, Cursor cursor, boolean call) throws JSONException {
        int index = cursor.getColumnIndex(call ? "subscription_id" : "sub_id");
        if (index < 0 || cursor.isNull(index)) return;
        if (call) {
            int component = cursor.getColumnIndex("subscription_component_name");
            if (component < 0 || !TELEPHONY_ACCOUNT.equals(cursor.getString(component))) return;
        }
        try {
            int subId = Integer.parseInt(cursor.getString(index));
            int slot = SubscriptionManager.getSlotIndex(subId);
            if (slot >= 0) row.put("simSlot", slot);
        } catch (NumberFormatException ignored) {}
    }

    private static void restoreSim(JSONObject row, ContentValues values, boolean call) {
        int slot = row.optInt("simSlot", -1);
        if (slot < 0) return;
        int subId = SubscriptionManager.getSubscriptionId(slot);
        if (!SubscriptionManager.isValidSubscriptionId(subId)) return;
        if (call) {
            values.put("subscription_component_name", TELEPHONY_ACCOUNT);
            values.put("subscription_id", Integer.toString(subId));
        } else {
            values.put("sub_id", subId);
        }
    }

    private static JSONObject values(Cursor cursor, String[] names) throws JSONException, IOException {
        JSONObject values = new JSONObject();
        for (String name : names) {
            int index = cursor.getColumnIndex(name);
            if (index < 0) continue;
            if (cursor.isNull(index)) values.put(name, JSONObject.NULL);
            else if (cursor.getType(index) == Cursor.FIELD_TYPE_INTEGER) values.put(name, cursor.getLong(index));
            else values.put(name, cursor.getString(index));
        }
        return SystemDataSchema.fields(values, names);
    }

    private static ContentValues contentValues(JSONObject source, String[] names)
            throws IOException, JSONException {
        JSONObject filtered = SystemDataSchema.fields(source, names);
        ContentValues result = new ContentValues();
        for (String name : names) {
            if (!filtered.has(name)) continue;
            Object value = filtered.get(name);
            if (value == JSONObject.NULL) result.putNull(name);
            else if (value instanceof Number) result.put(name, ((Number) value).longValue());
            else result.put(name, (String) value);
        }
        return result;
    }

    private Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort)
            throws IOException {
        mCheck.run();
        Cursor cursor = mResolver.query(uri, projection, selection, args, sort);
        require(cursor != null, "System data provider is unavailable");
        return cursor;
    }
}
