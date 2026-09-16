package com.vita.chat;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum ChatMessageRole {
	USER,
	ASSISTANT;
	
	@JsonValue
    public String toValue() {
        return name().toLowerCase();
    }

    @JsonCreator
    public static ChatMessageRole from(String value) {
        return ChatMessageRole.valueOf(value.toUpperCase());
    }
}
