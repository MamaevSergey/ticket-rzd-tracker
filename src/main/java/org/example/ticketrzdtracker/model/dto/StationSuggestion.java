package org.example.ticketrzdtracker.model.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class StationSuggestion {

    @JsonProperty("name")
    @JsonAlias({"n", "stationName", "cityName"})
    private String name;

    @JsonProperty("expressCode")
    @JsonAlias({"c", "code", "stationCode"})
    private String code;

    @JsonProperty("region")
    @JsonAlias({"regionName", "r", "state"})
    private String region;

    public String getDisplayName() {
        if (name == null) return "Неизвестная станция";
        String cleanName = name.trim();
        if (region != null && !region.isBlank() && !cleanName.toLowerCase().contains(region.toLowerCase())) {
            return cleanName + " (" + region.trim() + ")";
        }
        return cleanName;
    }
}