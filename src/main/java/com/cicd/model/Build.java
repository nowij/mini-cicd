package com.cicd.model;

import com.cicd.model.enums.BuildStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "builds")
@Getter
@Setter
@NoArgsConstructor
public class Build {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BuildStatus status = BuildStatus.PENDING;

    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;

    // 실제 빌드에 사용된 브랜치
    private String branch;

    // 로그 파일 경로
    private String logFilePath;

    // 실패 시 간단한 오류 메시지
    @Column(length = 1000)
    private String errorMessage;

    public long getDurationSeconds() {
        if (startedAt == null || finishedAt == null) return 0;
        return java.time.Duration.between(startedAt, finishedAt).getSeconds();
    }
}
