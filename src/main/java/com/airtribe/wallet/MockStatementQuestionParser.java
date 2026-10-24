package com.airtribe.wallet;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("mock")
public class MockStatementQuestionParser implements StatementQuestionParser {
    @Override
    public String toJson(String question) {
        return "{\"type\":4,\"from\":\"2026-01-01\",\"to\":\"2026-12-31\"}";
    }
}