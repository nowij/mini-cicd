package com.cicd.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "cicd_settings")
@Getter
@Setter
public class CicdSettings {

    @Id
    private Long id = 1L;

    // 로그 보관 기간 (일)
    private int logRetentionDays = 7;

    // 정리 배치 실행 시각 (0~23시)
    private int cleanupHour = 2;

    // 정리 배치 실행 분 (0~59분)
    private int cleanupMinute = 0;

    public String toCronExpression() {
        return String.format("0 %d %d * * *", cleanupMinute, cleanupHour);
    }
}
