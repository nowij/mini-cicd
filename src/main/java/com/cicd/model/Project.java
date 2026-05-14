package com.cicd.model;

import com.cicd.model.enums.DeployOs;
import com.cicd.model.enums.ProjectType;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "projects")
@Getter
@Setter
@NoArgsConstructor
public class Project {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank
    @Column(nullable = false)
    private String name;

    private String description;

    // === Git 설정 ===
    @NotBlank
    @Column(nullable = false)
    private String repoUrl;

    private String branch = "main";

    // Private repo 접근용 GitHub Personal Access Token
    private String githubToken;

    // === 빌드 설정 ===
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ProjectType projectType = ProjectType.MAVEN;

    // projectType 기본값 대신 사용할 커스텀 빌드 명령어
    private String buildCommand;

    // 모노레포일 경우 빌드 대상 서브디렉토리 (비워두면 루트)
    private String buildSubDir;

    // === 배포 서버 설정 ===
    private String deployHost;

    private Integer deployPort = 22;

    private String deployUser;

    private String deployPassword;

    // SSH 키 파일 경로 (password 대신 사용)
    private String deployKeyPath;

    @Enumerated(EnumType.STRING)
    private DeployOs deployOs = DeployOs.LINUX;

    // 원격 서버의 배포 대상 디렉토리
    private String deployTargetDir;

    // 프로세스 식별용 앱 이름
    private String appName;

    // 앱 시작 명령어
    // Linux 예: nohup java -jar /opt/myapp/app.jar > /opt/myapp/app.log 2>&1 &
    // Windows 예: start /B java -jar C:\Apps\myapp\app.jar
    private String startCommand;

    // 앱 종료 명령어 (배포 전 실행, 실패 무시)
    // Linux 예: pkill -f app.jar
    // Windows 예: taskkill /F /FI "WINDOWTITLE eq myapp" /T
    private String stopCommand;

    // 업로드된 실행 스크립트 파일명 (.sh 또는 .bat)
    // 배포 시 deployTargetDir에 자동으로 전송됨
    private String scriptFileName;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "project", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @OrderBy("startedAt DESC")
    private List<Build> builds = new ArrayList<>();
}
