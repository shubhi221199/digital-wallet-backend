package com.airtribe.wallet;

public record ApiError(String code, String message, String request_id) {
}