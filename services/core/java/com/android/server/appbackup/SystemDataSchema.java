/*
 * Copyright (C) 2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.server.appbackup;

import static android.app.appbackup.AppDataBackupRestoreManager.DATA_TYPE_CALL_LOGS;
import static android.app.appbackup.AppDataBackupRestoreManager.DATA_TYPE_MESSAGES;
import static android.app.appbackup.AppDataBackupRestoreManager.DATA_TYPE_WIFI;
import static com.android.server.appbackup.SwiftBackupArchive.require;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Set;

final class SystemDataSchema {
    static final String[] CALLS = {"number", "presentation", "date", "duration", "type",
            "features", "countryiso", "new", "is_read", "data_usage", "geocoded_location",
            "block_reason", "missed_reason"};
    static final String[] SMS = {"address", "body", "date", "date_sent", "type", "read", "seen",
            "status", "subject", "protocol", "reply_path_present", "service_center", "locked",
            "error_code"};
    static final String[] MMS = {"date", "date_sent", "msg_box", "read", "seen", "sub", "sub_cs",
            "ct_t", "ct_l", "exp", "m_cls", "m_type", "v", "m_size", "pri", "rr", "rpt_a",
            "resp_st", "st", "tr_id", "retr_st", "retr_txt", "retr_txt_cs", "read_status",
            "ct_cls", "resp_txt", "d_tm", "d_rpt", "locked", "text_only", "m_id"};
    static final String[] PART = {"seq", "ct", "name", "chset", "fn", "cd", "cid", "cl",
            "ctt_s", "ctt_t", "text"};
    static final String[] ADDRESS = {"address", "type", "charset"};
    private static final Set<String> LONG_FIELDS = Set.of("date", "date_sent", "duration",
            "data_usage", "missed_reason", "exp", "m_size", "d_tm");
    private static final Set<String> INTEGER_FIELDS = Set.of("presentation", "type", "features",
            "new", "is_read", "block_reason", "read", "seen", "status", "protocol",
            "reply_path_present", "locked", "error_code", "msg_box", "sub_cs", "m_type",
            "v", "pri", "rr", "rpt_a", "resp_st", "st", "retr_st", "retr_txt_cs",
            "read_status", "ct_cls", "d_rpt", "text_only", "seq", "chset", "charset");

    static boolean inlinePart(String contentType) {
        return contentType.equals("text/plain") || contentType.equals("application/smil");
    }

    static void requireType(int type) {
        if (type < DATA_TYPE_CALL_LOGS || type > DATA_TYPE_WIFI) {
            throw new IllegalArgumentException("Unsupported system backup category");
        }
    }

    static String label(int type) {
        switch (type) {
            case DATA_TYPE_CALL_LOGS: return "Call logs";
            case DATA_TYPE_MESSAGES: return "Messages";
            case DATA_TYPE_WIFI: return "Wi-Fi";
            default: throw new IllegalArgumentException("Unsupported system backup category");
        }
    }

    static String identity(int type) {
        requireType(type);
        return "android.appbackup.system" + type;
    }

    static JSONObject fields(JSONObject source, String[] names) throws JSONException, IOException {
        JSONObject result = new JSONObject();
        for (String name : names) {
            if (!source.has(name)) continue;
            Object value = source.get(name);
            require(value == JSONObject.NULL || value instanceof String || value instanceof Number,
                    "Invalid provider field");
            require(String.valueOf(value).length() <= (1 << 20), "Provider field is too large");
            if (value != JSONObject.NULL
                    && (LONG_FIELDS.contains(name) || INTEGER_FIELDS.contains(name))) {
                try {
                    long number = Long.parseLong(value.toString());
                    require(!INTEGER_FIELDS.contains(name)
                            || number >= Integer.MIN_VALUE && number <= Integer.MAX_VALUE,
                            "Provider integer is out of range: " + name);
                } catch (NumberFormatException e) {
                    throw new IOException("Invalid provider integer: " + name, e);
                }
            }
            result.put(name, value);
        }
        return result;
    }

    static void validate(JSONObject row, int type, SystemDataArchive archive,
            SystemDataArchive.Check check) throws IOException, JSONException {
        String kind = row.getString("kind");
        long slot = row.has("simSlot") ? row.getLong("simSlot") : -1;
        require(slot >= -1 && slot <= 15,
                "Invalid SIM slot");
        if (type == DATA_TYPE_WIFI) {
            if (kind.equals("wifi-platform")) {
                SystemWifiBackup.platformData(row, archive, check);
            } else {
                require(kind.equals("wifi"), "Wrong system backup category");
                SystemWifiBackup.configuration(row.getJSONObject("values"));
            }
            return;
        }
        boolean calls = type == DATA_TYPE_CALL_LOGS;
        require(calls ? kind.equals("call") : kind.equals("sms") || kind.equals("mms"),
                "Wrong system backup category");
        JSONObject values = row.getJSONObject("values");
        fields(values, calls ? CALLS : kind.equals("sms") ? SMS : MMS);
        require(values.getLong("date") >= 0, "Invalid record date");
        if (calls) {
            int callType = values.getInt("type");
            require(callType >= 1 && callType <= 7 && callType != 4
                    && values.getLong("duration") >= 0, "Unsupported call log record");
        } else if (kind.equals("sms")) {
            require(values.getInt("type") >= 1 && values.getInt("type") <= 6,
                    "Invalid SMS type");
        } else {
            require(values.getInt("msg_box") >= 1 && values.getInt("msg_box") <= 4,
                    "Invalid MMS box");
            require(values.getInt("m_type") == 128 || values.getInt("m_type") == 132,
                    "MMS delivery reports and pending downloads are not supported");
            JSONArray recipients = row.getJSONArray("recipients");
            require(recipients.length() > 0 && recipients.length() <= 1000,
                    "MMS recipients are missing or invalid");
            for (int i = 0; i < recipients.length(); i++) {
                require(!recipients.getString(i).isEmpty() && recipients.getString(i).length() <= 1024,
                        "Invalid MMS recipient");
            }
            JSONArray addresses = row.getJSONArray("addresses");
            require(addresses.length() > 0 && addresses.length() <= 1000, "Invalid MMS addresses");
            for (int i = 0; i < addresses.length(); i++) {
                JSONObject address = fields(addresses.getJSONObject(i), ADDRESS);
                int addressType = address.getInt("type");
                require(addressType == 129 || addressType == 130
                        || addressType == 137 || addressType == 151, "Invalid MMS address type");
                require(!address.isNull("address") && !address.getString("address").isEmpty()
                        && address.getString("address").length() <= 1024, "Missing or invalid MMS address");
            }
            JSONArray parts = row.getJSONArray("parts");
            require(parts.length() <= 1000, "Too many MMS parts");
            for (int i = 0; i < parts.length(); i++) {
                JSONObject part = parts.getJSONObject(i);
                JSONObject data = fields(part.getJSONObject("values"), PART);
                String contentType = data.getString("ct");
                require(!contentType.isEmpty(), "Missing MMS content type");
                require(inlinePart(contentType) != part.has("file"),
                        "Missing MMS attachment or invalid inline part");
                if (part.has("file")) archive.validateBlob(part, check);
            }
        }
    }
}
