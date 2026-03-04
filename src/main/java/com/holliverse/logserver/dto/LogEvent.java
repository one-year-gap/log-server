package com.holliverse.logserver.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;
import lombok.Data;

@Data
public class LogEvent {

    @JsonProperty("event_id")
    private String eventId;

    private String timestamp;
    private String event;

    @JsonProperty("event_name")
    private String eventName;

    @JsonProperty("member_properties")
    private MemberProperties memberProperties;

    @JsonProperty("event_properties")
    private Map<String, Object> eventProperties;

    @Data
    public static class MemberProperties {
        @JsonProperty("member_id")
        private String memberId;
    }
}
