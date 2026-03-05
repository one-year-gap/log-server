package com.holliverse.logserver.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;
import lombok.Data;

@Data
public class LogEvent {

    @JsonProperty("event_id")
    private Long eventId;

    private String timestamp;
    private String event;

    @JsonProperty("event_name")
    private String eventName;

    @JsonProperty("member_id")
    private Long memberId;

    @JsonProperty("event_properties")
    private Map<String, Object> eventProperties;
}
