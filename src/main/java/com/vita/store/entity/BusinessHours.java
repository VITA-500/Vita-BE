package com.vita.store.entity;

import com.vita.common.exception.BusinessException;
import com.vita.common.exception.ErrorCode;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;

/**
 * 영업시간
 * API에서는 글자로 주고받고 Db에는 두 시각으로 저장
 * 닫는 시각이 여는 시각보다 이르면 자정을 넘겨 다음 날까지 영업
 */
public record BusinessHours(LocalTime open, LocalTime close) {

    /** "HH:mm-HH:mm". 요청 DTO의 @Pattern에서도 같은 형식 사용 */
    public static final String FORMAT = "^([01][0-9]|2[0-3]):[0-5][0-9]-" +
            "([01][0-9]|2[0-3]):[0-5][0-9]$";

    /** 마지막 예약은 닫기 30분 전까지 */
    private static final int LAST_RESERVATION_BEFORE_CLOSE_MINUTES = 30;
    private static final int MINUTES_PER_DAY = 24 * 60;
    private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");
    private static final Pattern TIME_PATTERN = Pattern.compile("^([01][0-9]|2[0-3]):[0-5][0-9]$");

    private static final Pattern PATTERN = Pattern.compile(FORMAT);
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    /** 비어 있으면 null. 형식이 틀리거나 여닫는 시간이 같으면 400 */
    public static BusinessHours parse(String text){
        if(text == null || text.isBlank()){
            return null;
        }
        String value = text.trim();
        if(!PATTERN.matcher(value).matches()){
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "영업시간은 HH:mm-HH:mm 형식이어야 합니다. (예: 10:00-20:00)");
        }
        String[] parts = value.split("-");
        LocalTime open = LocalTime.parse(parts[0], HH_MM);
        LocalTime close = LocalTime.parse(parts[1], HH_MM);
        if(open.equals(close)){
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "영업시간의 여는 시각과 닫는 시각이 같을 수 없습니다.");
        }
        return new BusinessHours(open, close);
    }

    /** 여는 시각 ~ 닫기 30분 전 사이에만 예약 가능 */
    public boolean isReservableAt(LocalTime time){
        return minutesFromOpen(time) <= openMinutes() - LAST_RESERVATION_BEFORE_CLOSE_MINUTES;
    }

    /** time에 영업 중인지 확인 */
    public boolean isOpenAt(LocalTime time){
        return minutesFromOpen(time) < openMinutes();
    }

    /** 응답용 영업 중 여부, 영업시간이 없으면 null */
    public static Boolean openAt(String businessHours, LocalTime time){
        BusinessHours hours = parse(businessHours);
        return hours == null ? null : hours.isOpenAt(time);
    }

    /** 한국 시간 기준 지금 시각 */
    public static LocalTime nowInKorea(){
        return LocalTime.now(KOREA);
    }

    /**
     * 조회 조건의 영업 시각
     * openNow=true면 지금, openAt("HH:mm")이면 그 시각
     */
    public static LocalTime filterTime(Boolean openNow, String openAt){
        boolean hasOpenAt = openAt != null && !openAt.isBlank();
        if(Boolean.TRUE.equals(openNow) && hasOpenAt){
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "openNow와 openAt은 함께 사용할 수 없습니다.");
        }
        if(Boolean.TRUE.equals(openNow)){
            return nowInKorea();
        }
        if(!hasOpenAt){
            return null;
        }
        String value = openAt.trim();
        if(!TIME_PATTERN.matcher(value).matches()){
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "openAt은 HH:mm 형식이어야 합니다. (예: 18:00)");
        }
        return LocalTime.parse(value, HH_MM);
    }

    public LocalTime lastReservationTime(){
        return close.minusMinutes(LAST_RESERVATION_BEFORE_CLOSE_MINUTES);
    }

    // 여는 시각부터 time까지 몇 분 지났는지
    private int minutesFromOpen(LocalTime time){
        return Math.floorMod(toMinutes(time) - toMinutes(open), MINUTES_PER_DAY);
    }

    // 하루 영업 시간
    private int openMinutes(){
        return Math.floorMod(toMinutes(close) - toMinutes(open), MINUTES_PER_DAY);
    }

    private static int toMinutes(LocalTime time){
        return time.getHour() * 60 + time.getMinute();
    }

    /** 두 시각 중 하나라도 없으면 null */
    public static String format(LocalTime open, LocalTime close){
        if(open == null || close == null){
            return null;
        }
        return open.format(HH_MM) + "-" + close.format(HH_MM);
    }
}
