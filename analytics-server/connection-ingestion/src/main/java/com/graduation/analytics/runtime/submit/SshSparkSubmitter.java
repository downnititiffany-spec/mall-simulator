package com.graduation.analytics.runtime.submit;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

/**
 * SSH 远程提交器（§8.2/REMOTE_CLUSTER）：JSch 会话 + spark-submit 命令，stdout 捕获
 * 一行 JobResult JSON（spark-jobs 契约 §24.5）。凭据只读取 RuntimeProfile 的
 * credential_ref 所引用的密钥（由凭据服务提供，不落库不落日志）。
 */
@Slf4j
public class SshSparkSubmitter implements JobSubmitter {

    private final String host;
    private final int port;
    private final String user;
    private final String credential;   // 密钥/口令（调用方从凭据服务注入，禁止持久化）

    public SshSparkSubmitter(String host, int port, String user, String credential) {
        this.host = host;
        this.port = port;
        this.user = user;
        this.credential = credential;
    }

    @Override
    public String type() {
        return "ssh";
    }

    @Override
    public SubmitResult submit(List<String> command, String logPrefix) {
        String jobId = "ssh-" + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 6);
        String commandLine = String.join(" ", command) + " 2>&1 | "
                + "tee /tmp/" + logPrefix + "-" + jobId + ".log";
        String output = exec(commandLine);
        return new SubmitResult(jobId, "ssh submit, tail: " + tail(output));
    }

    @Override
    public String status(String externalJobId) {
        // 远程任务状态：轮询 yarn application -status（REMOTE_CLUSTER 部署端提供）
        return exec("yarn application -status " + externalJobId + " 2>&1 | head -20");
    }

    @Override
    public String logs(String externalJobId) {
        return exec("cat /tmp/*" + externalJobId + "*.log 2>&1 | tail -300");
    }

    @Override
    public void cancel(String externalJobId) {
        exec("yarn application -kill " + externalJobId + " 2>&1");
    }

    @Override
    public HealthResult healthCheck() {
        try {
            String out = exec("which spark-submit && spark-submit --version 2>&1 | head -2");
            return new HealthResult(true, "ssh " + user + "@" + host + ":" + port + " ok, " + tail(out));
        } catch (Exception e) {
            return new HealthResult(false, "ssh probe failed: " + e.getMessage());
        }
    }

    private String exec(String command) {
        try {
            JSch jsch = new JSch();
            Session session = jsch.getSession(user, host, port);
            session.setPassword(credential);
            Properties config = new Properties();
            config.put("StrictHostKeyChecking", "no");
            session.setConfig(config);
            session.connect(30_000);
            try {
                ChannelExec channel = (ChannelExec) session.openChannel("exec");
                channel.setCommand(command);
                channel.setInputStream(null);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                ByteArrayOutputStream err = new ByteArrayOutputStream();
                channel.setOutputStream(out);
                channel.setErrStream(err);
                channel.connect(30_000);
                while (!channel.isClosed()) {
                    Thread.sleep(300);
                }
                String stdout = out.toString(StandardCharsets.UTF_8);
                String stderr = err.toString(StandardCharsets.UTF_8);
                if (channel.getExitStatus() != 0 && stderr.isBlank()) {
                    return "(exit=" + channel.getExitStatus() + ") " + stdout;
                }
                return stdout.isBlank() ? stderr : stdout;
            } finally {
                session.disconnect();
            }
        } catch (Exception e) {
            throw new IllegalStateException("SSH 执行失败: " + e.getMessage(), e);
        }
    }

    private static String tail(String s) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        return t.length() > 500 ? t.substring(t.length() - 500) : t;
    }
}