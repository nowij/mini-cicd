package com.cicd.service;

import com.cicd.model.Project;
import com.jcraft.jsch.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Properties;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeployService {

    private final LogService logService;
    private final ScriptService scriptService;

    public void deploy(Project project, Path artifactPath, Long buildId) throws Exception {
        if (project.getDeployHost() == null || project.getDeployHost().isBlank()) {
            logService.append(buildId, "[DEPLOY] 배포 서버가 설정되지 않아 배포를 건너뜁니다.");
            return;
        }

        Session session = connectSsh(project, buildId);
        try {
            // 1. 기존 앱 종료
            if (hasValue(project.getStopCommand())) {
                logService.append(buildId, "[DEPLOY] 기존 앱 종료 중...");
                runRemoteCommand(session, project.getStopCommand(), buildId, true);
            }

            // 2. 배포 디렉토리 생성
            if (hasValue(project.getDeployTargetDir())) {
                String mkdirCmd = project.getDeployOs().name().equals("WINDOWS")
                        ? "if not exist \"" + project.getDeployTargetDir() + "\" mkdir \"" + project.getDeployTargetDir() + "\""
                        : "mkdir -p " + project.getDeployTargetDir();
                runRemoteCommand(session, mkdirCmd, buildId, true);
            }

            // 3. 아티팩트 업로드
            logService.append(buildId, "[DEPLOY] 파일 업로드 중...");
            uploadArtifacts(session, artifactPath, project.getDeployTargetDir(), buildId);

            // 3.5 스크립트 파일 업로드 (등록된 경우)
            if (hasValue(project.getScriptFileName())) {
                uploadScriptFile(session, project, buildId);
            }

            // 4. 앱 시작
            if (hasValue(project.getStartCommand())) {
                logService.append(buildId, "[DEPLOY] 앱 시작 중...");
                runRemoteCommand(session, project.getStartCommand(), buildId, false);
            }

            logService.append(buildId, "[DEPLOY] 배포 완료!");
        } finally {
            if (session != null && session.isConnected()) {
                session.disconnect();
            }
        }
    }

    private Session connectSsh(Project project, Long buildId) throws Exception {
        JSch jsch = new JSch();

        boolean useKey = hasValue(project.getDeployKeyPath());
        boolean usePassword = hasValue(project.getDeployPassword());

        if (useKey) {
            jsch.addIdentity(project.getDeployKeyPath());
            logService.append(buildId, "[DEPLOY] SSH 키 인증: " + project.getDeployKeyPath());
        } else if (usePassword) {
            logService.append(buildId, "[DEPLOY] SSH 비밀번호 인증");
        } else {
            throw new RuntimeException("SSH 인증 정보가 없습니다. 비밀번호 또는 SSH 키 파일 경로를 입력하세요.");
        }

        int port = project.getDeployPort() != null ? project.getDeployPort() : 22;
        Session session = jsch.getSession(project.getDeployUser(), project.getDeployHost(), port);

        Properties config = new Properties();
        config.put("StrictHostKeyChecking", "no");

        if (useKey) {
            config.put("PreferredAuthentications", "publickey");
        } else {
            // password + keyboard-interactive 모두 시도
            // keyboard-interactive는 UserInfo.getPassword()를 통해 비밀번호를 제공
            config.put("PreferredAuthentications", "password,keyboard-interactive");
            final String pwd = project.getDeployPassword();
            session.setPassword(pwd);
            session.setUserInfo(new UserInfo() {
                @Override public String getPassword()          { return pwd; }
                @Override public boolean promptPassword(String msg)    { return true; }
                @Override public String getPassphrase()        { return null; }
                @Override public boolean promptPassphrase(String msg)  { return false; }
                @Override public boolean promptYesNo(String msg)       { return true; }
                @Override public void showMessage(String msg)          {}
            });
        }

        session.setConfig(config);

        try {
            session.connect(30_000);
        } catch (JSchException e) {
            String hint = buildAuthErrorHint(project);
            throw new RuntimeException("SSH 인증 실패 (" + project.getDeployUser() + "@"
                    + project.getDeployHost() + ":" + port + ")\n" + hint, e);
        }

        logService.append(buildId, "[DEPLOY] SSH 연결 성공: "
                + project.getDeployUser() + "@" + project.getDeployHost() + ":" + port);
        return session;
    }

    private String buildAuthErrorHint(Project project) {
        StringBuilder sb = new StringBuilder();
        sb.append("인증 실패 체크리스트:\n");
        sb.append("  1. 사용자명·비밀번호가 맞는지 확인\n");
        sb.append("  2. SSH 서버에서 비밀번호 인증이 허용되어 있는지 확인\n");
        sb.append("     - Linux: /etc/ssh/sshd_config → PasswordAuthentication yes\n");
        sb.append("     - Mac  : 시스템 설정 → 공유 → 원격 로그인 ON\n");
        sb.append("     - Win  : OpenSSH 서버 서비스 실행 확인\n");
        sb.append("  3. 비밀번호 대신 SSH 키 인증을 사용하면 더 안정적입니다.");
        return sb.toString();
    }

    private void runRemoteCommand(Session session, String command, Long buildId, boolean ignoreError) throws Exception {
        ChannelExec channel = (ChannelExec) session.openChannel("exec");
        channel.setCommand(command);
        channel.setInputStream(null);

        ByteArrayOutputStream errStream = new ByteArrayOutputStream();
        channel.setErrStream(errStream);

        InputStream is = channel.getInputStream();
        channel.connect();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
            String line;
            while ((line = reader.readLine()) != null) {
                logService.append(buildId, line);
            }
        }

        int exitCode = channel.getExitStatus();
        channel.disconnect();

        if (exitCode != 0) {
            String errMsg = errStream.toString();
            if (ignoreError) {
                logService.append(buildId, "[WARN] 명령어 종료 코드: " + exitCode + (errMsg.isBlank() ? "" : " - " + errMsg));
            } else {
                throw new RuntimeException("원격 명령어 실패 (종료 코드 " + exitCode + "): " + errMsg);
            }
        }
    }

    private void uploadArtifacts(Session session, Path localPath, String remotePath, Long buildId) throws Exception {
        ChannelSftp sftp = (ChannelSftp) session.openChannel("sftp");
        sftp.connect();
        try {
            if (Files.isDirectory(localPath)) {
                uploadDirectory(sftp, localPath, remotePath, buildId);
            } else {
                String remoteFile = remotePath + "/" + localPath.getFileName();
                sftp.put(localPath.toString(), remoteFile);
                logService.append(buildId, "[DEPLOY] 업로드 완료: " + localPath.getFileName());
            }
        } finally {
            sftp.disconnect();
        }
    }

    private void uploadDirectory(ChannelSftp sftp, Path localDir, String remotePath, Long buildId) throws Exception {
        try { sftp.mkdir(remotePath); } catch (SftpException ignored) {}

        Files.walkFileTree(localDir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                String rel = localDir.relativize(dir).toString().replace("\\", "/");
                String remoteDir = rel.isEmpty() ? remotePath : remotePath + "/" + rel;
                try { sftp.mkdir(remoteDir); } catch (SftpException ignored) {}
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String rel = localDir.relativize(file).toString().replace("\\", "/");
                try {
                    sftp.put(file.toString(), remotePath + "/" + rel);
                    logService.append(buildId, "[DEPLOY] 업로드: " + rel);
                } catch (SftpException e) {
                    throw new IOException("SFTP 업로드 실패: " + rel, e);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void uploadScriptFile(Session session, Project project, Long buildId) throws Exception {
        scriptService.getScriptPath(project.getId(), project.getScriptFileName()).ifPresent(scriptPath -> {
            try {
                ChannelSftp sftp = (ChannelSftp) session.openChannel("sftp");
                sftp.connect();
                try {
                    String remotePath = project.getDeployTargetDir() + "/" + project.getScriptFileName();
                    sftp.put(scriptPath.toString(), remotePath);
                    logService.append(buildId, "[DEPLOY] 스크립트 업로드: " + project.getScriptFileName());
                } finally {
                    sftp.disconnect();
                }
                // Linux: 실행 권한 부여
                if (project.getDeployOs().name().equals("LINUX")) {
                    String chmod = "chmod +x " + project.getDeployTargetDir() + "/" + project.getScriptFileName();
                    runRemoteCommand(session, chmod, buildId, true);
                }
            } catch (Exception e) {
                log.warn("Script upload failed: {}", e.getMessage());
                try { logService.append(buildId, "[WARN] 스크립트 업로드 실패: " + e.getMessage()); }
                catch (Exception ignored) {}
            }
        });
    }

    public void stopApp(Project project) throws Exception {
        if (!hasValue(project.getDeployHost())) {
            throw new RuntimeException("배포 서버가 설정되지 않았습니다.");
        }
        if (!hasValue(project.getStopCommand())) {
            throw new RuntimeException("종료 명령어가 설정되지 않았습니다.");
        }
        Session session = connectSsh(project, null);
        try {
            runRemoteCommand(session, project.getStopCommand(), null, false);
        } finally {
            if (session != null && session.isConnected()) session.disconnect();
        }
    }

    private boolean hasValue(String s) {
        return s != null && !s.isBlank();
    }
}
