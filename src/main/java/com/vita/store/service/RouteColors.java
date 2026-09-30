package com.vita.store.service;

import java.util.LinkedHashMap;
import java.util.Map;

/***
 * 카카오는 노선명·차량 종류만 주고 색은 주지 않아서 서버에서 매핑한다.
 * 지하철은 노선별, 버스는 노선 번호가 아니라 종류(간선·지선·일반 ...)별 색이다.
 */
final class RouteColors {

    static final String WALK = "#8E8E8E";
    static final String BICYCLE = "#22A06B";
    static final String CAR = "#3D7BF7";
    private static final String TRANSIT_DEFAULT = "#5B6770";

    private static final Map<String, String> SUBWAY = new LinkedHashMap<>();
    private static final Map<String, String> BUS = new LinkedHashMap<>();

    static{
        SUBWAY.put("인천1호선", "#7CA8D5");
        SUBWAY.put("인천2호선", "#ED8B00");
        SUBWAY.put("신분당선", "#D4003B");
        SUBWAY.put("수인분당선", "#F5A200");
        SUBWAY.put("분당선", "#F5A200");
        SUBWAY.put("경의중앙선", "#77C4A3");
        SUBWAY.put("공항철도", "#0090D2");
        SUBWAY.put("경춘선", "#0C8E72");
        SUBWAY.put("서해선", "#81A914");
        SUBWAY.put("경강선", "#0054A6");
        SUBWAY.put("우이신설", "#B0CE18");
        SUBWAY.put("신림", "#6789CA");
        SUBWAY.put("김포", "#A17800");
        SUBWAY.put("의정부", "#FDA600");
        SUBWAY.put("에버라인", "#56AD2D");
        SUBWAY.put("용인", "#56AD2D");
        SUBWAY.put("GTX-A", "#9A6292");
        SUBWAY.put("1호선", "#0052A4");
        SUBWAY.put("2호선", "#00A84D");
        SUBWAY.put("3호선", "#EF7C1C");
        SUBWAY.put("4호선", "#00A5DE");
        SUBWAY.put("5호선", "#996CAC");
        SUBWAY.put("6호선", "#CD7C2F");
        SUBWAY.put("7호선", "#747F00");
        SUBWAY.put("8호선", "#E6186C");
        SUBWAY.put("9호선", "#BDB092");

        BUS.put("간선", "#3D5BAB");
        BUS.put("지선", "#5BB025");
        BUS.put("일반", "#0C8E72");
        BUS.put("좌석", "#8B5CF6");
        BUS.put("직행", "#E60012");
        BUS.put("광역", "#E60012");
        BUS.put("급행", "#D4003B");
        BUS.put("순환", "#F2B70A");
        BUS.put("마을", "#33A66F");
        BUS.put("공항", "#0090D2");
    }

    private RouteColors(){}

    static String transit(String type, String lineName, String vehicleType){
        return switch(type){
            case "SUBWAY" -> find(SUBWAY, lineName);
            case "BUS" -> find(BUS, vehicleType);
            case "WALKING" -> WALK;
            default -> TRANSIT_DEFAULT;
        };
    }

    private static String find(Map<String, String> table, String key){
        if(key == null){
            return TRANSIT_DEFAULT;
        }
        for(Map.Entry<String, String> entry : table.entrySet()){
            if(key.contains(entry.getKey())){
                return entry.getValue();
            }
        }
        return TRANSIT_DEFAULT;
    }
}