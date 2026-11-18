package ru.projectsshbutton;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.HostKey;
import com.jcraft.jsch.HostKeyRepository;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.UserInfo;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;

/** SSH execution boundary that has no Android dependency and can be integration-tested on a JVM. */
public final class SshTransport {
    private static final int MAX_COMBINED_OUTPUT_BYTES = 1_048_576;
    private SshTransport() { }

    /** Reads a server key before any private key is loaded or an SSH command is opened. */
    public static String inspectHostKey(Request request) throws Exception {
        Session session = null;
        CapturingHostKeyRepository repository = new CapturingHostKeyRepository();
        try {
            JSch jsch = new JSch();
            jsch.setHostKeyRepository(repository);
            session = jsch.getSession(request.user, request.host, request.port);
            session.setConfig("StrictHostKeyChecking", "yes");
            session.setConfig("PreferredAuthentications", "none");
            session.setUserInfo(new NonInteractiveUserInfo());
            try {
                session.connect(Math.multiplyExact(request.timeoutSeconds, 1000));
            } catch (Exception exception) {
                if (repository.fingerprint == null) throw exception;
            }
            if (repository.fingerprint == null) throw new IllegalStateException("SSH server did not provide a host key.");
            return repository.fingerprint;
        } finally {
            if (session != null) session.disconnect();
        }
    }

    public static Result execute(Request request, byte[] privateKey, char[] passphrase) throws Exception {
        Session session = null;
        ChannelExec channel = null;
        ByteBuffer encodedPassphrase = null;
        byte[] passphraseBytes = null;
        if (passphrase.length > 0) {
            encodedPassphrase = StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(passphrase));
            passphraseBytes = new byte[encodedPassphrase.remaining()];
            encodedPassphrase.get(passphraseBytes);
        }
        try {
            JSch jsch = new JSch();
            jsch.setHostKeyRepository(new PinnedHostKeyRepository(request.hostFingerprint));
            jsch.addIdentity("selected-key", privateKey, null, passphraseBytes);
            session = jsch.getSession(request.user, request.host, request.port);
            session.setConfig("StrictHostKeyChecking", "yes");
            session.setUserInfo(new NonInteractiveUserInfo());
            int timeoutMillis = Math.multiplyExact(request.timeoutSeconds, 1000);
            session.connect(timeoutMillis);
            channel = (ChannelExec) session.openChannel("exec");
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ByteArrayOutputStream errors = new ByteArrayOutputStream();
            OutputBudget outputBudget = new OutputBudget(MAX_COMBINED_OUTPUT_BYTES);
            channel.setCommand(request.command);
            channel.setInputStream(null);
            channel.setOutputStream(new BoundedOutputStream(output, outputBudget));
            channel.setErrStream(new BoundedOutputStream(errors, outputBudget));
            channel.connect(timeoutMillis);
            long deadline = System.currentTimeMillis() + timeoutMillis;
            while (!channel.isClosed() && System.currentTimeMillis() < deadline) Thread.sleep(80);
            if (!channel.isClosed()) throw new IllegalStateException("Command timed out after " + request.timeoutSeconds + " seconds.");
            if (outputBudget.isExceeded()) throw new IOException("SSH command output exceeds 1 MiB limit.");
            return new Result(channel.getExitStatus(), output.toString("UTF-8"), errors.toString("UTF-8"));
        } finally {
            if (passphraseBytes != null) Arrays.fill(passphraseBytes, (byte) 0);
            if (encodedPassphrase != null && encodedPassphrase.hasArray()) Arrays.fill(encodedPassphrase.array(), (byte) 0);
            if (channel != null) channel.disconnect();
            if (session != null) session.disconnect();
        }
    }

    public static final class Request {
        public final String host;
        public final int port;
        public final String user;
        public final String hostFingerprint;
        public final String command;
        public final int timeoutSeconds;

        public Request(String host, int port, String user, String hostFingerprint, String command, int timeoutSeconds) {
            this.host = host;
            this.port = port;
            this.user = user;
            this.hostFingerprint = hostFingerprint;
            this.command = command;
            this.timeoutSeconds = timeoutSeconds;
        }
    }

    public static final class Result {
        public final int exitStatus;
        public final String output;
        public final String errorOutput;

        Result(int exitStatus, String output, String errorOutput) {
            this.exitStatus = exitStatus;
            this.output = output;
            this.errorOutput = errorOutput;
        }
    }

    private static final class OutputBudget {
        private final int maximum;
        private int used;
        private boolean exceeded;

        OutputBudget(int maximum) {
            this.maximum = maximum;
        }

        synchronized void reserve(int count) throws IOException {
            if (count < 0 || used > maximum - count) {
                exceeded = true;
                throw new IOException("SSH command output exceeds 1 MiB limit.");
            }
            used += count;
        }

        synchronized boolean isExceeded() {
            return exceeded;
        }
    }

    private static final class BoundedOutputStream extends OutputStream {
        private final OutputStream delegate;
        private final OutputBudget budget;

        BoundedOutputStream(OutputStream delegate, OutputBudget budget) {
            this.delegate = delegate;
            this.budget = budget;
        }

        @Override
        public void write(int value) throws IOException {
            budget.reserve(1);
            delegate.write(value);
        }

        @Override
        public void write(byte[] values, int offset, int length) throws IOException {
            budget.reserve(length);
            delegate.write(values, offset, length);
        }
    }

    private static final class PinnedHostKeyRepository implements HostKeyRepository {
        private final String expectedFingerprint;

        PinnedHostKeyRepository(String expectedFingerprint) {
            this.expectedFingerprint = expectedFingerprint;
        }

        @Override
        public int check(String host, byte[] key) {
            try {
                String actual = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(
                        MessageDigest.getInstance("SHA-256").digest(key));
                return expectedFingerprint.equals(actual) ? OK : CHANGED;
            } catch (Exception exception) {
                return CHANGED;
            }
        }

        @Override public void add(HostKey hostkey, UserInfo ui) { }
        @Override public void remove(String host, String type) { }
        @Override public void remove(String host, String type, byte[] key) { }
        @Override public String getKnownHostsRepositoryID() { return "Pinned host key"; }
        @Override public HostKey[] getHostKey() { return new HostKey[0]; }
        @Override public HostKey[] getHostKey(String host, String type) { return new HostKey[0]; }
    }

    private static final class CapturingHostKeyRepository implements HostKeyRepository {
        private String fingerprint;

        @Override
        public int check(String host, byte[] key) {
            try {
                fingerprint = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(
                        MessageDigest.getInstance("SHA-256").digest(key));
                return OK;
            } catch (Exception exception) {
                return CHANGED;
            }
        }

        @Override public void add(HostKey hostkey, UserInfo ui) { }
        @Override public void remove(String host, String type) { }
        @Override public void remove(String host, String type, byte[] key) { }
        @Override public String getKnownHostsRepositoryID() { return "Host key inspection"; }
        @Override public HostKey[] getHostKey() { return new HostKey[0]; }
        @Override public HostKey[] getHostKey(String host, String type) { return new HostKey[0]; }
    }

    private static final class NonInteractiveUserInfo implements UserInfo {
        @Override public String getPassphrase() { return null; }
        @Override public String getPassword() { return null; }
        @Override public boolean promptPassword(String message) { return false; }
        @Override public boolean promptPassphrase(String message) { return false; }
        @Override public boolean promptYesNo(String message) { return false; }
        @Override public void showMessage(String message) { }
    }
}
