package com.vita.chat;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum ChatMessageFeedback {
	LIKE, UNLIKE;
	
	@JsonValue
    public String toValue() {
        return name().toLowerCase();
    }

    @JsonCreator
    public static ChatMessageFeedback from(String value) {
        return ChatMessageFeedback.valueOf(value.toUpperCase());
    }
	
	
}
