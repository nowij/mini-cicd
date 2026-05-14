package com.cicd.service;

import com.cicd.model.Build;
import com.cicd.model.Project;
import com.cicd.model.enums.BuildStatus;
import com.cicd.model.enums.ProjectType;
import com.cicd.repository.BuildRepository;
import com.cicd.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class BuildService {

    private final BuildRepository buildRepository;
    private final ProjectRepository projectRepository;
    private final DeployService deployService;
    private final LogService logService;

    @Value("${cicd.workspace:./workspace}")
    private String workspaceDir;

    // 빌드 ID → 현재 실행 중인 OS 프로세스
    private final ConcurrentHashMap<Long, Process> activeProcesses = new ConcurrentHashMap<>();
    // 빌드 ID → 중단 요청 여부
    private final ConcurrentHashMap<Long, Boolean> cancelFlags = new ConcurrentHashMap<>();

    /**
     * 빌드 레코드를 생성하고 비동기로 빌드를 실행합니다.
     * 이미 실행 중인 빌드가 있으면 예외를 던집니다.
     */
    @Transactional
    public Build createBuild(Long projectId, String branch) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("프로젝트를 찾을 수 없습니다: " + projectId));

        // 진행 중인 빌드 중복 방지
        buildRepository.findTopByProjectOrderByStartedAtDesc(project).ifPresent(last -> {
            if (last.getStatus() == BuildStatus.BUILDING || last.getStatus() == BuildStatus.DEPLOYING) {
                throw new IllegalStateException("이미 빌드가 진행 중입니다.");
            }
        });

        Build build = new Build();
        build.setProject(project);
        build.setStatus(BuildStatus.PENDING);
        build.setStartedAt(LocalDateTime.now());
        // 선택된 브랜치가 없으면 프로젝트 기본 브랜치 사용
        build.setBranch(branch != null && !branch.isBlank() ? branch
                : (project.getBranch() != null ? project.getBranch() : "main"));
        return buildRepository.save(build);
    }

    @Async("buildExecutor")
    public void runBuild(Long buildId) {
        Build build = buildRepository.findById(buildId).orElseThrow();
        // @Async는 별도 스레드에서 실행되므로 기존 세션이 닫혀 있음.
        // build.getProject()의 lazy proxy를 초기화하려면 새 세션에서 직접 조회해야 함.
        // Hibernate proxy는 getId()만 세션 없이 안전하게 호출 가능.
        Project project = projectRepository.findById(build.getProject().getId()).orElseThrow();

        logService.init(buildId);
        logService.append(buildId, "========================================");
        logService.append(buildId, "  빌드 시작: " + project.getName());
        logService.append(buildId, "  빌드 ID  : " + buildId);
        logService.append(buildId, "  프로젝트 : " + project.getProjectType().getDisplayName());
        logService.append(buildId, "  브랜치   : " + build.getBranch());
        logService.append(buildId, "========================================");

        Path workDir = Paths.get(workspaceDir, "build-" + buildId);

        try {
            Files.createDirectories(workDir);

            // 1. Git Clone
            build.setStatus(BuildStatus.BUILDING);
            buildRepository.save(build);

            logService.append(buildId, "\n[STEP 1/3] 저장소 클론 중...");
            cloneRepo(project, build.getBranch(), workDir, buildId);

            // 2. 빌드
            logService.append(buildId, "\n[STEP 2/3] 빌드 실행 중...");
            Path buildSubDir = resolveSubDir(project, workDir);
            Path artifactPath = buildProject(project, buildSubDir, buildId);

            // 3. 배포
            build.setStatus(BuildStatus.DEPLOYING);
            buildRepository.save(build);

            logService.append(buildId, "\n[STEP 3/3] 배포 중...");
            deployService.deploy(project, artifactPath, buildId);

            build.setStatus(BuildStatus.SUCCESS);
            logService.append(buildId, "\n========================================");
            logService.append(buildId, "  BUILD SUCCESS");
            logService.append(buildId, "========================================");

        } catch (Exception e) {
            if (cancelFlags.getOrDefault(buildId, false)) {
                log.info("Build {} cancelled", buildId);
                build.setStatus(BuildStatus.CANCELLED);
                logService.append(buildId, "\n========================================");
                logService.append(buildId, "  BUILD CANCELLED");
                logService.append(buildId, "========================================");
            } else {
                log.error("Build {} failed", buildId, e);
                build.setStatus(BuildStatus.FAILED);
                build.setErrorMessage(truncate(e.getMessage(), 1000));
                logService.append(buildId, "\n========================================");
                logService.append(buildId, "  BUILD FAILED: " + e.getMessage());
                logService.append(buildId, "========================================");
            }
        } finally {
            build.setFinishedAt(LocalDateTime.now());
            buildRepository.save(build);
            logService.complete(buildId);
            cleanupWorkDir(workDir, buildId);
            activeProcesses.remove(buildId);
            cancelFlags.remove(buildId);
        }
    }

    private void cloneRepo(Project project, String branch, Path workDir, Long buildId) throws Exception {
        String repoUrl = injectToken(project.getRepoUrl(), project.getGithubToken());

        List<String> cmd;
        if (isWindows()) {
            cmd = List.of("cmd", "/c", "git", "clone",
                    "--branch", branch, "--depth", "1",
                    repoUrl, ".");
        } else {
            cmd = List.of("git", "clone",
                    "--branch", branch, "--depth", "1",
                    repoUrl, ".");
        }
        executeCommand(cmd, workDir, buildId);
    }

    private Path buildProject(Project project, Path workDir, Long buildId) throws Exception {
        // gradlew 실행 권한 부여 (Linux)
        if (project.getProjectType() == ProjectType.GRADLE && !isWindows()) {
            Path gradlew = workDir.resolve("gradlew");
            if (Files.exists(gradlew)) {
                gradlew.toFile().setExecutable(true);
            }
        }

        List<String> cmd = resolveBuildCommand(project, workDir);
        logService.append(buildId, "빌드 명령어: " + String.join(" ", cmd));
        executeCommand(cmd, workDir, buildId);

        return findArtifact(project, workDir);
    }

    private List<String> resolveBuildCommand(Project project, Path workDir) {
        // 커스텀 명령어 우선
        if (project.getBuildCommand() != null && !project.getBuildCommand().isBlank()) {
            if (isWindows()) return List.of("cmd", "/c", project.getBuildCommand());
            return List.of("bash", "-c", project.getBuildCommand());
        }

        return switch (project.getProjectType()) {
            case MAVEN -> isWindows()
                    ? List.of("cmd", "/c", "mvn", "clean", "package", "-DskipTests")
                    : List.of("mvn", "clean", "package", "-DskipTests");
            case GRADLE -> {
                String gradlew = isWindows()
                        ? workDir.resolve("gradlew.bat").toString()
                        : "./gradlew";
                yield isWindows()
                        ? List.of("cmd", "/c", gradlew, "build", "-x", "test")
                        : List.of(gradlew, "build", "-x", "test");
            }
            case REACT -> isWindows()
                    ? List.of("cmd", "/c", "npm install && npm run build")
                    : List.of("bash", "-c", "npm install && npm run build");
            case CUSTOM -> throw new IllegalStateException("커스텀 프로젝트는 빌드 명령어를 직접 입력해야 합니다.");
        };
    }

    private Path findArtifact(Project project, Path workDir) throws Exception {
        return switch (project.getProjectType()) {
            case MAVEN -> findJar(workDir.resolve("target"));
            case GRADLE -> findJar(workDir.resolve("build/libs"));
            case REACT -> {
                // Vite는 dist/, CRA는 build/
                Path dist = workDir.resolve("dist");
                Path build = workDir.resolve("build");
                if (Files.isDirectory(dist)) yield dist;
                if (Files.isDirectory(build)) yield build;
                throw new RuntimeException("React 빌드 결과물을 찾을 수 없습니다 (dist/ 또는 build/)");
            }
            case CUSTOM -> workDir;
        };
    }

    private Path findJar(Path dir) throws Exception {
        if (!Files.isDirectory(dir)) {
            throw new RuntimeException("빌드 결과 디렉토리가 없습니다: " + dir);
        }
        try (var stream = Files.list(dir)) {
            return stream
                    .filter(p -> p.toString().endsWith(".jar")
                            && !p.getFileName().toString().contains("original-")
                            && !p.getFileName().toString().contains("plain"))
                    .findFirst()
                    .orElseThrow(() -> new RuntimeException("JAR 파일을 찾을 수 없습니다: " + dir));
        }
    }

    private Path resolveSubDir(Project project, Path workDir) {
        if (project.getBuildSubDir() != null && !project.getBuildSubDir().isBlank()) {
            return workDir.resolve(project.getBuildSubDir());
        }
        return workDir;
    }

    private void executeCommand(List<String> cmd, Path workDir, Long buildId) throws Exception {
        // 중단 요청이 이미 들어온 경우 새 커맨드를 시작하지 않음
        if (cancelFlags.getOrDefault(buildId, false)) {
            throw new RuntimeException("중단 요청으로 명령어 실행 건너뜀");
        }

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(workDir.toFile());
        pb.redirectErrorStream(true);

        Process process = pb.start();
        activeProcesses.put(buildId, process);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                logService.append(buildId, line);
            }
        }

        int exitCode = process.waitFor();
        activeProcesses.remove(buildId);

        if (cancelFlags.getOrDefault(buildId, false)) {
            throw new RuntimeException("빌드가 중단되었습니다.");
        }
        if (exitCode != 0) {
            throw new RuntimeException("명령어 실패 (종료 코드: " + exitCode + ")");
        }
    }

    /**
     * 진행 중인 빌드를 강제 중단합니다.
     */
    public void cancelBuild(Long buildId) {
        Build build = buildRepository.findById(buildId).orElseThrow();
        BuildStatus status = build.getStatus();
        if (status == BuildStatus.BUILDING || status == BuildStatus.DEPLOYING || status == BuildStatus.PENDING) {
            cancelFlags.put(buildId, true);
            Process process = activeProcesses.get(buildId);
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
                log.info("Build {} process destroyed", buildId);
            }
        }
    }

    private void cleanupWorkDir(Path workDir, Long buildId) {
        try {
            if (Files.exists(workDir)) {
                Files.walk(workDir)
                        .sorted(java.util.Comparator.reverseOrder())
                        .map(Path::toFile)
                        .forEach(File::delete);
                logService.append(buildId, "[INFO] 작업 디렉토리 정리 완료");
            }
        } catch (Exception e) {
            log.warn("Failed to clean up workspace: {}", workDir, e);
        }
    }

    // GitHub Private Repo: token을 URL에 삽입
    // 주의: 토큰이 git 로그 등에 남을 수 있으므로 개발 환경 전용으로 사용
    private String injectToken(String repoUrl, String token) {
        if (token == null || token.isBlank()) return repoUrl;
        if (repoUrl.startsWith("https://")) {
            return "https://x-access-token:" + token + "@" + repoUrl.substring("https://".length());
        }
        return repoUrl;
    }

    private boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() > maxLen ? s.substring(0, maxLen) : s;
    }
}
